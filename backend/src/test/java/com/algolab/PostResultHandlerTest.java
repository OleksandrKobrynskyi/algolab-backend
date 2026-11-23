package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostResultHandlerTest {

    private static final Gson GSON = new Gson();

    @Mock
    private DynamoDbClient ddb;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    private PostResultHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PostResultHandler(ddb, "TestTable");
    }

    @Test
    void shouldReturn201AndSaveIntegerScoreWhenPayloadIsValid() {
        Map<String, Object> payload = Map.of(
                "studentName", "  Alice Smith  ",
                "testName", "  Algebra  ",
                "score", 95,
                "answers", List.of("A", "B"));

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(post(GSON.toJson(payload)), context);

        assertEquals(201, response.getStatusCode());

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(ddb, times(1)).putItem(captor.capture());

        PutItemRequest putItemRequest = captor.getValue();
        assertEquals("TestTable", putItemRequest.tableName());

        Map<String, AttributeValue> item = putItemRequest.item();
        assertEquals("95", item.get("score").n());
        assertEquals("Alice Smith", item.get("studentName").s());
        assertEquals("Algebra", item.get("testName").s());
        assertNotNull(item.get("submissionId").s());
        assertNotNull(item.get("createdAt").s());
        assertEquals(GSON.toJson(List.of("A", "B")), item.get("answers").s());

        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertEquals("saved", body.get("status").getAsString());
        assertEquals(item.get("submissionId").s(), body.get("submissionId").getAsString());
    }

    @Test
    void shouldReturn400WhenStudentNameIsBlank() {
        Map<String, Object> payload = Map.of(
                "studentName", "   ",
                "testName", "Algebra",
                "score", 95,
                "answers", List.of("A"));

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(post(GSON.toJson(payload)), context);

        assertEquals(400, response.getStatusCode());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldReturn400WhenScoreIsOutOfRange() {
        APIGatewayProxyResponseEvent belowRange =
                handler.handleRequest(post(payloadWithScore(-1)), context);
        APIGatewayProxyResponseEvent aboveRange =
                handler.handleRequest(post(payloadWithScore(1500)), context);

        assertEquals(400, belowRange.getStatusCode());
        assertEquals(400, aboveRange.getStatusCode());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldReturn400WhenJsonIsMalformed() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(post(" {broken"), context);

        assertEquals(400, response.getStatusCode());
        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertEquals("Malformed JSON", body.get("error").getAsString());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldReturn500WhenDynamoDbFails() {
        when(context.getLogger()).thenReturn(logger);
        when(ddb.putItem(any(PutItemRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB unavailable"));

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(post(payloadWithScore(95)), context);

        assertEquals(500, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Origin"));
        assertEquals("application/json", response.getHeaders().get("Content-Type"));

        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertEquals("Internal server error", body.get("error").getAsString());
        verify(logger).log(contains("DynamoDB unavailable"));
    }

    @Test
    void shouldReturn200OnOptionsPreflight() {
        APIGatewayProxyRequestEvent request =
                new APIGatewayProxyRequestEvent().withHttpMethod("OPTIONS");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Origin"));
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    // ---- score ----

    @Test
    void shouldAcceptScoreBoundsZeroAndThousand() {
        assertEquals(201, handler.handleRequest(post(payloadWithScore(0)), context).getStatusCode());
        assertEquals(201, handler.handleRequest(post(payloadWithScore(1000)), context).getStatusCode());
    }

    @Test
    void shouldRejectScoreJustOutsideBounds() {
        assertEquals(400, handler.handleRequest(post(payloadWithScore(-1)), context).getStatusCode());
        assertEquals(400, handler.handleRequest(post(payloadWithScore(1001)), context).getStatusCode());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectNonIntegerScoresWithValidationMessage() {
        for (String score : new String[] {"\"500\"", "500.5", "1e2", "true", "null"}) {
            APIGatewayProxyResponseEvent response =
                    handler.handleRequest(post(rawJson(score, "[\"A\"]")), context);
            assertEquals(400, response.getStatusCode(), "score " + score);
            assertFalse(errorOf(response).contains("Malformed"), "score " + score);
        }
        APIGatewayProxyResponseEvent missing = handler.handleRequest(post(
                "{\"studentName\":\"Alice Smith\",\"testName\":\"Algebra\",\"answers\":[\"A\"]}"), context);
        assertEquals(400, missing.getStatusCode());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    // ---- names ----

    @Test
    void shouldRejectNonStringNames() {
        for (String value : new String[] {"123", "true", "null"}) {
            assertEquals(400, handler.handleRequest(post("{\"studentName\":" + value
                    + ",\"testName\":\"Algebra\",\"score\":5,\"answers\":[]}"), context).getStatusCode());
            assertEquals(400, handler.handleRequest(post("{\"studentName\":\"Alice Smith\",\"testName\":"
                    + value + ",\"score\":5,\"answers\":[]}"), context).getStatusCode());
        }
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    // ---- answers ----

    @Test
    void shouldRejectInvalidAnswerElements() {
        for (String answers : new String[] {"[1]", "[true]", "[null]", "[\"\"]", "[\"   \"]",
                "[{\"a\":1}]", "[[\"A\"]]", "\"A\"", "{}", "5", "null"}) {
            assertEquals(400, handler.handleRequest(post(rawJson("5", answers)), context).getStatusCode(),
                    "answers " + answers);
        }
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectMissingAnswers() {
        assertEquals(400, handler.handleRequest(post(
                "{\"studentName\":\"Alice Smith\",\"testName\":\"Algebra\",\"score\":5}"), context)
                .getStatusCode());
    }

    @Test
    void shouldEnforcePerAnswerLength() {
        assertEquals(201, handler.handleRequest(
                post(rawJson("5", GSON.toJson(List.of("a".repeat(256))))), context).getStatusCode());
        assertEquals(400, handler.handleRequest(
                post(rawJson("5", GSON.toJson(List.of("a".repeat(257))))), context).getStatusCode());
    }

    @Test
    void shouldEnforceAnswerCount() {
        assertEquals(201, handler.handleRequest(
                post(rawJson("5", GSON.toJson(Collections.nCopies(200, "A")))), context).getStatusCode());
        assertEquals(400, handler.handleRequest(
                post(rawJson("5", GSON.toJson(Collections.nCopies(201, "A")))), context).getStatusCode());
    }

    @Test
    void shouldEnforceTotalAnswerBytes() {
        // Multi-byte chars: each element is 256 chars (512 bytes), so the 256-char and
        // 200-count limits are not hit; only the 64 KB total can reject.
        String answer = "я".repeat(256);
        assertEquals(201, handler.handleRequest(post(rawJson("5",
                GSON.toJson(Collections.nCopies(128, answer)))), context).getStatusCode());
        APIGatewayProxyResponseEvent tooBig = handler.handleRequest(post(rawJson("5",
                GSON.toJson(Collections.nCopies(129, answer)))), context);
        assertEquals(400, tooBig.getStatusCode());
        assertTrue(errorOf(tooBig).contains("bytes"));
    }

    @Test
    void shouldStoreAnswersAsGsonJsonString() {
        handler.handleRequest(post(rawJson("5", "[\"A\",\"B\"]")), context);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(ddb).putItem(captor.capture());
        assertEquals(GSON.toJson(List.of("A", "B")), captor.getValue().item().get("answers").s());
    }

    // ---- body ----

    @Test
    void shouldRejectBodyOverLimitButNotAtLimit() {
        String atLimit = padTo(rawJson("5", "[\"A\"]"), 102400);
        String overLimit = padTo(rawJson("5", "[\"A\"]"), 102401);
        assertEquals(102400, atLimit.getBytes(StandardCharsets.UTF_8).length);

        assertEquals(201, handler.handleRequest(post(atLimit), context).getStatusCode());

        APIGatewayProxyResponseEvent response = handler.handleRequest(post(overLimit), context);
        assertEquals(400, response.getStatusCode());
        assertTrue(errorOf(response).contains("too large"));
        verify(ddb, times(1)).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectBase64BodyOverLimitButNotAtLimit() {
        String atLimit = padTo(rawJson("5", "[\"A\"]"), 102400);
        String overLimit = padTo(rawJson("5", "[\"A\"]"), 102401);

        assertEquals(201, handler.handleRequest(postBase64(atLimit), context).getStatusCode());
        assertEquals(400, handler.handleRequest(postBase64(overLimit), context).getStatusCode());
        verify(ddb, times(1)).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectHugeBase64WithoutDecoding() {
        String huge = "A".repeat(1_000_000);
        APIGatewayProxyRequestEvent request = post(huge).withIsBase64Encoded(true);
        assertEquals(400, handler.handleRequest(request, context).getStatusCode());
    }

    @Test
    void shouldRejectInvalidBase64() {
        APIGatewayProxyRequestEvent request = post("!!!not-base64!!!").withIsBase64Encoded(true);
        assertEquals(400, handler.handleRequest(request, context).getStatusCode());
    }

    @Test
    void shouldRejectNullAndEmptyBody() {
        assertEquals(400, handler.handleRequest(post(null), context).getStatusCode());
        assertEquals(400, handler.handleRequest(post(""), context).getStatusCode());
        assertEquals(400, handler.handleRequest(post("   "), context).getStatusCode());
        assertEquals(400, handler.handleRequest(
                new APIGatewayProxyRequestEvent().withHttpMethod("POST"), context).getStatusCode());
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectNonObjectRootAndMalformedJson() {
        for (String body : new String[] {"[]", "\"text\"", "123", "true", "null", "{\"a\":", "{broken",
                "{} trailing"}) {
            assertEquals(400, handler.handleRequest(post(body), context).getStatusCode(), body);
        }
        verify(ddb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldRejectDeeplyNestedJsonWith400() {
        String deep = "[".repeat(50_000);
        assertEquals(400, handler.handleRequest(post(deep), context).getStatusCode());
    }

    // ---- createdAt ----

    @Test
    void shouldStoreFixedWidthCreatedAt() {
        handler.handleRequest(post(payloadWithScore(5)), context);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(ddb).putItem(captor.capture());
        String createdAt = captor.getValue().item().get("createdAt").s();

        assertEquals(24, createdAt.length());
        assertTrue(createdAt.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$"), createdAt);
    }

    @Test
    void formatCreatedAtKeepsMillisWhenZero() {
        String formatted = PostResultHandler.formatCreatedAt(Instant.parse("2026-11-23T16:05:09Z"));

        assertEquals("2026-11-23T16:05:09.000Z", formatted);
        assertEquals(24, formatted.length());
    }

    @Test
    void formatCreatedAtTruncatesNanosToMillis() {
        String formatted = PostResultHandler.formatCreatedAt(
                Instant.parse("2026-11-23T16:05:09.007654321Z"));

        assertEquals("2026-11-23T16:05:09.007Z", formatted);
        assertEquals(24, formatted.length());
    }

    private static String rawJson(String score, String answers) {
        return "{\"studentName\":\"Alice Smith\",\"testName\":\"Algebra\",\"score\":" + score
                + ",\"answers\":" + answers + "}";
    }

    private static String padTo(String json, int bytes) {
        // Trailing whitespace is valid JSON, so the padded body still parses.
        return json + " ".repeat(bytes - json.getBytes(StandardCharsets.UTF_8).length);
    }

    private static String errorOf(APIGatewayProxyResponseEvent response) {
        return JsonParser.parseString(response.getBody()).getAsJsonObject().get("error").getAsString();
    }

    private static APIGatewayProxyRequestEvent postBase64(String body) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withIsBase64Encoded(true)
                .withBody(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static APIGatewayProxyRequestEvent post(String body) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withBody(body);
    }

    private static String payloadWithScore(int score) {
        return GSON.toJson(Map.of(
                "studentName", "Alice Smith",
                "testName", "Algebra",
                "score", score,
                "answers", List.of("A", "B")));
    }
}
