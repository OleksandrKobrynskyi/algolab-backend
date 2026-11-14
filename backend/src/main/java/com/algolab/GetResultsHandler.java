package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles GET /results: reads submissions with optional filtering by test name
 * and simple pagination.
 */
public class GetResultsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Gson GSON = new Gson();
    private static final Type ANSWERS_TYPE = new TypeToken<List<Object>>() {
    }.getType();
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 10000;

    private final DynamoDbClient ddb;
    private final String tableName;
    private final String gsiName;
    private final String adminLogin;
    private final String adminPassword;

    public GetResultsHandler() {
        this.ddb = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        this.tableName = System.getenv("TABLE_NAME");
        this.gsiName = envOrDefault("GSI_NAME", "TestNameIndex");
        this.adminLogin = System.getenv("ADMIN_LOGIN");
        this.adminPassword = System.getenv("ADMIN_PASSWORD");
    }

    GetResultsHandler(DynamoDbClient ddb, String tableName, String gsiName,
                      String adminLogin, String adminPassword) {
        this.ddb = ddb;
        this.tableName = tableName;
        this.gsiName = gsiName;
        this.adminLogin = adminLogin;
        this.adminPassword = adminPassword;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request,
                                                      Context context) {
        if (request != null && "OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return CorsUtil.buildResponse(200, Map.of());
        }

        String login = header(request, "X-Admin-Login");
        String password = header(request, "X-Admin-Password");
        if (adminLogin == null || adminPassword == null
                || !adminLogin.equals(login) || !adminPassword.equals(password)) {
            return CorsUtil.buildResponse(401, Map.of("error", "Unauthorized"));
        }

        Map<String, String> params = request == null ? null : request.getQueryStringParameters();
        int limit = parseLimit(params);
        String testName = params == null ? null : params.get("testName");
        Map<String, AttributeValue> exclusiveStartKey =
                decodeLastKey(params == null ? null : params.get("lastKey"));

        List<Map<String, AttributeValue>> rawItems;
        Map<String, AttributeValue> lastEvaluatedKey;
        try {
            if (testName != null && !testName.isBlank()) {
                QueryRequest.Builder query = QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(gsiName)
                        .keyConditionExpression("testName = :testName")
                        .expressionAttributeValues(Map.of(
                                ":testName", AttributeValue.fromS(testName)))
                        .limit(limit);
                if (exclusiveStartKey != null) {
                    query.exclusiveStartKey(exclusiveStartKey);
                }
                QueryResponse response = ddb.query(query.build());
                rawItems = response.items();
                lastEvaluatedKey = response.lastEvaluatedKey();
            } else {
                ScanRequest.Builder scan = ScanRequest.builder()
                        .tableName(tableName)
                        .limit(limit);
                if (exclusiveStartKey != null) {
                    scan.exclusiveStartKey(exclusiveStartKey);
                }
                ScanResponse response = ddb.scan(scan.build());
                rawItems = response.items();
                lastEvaluatedKey = response.lastEvaluatedKey();
            }
        } catch (RuntimeException e) {
            return CorsUtil.buildResponse(500, Map.of("error", "Could not read results"));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        if (rawItems != null) {
            for (Map<String, AttributeValue> rawItem : rawItems) {
                items.add(toCleanItem(rawItem));
            }
        }

        String nextKey = encodeLastKey(lastEvaluatedKey);
        JsonObject body = new JsonObject();
        body.add("items", GSON.toJsonTree(items));
        if (nextKey == null) {
            body.add("nextKey", JsonNull.INSTANCE);
        } else {
            body.addProperty("nextKey", nextKey);
        }
        return CorsUtil.buildResponse(200, body);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String header(APIGatewayProxyRequestEvent request, String name) {
        if (request == null || request.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int parseLimit(Map<String, String> params) {
        int limit = DEFAULT_LIMIT;
        if (params != null && params.get("limit") != null) {
            try {
                limit = Integer.parseInt(params.get("limit").trim());
            } catch (NumberFormatException ignored) {
                limit = DEFAULT_LIMIT;
            }
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Map<String, Object> toCleanItem(Map<String, AttributeValue> item) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (item == null) {
            return clean;
        }
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            String name = entry.getKey();
            AttributeValue value = entry.getValue();
            if (value == null) {
                clean.put(name, null);
            } else if ("score".equals(name)) {
                clean.put(name, parseScore(value));
            } else if ("answers".equals(name)) {
                clean.put(name, parseAnswers(value));
            } else if (value.s() != null) {
                clean.put(name, value.s());
            } else if (value.n() != null) {
                clean.put(name, value.n());
            } else if (value.bool() != null) {
                clean.put(name, value.bool());
            } else if (Boolean.TRUE.equals(value.nul())) {
                clean.put(name, null);
            } else if (value.hasSs()) {
                clean.put(name, value.ss());
            } else if (value.hasNs()) {
                clean.put(name, value.ns());
            } else {
                clean.put(name, value.toString());
            }
        }
        return clean;
    }

    private static int parseScore(AttributeValue value) {
        String raw = value.n() != null ? value.n() : value.s();
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.valueOf(raw).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Object> parseAnswers(AttributeValue value) {
        if (value.s() == null || value.s().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Object> answers = GSON.fromJson(value.s(), ANSWERS_TYPE);
            return answers == null ? new ArrayList<>() : answers;
        } catch (JsonParseException e) {
            return new ArrayList<>();
        }
    }

    private static Map<String, AttributeValue> decodeLastKey(String rawLastKey) {
        if (rawLastKey == null || rawLastKey.isBlank()) {
            return null;
        }
        String raw = rawLastKey.trim();
        String json = raw;
        try {
            json = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // It is not Base64, so treat it as a plain submission id.
        }

        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                Map<String, AttributeValue> key = toAttributeMap(parsed.getAsJsonObject());
                if (!key.isEmpty()) {
                    return key;
                }
            }
        } catch (JsonParseException ignored) {
            // Not JSON either; fall back to a plain submission id.
        }

        return Map.of("submissionId", AttributeValue.fromS(raw));
    }

    private static Map<String, AttributeValue> toAttributeMap(JsonObject object) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                key.put(entry.getKey(), AttributeValue.fromS(value.getAsString()));
                continue;
            }
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject typed = value.getAsJsonObject();
            if (typed.has("S")) {
                key.put(entry.getKey(), AttributeValue.fromS(typed.get("S").getAsString()));
            } else if (typed.has("s")) {
                key.put(entry.getKey(), AttributeValue.fromS(typed.get("s").getAsString()));
            } else if (typed.has("N")) {
                key.put(entry.getKey(), AttributeValue.fromN(typed.get("N").getAsString()));
            } else if (typed.has("n")) {
                key.put(entry.getKey(), AttributeValue.fromN(typed.get("n").getAsString()));
            } else if (typed.has("B")) {
                key.put(entry.getKey(), AttributeValue.fromB(SdkBytes.fromByteArray(
                        Base64.getDecoder().decode(typed.get("B").getAsString()))));
            } else if (typed.has("BOOL")) {
                key.put(entry.getKey(), AttributeValue.fromBool(typed.get("BOOL").getAsBoolean()));
            }
        }
        return key;
    }

    private static String encodeLastKey(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String json = GSON.toJson(lastEvaluatedKey);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
