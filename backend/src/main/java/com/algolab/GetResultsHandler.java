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
import com.google.gson.JsonPrimitive;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final int MAX_TEST_NAME_LENGTH = 100;

    private static final String TABLE_PARTITION_KEY = "submissionId";
    private static final String GSI_PARTITION_KEY = "testName";
    private static final String GSI_SORT_KEY = "createdAt";

    private static final Set<String> SCAN_CURSOR_KEYS = Set.of(TABLE_PARTITION_KEY);
    private static final Set<String> GSI_CURSOR_KEYS =
            Set.of(TABLE_PARTITION_KEY, GSI_PARTITION_KEY, GSI_SORT_KEY);

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
        if (testName != null && testName.length() > MAX_TEST_NAME_LENGTH) {
            return CorsUtil.buildResponse(400, Map.of(
                    "error", "Invalid testName parameter: must be at most "
                            + MAX_TEST_NAME_LENGTH + " characters"), requestHeaders);
        }

        Map<String, AttributeValue> exclusiveStartKey;
        try {
            exclusiveStartKey = decodeLastKey(params == null ? null : params.get("lastKey"));
            validateCursorForMode(exclusiveStartKey, testName);
        } catch (InvalidCursorException e) {
            return CorsUtil.buildResponse(400, Map.of("error", e.getMessage()), requestHeaders);
        }

        try {
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

    static String encodeLastKey(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, JsonObject> typedKey = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            String attributeName = entry.getKey();
            AttributeValue value = entry.getValue();
            if (attributeName == null || attributeName.isBlank() || value == null) {
                continue;
            }
            JsonObject typedValue = encodeAttributeValue(value);
            if (typedValue != null) {
                typedKey.put(attributeName, typedValue);
            }
        }
        if (typedKey.isEmpty()) {
            return null;
        }
        String json = GSON.toJson(typedKey);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject encodeAttributeValue(AttributeValue value) {
        JsonObject typedValue = new JsonObject();
        if (value.s() != null) {
            typedValue.addProperty("S", value.s());
        } else if (value.n() != null) {
            typedValue.addProperty("N", value.n());
        } else if (value.b() != null) {
            typedValue.addProperty("B",
                    Base64.getEncoder().encodeToString(value.b().asByteArray()));
        } else if (value.bool() != null) {
            typedValue.addProperty("BOOL", value.bool());
        } else {
            return null;
        }
        return typedValue;
    }

    static Map<String, AttributeValue> decodeLastKey(String rawLastKey) {
        if (rawLastKey == null || rawLastKey.isBlank()) {
            return null;
        }
        String raw = rawLastKey.trim();

        // Base64 is the canonical cursor encoding. Raw JSON objects are also
        // accepted for convenience, but anything else is a hard failure.
        String json = decodeBase64(raw);
        if (json == null) {
            if (!raw.startsWith("{")) {
                throw invalidCursor("lastKey must be a Base64-encoded JSON cursor");
            }
            json = raw;
        }
        return parseJsonKey(json);
    }

    private static void validateCursorForMode(Map<String, AttributeValue> cursor, String testName) {
        if (cursor == null) {
            return;
        }
        if (testName == null) {
            if (!cursor.keySet().equals(SCAN_CURSOR_KEYS)) {
                throw invalidCursor("cursor does not match scan mode: expected keys "
                        + SCAN_CURSOR_KEYS + " but got " + cursor.keySet());
            }
            requireStringKey(cursor, TABLE_PARTITION_KEY, "scan cursor");
            return;
        }

        if (!cursor.keySet().equals(GSI_CURSOR_KEYS)) {
            throw invalidCursor("cursor does not match testName query mode: expected keys "
                    + GSI_CURSOR_KEYS + " but got " + cursor.keySet());
        }
        String cursorTestName = requireStringKey(cursor, GSI_PARTITION_KEY, "testName query cursor");
        requireStringKey(cursor, GSI_SORT_KEY, "testName query cursor");
        if (!testName.equals(cursorTestName)) {
            throw invalidCursor("cursor testName does not match the requested testName");
        }
    }

    private static String requireStringKey(Map<String, AttributeValue> cursor, String attributeName,
                                           String context) {
        AttributeValue value = cursor.get(attributeName);
        if (value == null || value.s() == null) {
            throw invalidCursor(context + " requires string attribute '" + attributeName + "'");
        }
        return value.s();
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
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            throw invalidCursor("lastKey is not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            throw invalidCursor("lastKey must decode to a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.isEmpty()) {
            throw invalidCursor("lastKey must contain at least one key attribute");
        }

        Map<String, AttributeValue> key = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String attributeName = entry.getKey();
            if (attributeName == null || attributeName.isBlank()) {
                throw invalidCursor("lastKey contains an empty attribute name");
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                throw invalidCursor("lastKey attribute '" + attributeName
                        + "' must be a typed JSON object");
            }
            JsonObject typedValue = value.getAsJsonObject();
            if (typedValue.size() != 1) {
                throw invalidCursor("lastKey attribute '" + attributeName
                        + "' must contain exactly one type key");
            }
            Map.Entry<String, JsonElement> typeEntry = typedValue.entrySet().iterator().next();
            key.put(attributeName, decodeAttributeValue(attributeName, typeEntry.getKey(),
                    typeEntry.getValue()));
        }
        return key;
    }

    private static AttributeValue decodeAttributeValue(String attributeName, String type,
                                                       JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw invalidCursor("lastKey attribute '" + attributeName
                    + "' type " + type + " must be a JSON primitive");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        switch (type) {
            case "S": {
                if (!primitive.isString()) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type S must be a JSON string");
                }
                return AttributeValue.fromS(primitive.getAsString());
            }
            case "N": {
                if (!primitive.isString()) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type N must be a numeric JSON string");
                }
                String number = primitive.getAsString();
                if (!isValidDynamoNumber(number)) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type N is not a valid number");
                }
                return AttributeValue.fromN(number);
            }
            case "B": {
                if (!primitive.isString()) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type B must be a Base64 JSON string");
                }
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(primitive.getAsString());
                } catch (IllegalArgumentException e) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type B is not valid Base64");
                }
                return AttributeValue.fromB(SdkBytes.fromByteArray(bytes));
            }
            case "BOOL": {
                if (!primitive.isBoolean()) {
                    throw invalidCursor("lastKey attribute '" + attributeName
                            + "' type BOOL must be a JSON boolean");
                }
                return AttributeValue.fromBool(primitive.getAsBoolean());
            }
            default:
                throw invalidCursor("lastKey attribute '" + attributeName
                        + "' has an unsupported type '" + type + "'");
        }
    }

    private static boolean isValidDynamoNumber(String number) {
        if (number == null || number.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(number);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static InvalidCursorException invalidCursor(String message) {
        return new InvalidCursorException("Invalid lastKey parameter: " + message);
    }

    static final class InvalidCursorException extends RuntimeException {
        InvalidCursorException(String message) {
            super(message);
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
