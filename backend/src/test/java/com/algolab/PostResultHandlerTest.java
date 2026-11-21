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
