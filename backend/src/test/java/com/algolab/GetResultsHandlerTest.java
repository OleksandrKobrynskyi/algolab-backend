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
        Map<String, String> decodedKey = GSON.fromJson(decoded,
                new TypeToken<Map<String, String>>() {
                }.getType());
        assertEquals(Map.of("submissionId", "sub-1"), decodedKey);

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
