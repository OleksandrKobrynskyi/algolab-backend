package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles GET /results: reads submissions with optional filtering by test name
 * and cursor based pagination.
 */
public class GetResultsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Gson GSON = new Gson();
    private static final Type ANSWERS_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private static final int DEFAULT_LIMIT = 100;
    private static final int MIN_LIMIT = 1;
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
        Map<String, String> requestHeaders = request == null ? null : request.getHeaders();

        if (request != null && "OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return CorsUtil.buildResponse(200, Map.of(), requestHeaders);
        }

        String login = header(request, "X-Admin-Login");
        String password = header(request, "X-Admin-Password");
        if (!isAuthorized(login, password)) {
            return CorsUtil.buildResponse(401,
                    Map.of("error", "Unauthorized: Invalid teacher credentials"), requestHeaders);
        }

        Map<String, String> params = request == null ? null : request.getQueryStringParameters();

        int limit;
        try {
            limit = parseLimit(params);
        } catch (NumberFormatException e) {
            return CorsUtil.buildResponse(400, Map.of("error", "Invalid limit parameter"), requestHeaders);
        }

        String testName = trimToNull(params == null ? null : params.get("testName"));

        try {
            Map<String, AttributeValue> exclusiveStartKey =
                    decodeLastKey(params == null ? null : params.get("lastKey"));

            List<Map<String, AttributeValue>> rawItems;
            Map<String, AttributeValue> lastEvaluatedKey;

            if (testName != null) {
                QueryRequest.Builder query = QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(gsiName)
                        .keyConditionExpression("testName = :tn")
                        .expressionAttributeValues(Map.of(
                                ":tn", AttributeValue.builder().s(testName).build()))
                        .scanIndexForward(false)
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

            List<Map<String, Object>> items = new ArrayList<>();
            if (rawItems != null) {
                for (Map<String, AttributeValue> rawItem : rawItems) {
                    items.add(toCleanItem(rawItem));
                }
            }

            String nextKey = encodeLastKey(lastEvaluatedKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("count", items.size());
            body.put("nextKey", nextKey);
            return CorsUtil.buildResponse(200, body, requestHeaders);
        } catch (Exception e) {
            logError(context, "Could not read results", e);
            return CorsUtil.buildResponse(500, Map.of("error", "Could not read results"), requestHeaders);
        }
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

    private boolean isAuthorized(String login, String password) {
        if (adminLogin == null || adminPassword == null || login == null || password == null) {
            return false;
        }
        boolean loginMatches = MessageDigest.isEqual(
                adminLogin.getBytes(StandardCharsets.UTF_8),
                login.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = MessageDigest.isEqual(
                adminPassword.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
        return loginMatches & passwordMatches;
    }

    private static int parseLimit(Map<String, String> params) {
        String raw = params == null ? null : params.get("limit");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int limit = Integer.parseInt(raw.trim());
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> toCleanItem(Map<String, AttributeValue> item) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (item == null) {
            return clean;
        }
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            String name = entry.getKey();
            AttributeValue value = entry.getValue();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "submissionId", "studentName", "testName", "createdAt" ->
                        clean.put(name, value == null ? null : value.s());
                case "score" -> clean.put(name, parseScore(value));
                case "answers" -> clean.put(name, parseAnswers(value));
                default -> putGenericValue(clean, name, value);
            }
        }
        return clean;
    }

    private static void putGenericValue(Map<String, Object> clean, String name, AttributeValue value) {
        if (value == null) {
            clean.put(name, null);
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

    private static Integer parseScore(AttributeValue value) {
        if (value == null) {
            return 0;
        }
        String raw = value.n() != null ? value.n() : value.s();
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.valueOf(raw.trim()).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> parseAnswers(AttributeValue value) {
        if (value == null || value.s() == null || value.s().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> answers = GSON.fromJson(value.s(), ANSWERS_TYPE);
            return answers == null ? new ArrayList<>() : answers;
        } catch (JsonParseException e) {
            return new ArrayList<>();
        }
    }

    private static String encodeLastKey(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, String> simpleKey = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            AttributeValue value = entry.getValue();
            if (entry.getKey() == null || value == null) {
                continue;
            }
            if (value.s() != null) {
                simpleKey.put(entry.getKey(), value.s());
            } else if (value.n() != null) {
                simpleKey.put(entry.getKey(), value.n());
            } else if (value.bool() != null) {
                simpleKey.put(entry.getKey(), String.valueOf(value.bool()));
            } else if (value.b() != null) {
                simpleKey.put(entry.getKey(), Base64.getEncoder().encodeToString(value.b().asByteArray()));
            }
        }
        if (simpleKey.isEmpty()) {
            return null;
        }
        String json = GSON.toJson(simpleKey);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodeLastKey(String rawLastKey) {
        if (rawLastKey == null || rawLastKey.isBlank()) {
            return null;
        }
        String raw = rawLastKey.trim();

        String json = decodeBase64(raw);
        if (json != null) {
            Map<String, AttributeValue> parsed = parseJsonKey(json);
            if (parsed != null) {
                return parsed;
            }
        }

        // Also accept a raw JSON cursor (useful when a client sends it unencoded).
        if (raw.startsWith("{")) {
            Map<String, AttributeValue> parsed = parseJsonKey(raw);
            if (parsed != null) {
                return parsed;
            }
        }

        return Map.of("submissionId", AttributeValue.fromS(raw));
    }

    private static String decodeBase64(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Not URL-safe Base64, try the standard alphabet below.
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Not Base64 at all.
        }
        return null;
    }

    private static Map<String, AttributeValue> parseJsonKey(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (entry.getKey() == null || value == null || !value.isJsonPrimitive()) {
                    continue;
                }
                key.put(entry.getKey(), AttributeValue.fromS(value.getAsString()));
            }
            return key.isEmpty() ? null : key;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static void logError(Context context, String message, Throwable error) {
        if (context == null || context.getLogger() == null) {
            return;
        }
        String detail = error == null
                ? message
                : message + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
        context.getLogger().log(detail);
    }
}
