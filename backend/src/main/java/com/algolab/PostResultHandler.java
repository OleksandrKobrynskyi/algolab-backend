package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles POST /results: validates the submission, stores it in DynamoDB and
 * returns the generated submission id.
 */
public class PostResultHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Object>> BODY_TYPE = new TypeToken<>() {
    };

    private final DynamoDbClient ddb;
    private final String tableName;

    public PostResultHandler() {
        this.ddb = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        this.tableName = System.getenv("TABLE_NAME");
    }

    PostResultHandler(DynamoDbClient ddb, String tableName) {
        this.ddb = ddb;
        this.tableName = tableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request,
                                                      Context context) {
        if (request != null && "OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return CorsUtil.buildResponse(200, Map.of());
        }

        Map<String, Object> payload = parseBody(request);
        if (payload == null) {
            return badRequest("Request body must be a valid JSON object");
        }

        Object studentNameValue = payload.get("studentName");
        String studentName = studentNameValue == null ? "" : String.valueOf(studentNameValue).trim();
        if (studentName.isBlank()) {
            return badRequest("studentName is required");
        }

        String testName = stringValue(payload.get("testName"), "");
        String score = intString(payload.get("score"));
        String answers = answersJson(payload.get("answers"));

        String submissionId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("submissionId", AttributeValue.fromS(submissionId));
        item.put("studentName", AttributeValue.fromS(studentName));
        if (!testName.isBlank()) {
            // A blank value is not a valid DynamoDB key, so leave the item out
            // of the TestNameIndex when no test name was supplied.
            item.put("testName", AttributeValue.fromS(testName));
        }
        item.put("score", AttributeValue.fromN(score));
        item.put("answers", AttributeValue.fromS(answers));
        item.put("createdAt", AttributeValue.fromS(createdAt));

        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
        } catch (RuntimeException e) {
            return CorsUtil.buildResponse(500, Map.of("error", "Could not save the result"));
        }

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("status", "saved");
        responseBody.put("submissionId", submissionId);
        return CorsUtil.buildResponse(201, responseBody);
    }

    private static Map<String, Object> parseBody(APIGatewayProxyRequestEvent request) {
        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = GSON.fromJson(request.getBody(), BODY_TYPE.getType());
            return payload == null ? null : payload;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static APIGatewayProxyResponseEvent badRequest(String message) {
        return CorsUtil.buildResponse(400, Map.of("error", message));
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static String intString(Object value) {
        if (value instanceof Number number) {
            return String.valueOf(number.intValue());
        }
        if (value != null) {
            try {
                return String.valueOf(Double.valueOf(String.valueOf(value).trim()).intValue());
            } catch (NumberFormatException ignored) {
                // Fall through to the default below.
            }
        }
        return "0";
    }

    private static String answersJson(Object answers) {
        if (answers instanceof List<?>) {
            return GSON.toJson(answers);
        }
        return "[]";
    }
}
