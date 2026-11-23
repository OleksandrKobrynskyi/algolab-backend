package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetResultsHandlerTest {

    private static final String TABLE_NAME = "AlgoLabResults";
    private static final String GSI_NAME = "TestNameIndex";
    private static final String ADMIN_LOGIN = "teacher";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private DynamoDbClient mockDdb;
    private GetResultsHandler handler;

    @BeforeEach
    void setUp() {
        mockDdb = mock(DynamoDbClient.class);
        handler = new GetResultsHandler(mockDdb, TABLE_NAME, GSI_NAME, ADMIN_LOGIN, ADMIN_PASSWORD);
    }

    @Test
    void shouldReturn401WhenCredentialsAreMissingOrWrong() {
        APIGatewayProxyResponseEvent missing = handler.handleRequest(
                request(null, Map.of()), context());
        assertEquals(401, missing.getStatusCode());

        APIGatewayProxyResponseEvent wrong = handler.handleRequest(
                request(Map.of(
                        "X-Admin-Login", "teacher",
                        "X-Admin-Password", "not-the-password"), Map.of()),
                context());
        assertEquals(401, wrong.getStatusCode());

        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturnItemsAndParseBothIntAndDecimalScoresSafely() {
        Map<String, AttributeValue> decimalScoreItem = new LinkedHashMap<>();
        decimalScoreItem.put("submissionId", AttributeValue.fromS("sub-1"));
        decimalScoreItem.put("studentName", AttributeValue.fromS("Alice"));
        decimalScoreItem.put("testName", AttributeValue.fromS("MergeSort"));
        decimalScoreItem.put("createdAt", AttributeValue.fromS("2024-01-01T00:00:00Z"));
        decimalScoreItem.put("score", AttributeValue.fromN("95.0"));
        decimalScoreItem.put("answers", AttributeValue.fromS("[\"A\",\"B\"]"));

        Map<String, AttributeValue> intScoreItem = new LinkedHashMap<>();
        intScoreItem.put("submissionId", AttributeValue.fromS("sub-2"));
        intScoreItem.put("studentName", AttributeValue.fromS("Bob"));
        intScoreItem.put("testName", AttributeValue.fromS("MergeSort"));
        intScoreItem.put("createdAt", AttributeValue.fromS("2024-01-02T00:00:00Z"));
        intScoreItem.put("score", AttributeValue.fromN("87"));
        intScoreItem.put("answers", AttributeValue.fromS("[]"));

        when(mockDdb.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder()
                .items(List.of(decimalScoreItem, intScoreItem))
                .build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(Map.of(
                        "x-admin-login", ADMIN_LOGIN,
                        "x-admin-password", ADMIN_PASSWORD), Map.of()),
                context());

        assertEquals(200, response.getStatusCode());
        verify(mockDdb, times(1)).scan(any(ScanRequest.class));

        JsonObject responseBody = body(response);
        assertEquals(2, responseBody.get("count").getAsInt());
        assertTrue(responseBody.get("nextKey").isJsonNull());

        JsonObject first = responseBody.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("sub-1", first.get("submissionId").getAsString());
        assertEquals("Alice", first.get("studentName").getAsString());
        assertEquals("MergeSort", first.get("testName").getAsString());
        assertEquals("2024-01-01T00:00:00Z", first.get("createdAt").getAsString());
        assertEquals("95", first.get("score").getAsString());
        assertTrue(first.get("score").getAsJsonPrimitive().isNumber());

        List<String> answers = GSON.fromJson(first.get("answers"), STRING_LIST_TYPE);
        assertEquals(List.of("A", "B"), answers);

        JsonObject second = responseBody.getAsJsonArray("items").get(1).getAsJsonObject();
        assertEquals("87", second.get("score").getAsString());
        assertEquals(List.of(), GSON.fromJson(second.get("answers"), STRING_LIST_TYPE));
    }

    @Test
    void shouldClampLimitTo10000WhenExceeded() {
        when(mockDdb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("limit", "99999")), context());

        assertEquals(200, response.getStatusCode());

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(mockDdb).scan(captor.capture());
        assertEquals(10000, captor.getValue().limit());
    }

    @Test
    void shouldEncodeAndDecodePaginationKeyCorrectly() {
        Map<String, AttributeValue> lastEvaluatedKey =
                Map.of("submissionId", AttributeValue.fromS("sub-1"));

        when(mockDdb.scan(any(ScanRequest.class))).thenReturn(
                ScanResponse.builder()
                        .items(List.of())
                        .lastEvaluatedKey(lastEvaluatedKey)
                        .build(),
                ScanResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent firstResponse = handler.handleRequest(
                authorizedRequest(Map.of()), context());

        assertEquals(200, firstResponse.getStatusCode());
        String nextKey = body(firstResponse).get("nextKey").getAsString();
        assertNotNull(nextKey);
        assertFalse(nextKey.isBlank());
        assertFalse(nextKey.contains("+"), "cursor must be URL-safe Base64");
        assertFalse(nextKey.contains("/"), "cursor must be URL-safe Base64");
        assertFalse(nextKey.contains("="), "cursor must be unpadded Base64");

        String decoded = new String(Base64.getUrlDecoder().decode(nextKey), StandardCharsets.UTF_8);
        JsonObject decodedKey = JsonParser.parseString(decoded).getAsJsonObject();
        assertEquals("sub-1",
                decodedKey.getAsJsonObject("submissionId").get("S").getAsString());

        APIGatewayProxyResponseEvent secondResponse = handler.handleRequest(
                authorizedRequest(Map.of("lastKey", nextKey)), context());
        assertEquals(200, secondResponse.getStatusCode());

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(mockDdb, times(2)).scan(captor.capture());
        List<ScanRequest> scanRequests = captor.getAllValues();
        assertTrue(scanRequests.get(0).exclusiveStartKey().isEmpty());
        assertEquals("sub-1", scanRequests.get(1).exclusiveStartKey().get("submissionId").s());
    }

    @Test
    void shouldQueryGsiWhenTestNameFilterIsProvided() {
        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "MergeSort")), context());

        assertEquals(200, response.getStatusCode());

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(mockDdb).query(captor.capture());
        QueryRequest query = captor.getValue();

        assertEquals(TABLE_NAME, query.tableName());
        assertEquals(GSI_NAME, query.indexName());
        assertEquals("testName = :tn", query.keyConditionExpression());
        assertEquals("MergeSort", query.expressionAttributeValues().get(":tn").s());
        assertFalse(query.scanIndexForward());
        verify(mockDdb, never()).scan(any(ScanRequest.class));
    }

    @Test
    void shouldReturn400WhenLimitIsNotNumeric() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("limit", "abc")), context());

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldAcceptTestNameOfExactly100Characters() {
        String testName = "a".repeat(100);
        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", testName)), context());

        assertEquals(200, response.getStatusCode());

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(mockDdb).query(captor.capture());
        assertEquals(testName, captor.getValue().expressionAttributeValues().get(":tn").s());
    }

    @Test
    void shouldReturn400WhenTestNameExceeds100Characters() {
        for (String testName : List.of("a".repeat(101), "a".repeat(2500))) {
            APIGatewayProxyResponseEvent response = handler.handleRequest(
                    authorizedRequest(Map.of("testName", testName)), context());

            assertEquals(400, response.getStatusCode());
            assertTrue(body(response).get("error").getAsString().contains("testName"));
            assertCorsHeadersPresent(response);
        }
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldTrimTestNameBeforeModeSelectionAndCursorComparison() {
        String gsiCursor = GetResultsHandler.encodeLastKey(gsiKey("MergeSort"));
        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "  MergeSort  ", "lastKey", gsiCursor)),
                context());

        assertEquals(200, response.getStatusCode());

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(mockDdb).query(captor.capture());
        assertEquals("MergeSort", captor.getValue().expressionAttributeValues().get(":tn").s());
        assertEquals("MergeSort",
                captor.getValue().exclusiveStartKey().get("testName").s());
    }

    @Test
    void shouldTreatBlankTestNameAsUnfilteredScan() {
        when(mockDdb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "   ")), context());

        assertEquals(200, response.getStatusCode());
        verify(mockDdb).scan(any(ScanRequest.class));
        verify(mockDdb, never()).query(any(QueryRequest.class));
    }

    @Test
    void shouldReturn400ForMalformedLastKeyWithoutFallingBackToSubmissionId() {
        List<String> malformedCursors = List.of(
                "sub-1",
                "not-base64!!",
                base64Url("not json"),
                base64Url("[]"),
                base64Url("{}"),
                base64Url("{\"submissionId\":\"sub-1\"}"),
                base64Url("{\"submissionId\":{\"X\":\"sub-1\"}}"),
                base64Url("{\"submissionId\":{\"S\":\"sub-1\",\"N\":\"1\"}}"),
                base64Url("{\"submissionId\":{\"S\":123}}"),
                base64Url("{\"submissionId\":{\"N\":\"abc\"}}"),
                base64Url("{\"submissionId\":{\"B\":\"!!!\"}}"),
                base64Url("{\"submissionId\":{\"BOOL\":\"true\"}}"));

        for (String cursor : malformedCursors) {
            APIGatewayProxyResponseEvent response = handler.handleRequest(
                    authorizedRequest(Map.of("lastKey", cursor)), context());

            assertEquals(400, response.getStatusCode(), "cursor should be rejected: " + cursor);
            assertTrue(body(response).get("error").getAsString().contains("lastKey"));
            assertCorsHeadersPresent(response);
        }
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenBinaryCursorValueIsNotBase64() {
        String cursor = base64Url("{\"submissionId\":{\"B\":\"not base64!\"}}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("lastKey", cursor)), context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("Base64"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenNumericCursorValueIsNotNumeric() {
        String cursor = base64Url("{\"submissionId\":{\"N\":\"twelve\"}}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("lastKey", cursor)), context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("number"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenBooleanCursorValueIsNotBoolean() {
        String cursor = base64Url("{\"submissionId\":{\"BOOL\":1}}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("lastKey", cursor)), context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("BOOL"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenScanCursorIsUsedForGsiQuery() {
        String scanCursor = GetResultsHandler.encodeLastKey(
                Map.of("submissionId", AttributeValue.fromS("sub-1")));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "MergeSort", "lastKey", scanCursor)),
                context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("testName query mode"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenGsiCursorIsUsedForScan() {
        String gsiCursor = GetResultsHandler.encodeLastKey(gsiKey("MergeSort"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("lastKey", gsiCursor)), context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("scan mode"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldReturn400WhenGsiCursorTestNameDoesNotMatchRequest() {
        String gsiCursor = GetResultsHandler.encodeLastKey(gsiKey("MergeSort"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "QuickSort", "lastKey", gsiCursor)),
                context());

        assertEquals(400, response.getStatusCode());
        assertTrue(body(response).get("error").getAsString().contains("does not match"));
        verifyNoInteractions(mockDdb);
    }

    @Test
    void shouldPassMatchingGsiCursorToQuery() {
        Map<String, AttributeValue> cursorKey = gsiKey("MergeSort");
        String gsiCursor = GetResultsHandler.encodeLastKey(cursorKey);
        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                authorizedRequest(Map.of("testName", "MergeSort", "lastKey", gsiCursor)),
                context());

        assertEquals(200, response.getStatusCode());

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(mockDdb).query(captor.capture());
        assertEquals(cursorKey, captor.getValue().exclusiveStartKey());
    }

    @Test
    void shouldRoundTripSNAndBooleanAttributeValueTypes() {
        Map<String, AttributeValue> keyMap = new LinkedHashMap<>();
        keyMap.put("stringKey", AttributeValue.fromS("some text"));
        keyMap.put("numberKey", AttributeValue.fromN("123.45"));
        keyMap.put("binaryKey", AttributeValue.fromB(
                SdkBytes.fromByteArray(new byte[]{0, 1, 2, (byte) 0xFF})));
        keyMap.put("booleanKey", AttributeValue.fromBool(true));

        String encoded = GetResultsHandler.encodeLastKey(keyMap);
        assertNotNull(encoded);

        Map<String, AttributeValue> decoded = GetResultsHandler.decodeLastKey(encoded);
        assertEquals(keyMap, decoded);
        assertEquals(AttributeValue.fromS("some text"), decoded.get("stringKey"));
        assertEquals(AttributeValue.fromN("123.45"), decoded.get("numberKey"));
        assertEquals(AttributeValue.fromB(
                        SdkBytes.fromByteArray(new byte[]{0, 1, 2, (byte) 0xFF})),
                decoded.get("binaryKey"));
        assertEquals(AttributeValue.fromBool(true), decoded.get("booleanKey"));
    }

    @Test
    void shouldRejectInvalidBinaryAndNumericValuesInCodec() {
        String badBinary = base64Url("{\"binaryKey\":{\"B\":\"not base64!\"}}");
        String badNumber = base64Url("{\"numberKey\":{\"N\":\"NaN-ish\"}}");

        assertThrows(GetResultsHandler.InvalidCursorException.class,
                () -> GetResultsHandler.decodeLastKey(badBinary));
        assertThrows(GetResultsHandler.InvalidCursorException.class,
                () -> GetResultsHandler.decodeLastKey(badNumber));
    }

    private static Map<String, AttributeValue> gsiKey(String testName) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("submissionId", AttributeValue.fromS("sub-1"));
        key.put("testName", AttributeValue.fromS(testName));
        key.put("createdAt", AttributeValue.fromS("2024-01-01T00:00:00Z"));
        return key;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertCorsHeadersPresent(APIGatewayProxyResponseEvent response) {
        assertEquals("Content-Type,X-Admin-Login,X-Admin-Password",
                response.getHeaders().get("Access-Control-Allow-Headers"));
        assertEquals("OPTIONS,GET,POST",
                response.getHeaders().get("Access-Control-Allow-Methods"));
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
    }

    private static Context context() {
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        return context;
    }

    private static APIGatewayProxyRequestEvent request(Map<String, String> headers,
                                                       Map<String, String> queryParameters) {
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withHeaders(headers)
                .withQueryStringParameters(queryParameters);
    }

    private static APIGatewayProxyRequestEvent authorizedRequest(Map<String, String> queryParameters) {
        return request(Map.of(
                "X-Admin-Login", ADMIN_LOGIN,
                "X-Admin-Password", ADMIN_PASSWORD), queryParameters);
    }

    private static JsonObject body(APIGatewayProxyResponseEvent response) {
        return JsonParser.parseString(response.getBody()).getAsJsonObject();
    }
}
