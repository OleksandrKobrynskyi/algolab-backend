package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles POST /results: validates a submission, stores it in DynamoDB and
 * returns the generated submission id.
 */
public class PostResultHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final int MAX_BODY_BYTES = 102400;

    private static final Gson GSON = new Gson();

    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbClient ddb;
    private final String tableName;

    /**
     * Constructor required by the AWS Lambda runtime.
     */
    public PostResultHandler() {
        this(DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build(),
                System.getenv("TABLE_NAME"));
    }

    PostResultHandler(DynamoDbClient ddb, String tableName) {
        this.ddb = ddb;
        this.tableName = tableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request,
                                                      Context context) {
        Map<String, String> requestHeaders = request == null ? null : request.getHeaders();

        if (request != null && "OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return CorsUtil.buildResponse(200, Map.of(), requestHeaders);
        }

        String body;
        try {
            body = readBody(request);
        } catch (RuntimeException e) {
            return CorsUtil.buildResponse(400, Map.of("error", "Malformed request body"), requestHeaders);
        }
        if (body == null || body.trim().isEmpty()) {
            return CorsUtil.buildResponse(400, Map.of("error", "Request body is required"), requestHeaders);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return CorsUtil.buildResponse(400, Map.of("error", "Request body is too large"), requestHeaders);
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException | StackOverflowError e) {
            return CorsUtil.buildResponse(400, Map.of("error", "Malformed JSON"), requestHeaders);
        }
        if (root == null || !root.isJsonObject()) {
            return CorsUtil.buildResponse(400, Map.of("error", "Request body must be a JSON object"),
                    requestHeaders);
        }

        String[] error = new String[1];
        SubmissionRequest submission = SubmissionRequest.fromJson(root.getAsJsonObject(), error);
        if (submission == null) {
            return CorsUtil.buildResponse(400, Map.of("error", error[0]), requestHeaders);
        }

        try {
            String submissionId = UUID.randomUUID().toString();
            String createdAt = formatCreatedAt(Instant.now());

            Map<String, AttributeValue> item = new LinkedHashMap<>();
            item.put("submissionId", AttributeValue.fromS(submissionId));
            item.put("studentName", AttributeValue.fromS(submission.getStudentName().trim()));
            item.put("testName", AttributeValue.fromS(submission.getTestName().trim()));
            // Store the score as a strict integer string so Gson never parses
            // it back as a Double (e.g. "95" instead of "95.0").
            item.put("score", AttributeValue.fromN(String.valueOf(submission.getScore())));
            item.put("answers", AttributeValue.fromS(GSON.toJson(submission.getAnswers())));
            item.put("createdAt", AttributeValue.fromS(createdAt));

            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            Map<String, Object> responseBody = new LinkedHashMap<>();
            responseBody.put("status", "saved");
            responseBody.put("submissionId", submissionId);
            return CorsUtil.buildResponse(201, responseBody, requestHeaders);
        } catch (Exception e) {
            if (context != null) {
                LambdaLogger logger = context.getLogger();
                if (logger != null) {
                    logger.log("Failed to save submission: " + e);
                }
            }
            return CorsUtil.buildResponse(500, Map.of("error", "Internal server error"), requestHeaders);
        }
    }

    /**
     * Returns the request body as text, base64-decoding it first when flagged.
     * An implausibly long base64 string is reported as oversized without decoding it.
     */
    private static String readBody(APIGatewayProxyRequestEvent request) {
        if (request == null || request.getBody() == null) {
            return null;
        }
        String raw = request.getBody();
        if (Boolean.TRUE.equals(request.getIsBase64Encoded())) {
            if (raw.length() > (MAX_BODY_BYTES / 3 + 2) * 4 + 16) {
                return "x".repeat(MAX_BODY_BYTES + 1);
            }
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        }
        return raw;
    }

    /**
     * Fixed-width (24 chars) UTC ISO-8601 timestamp with millisecond precision.
     */
    static String formatCreatedAt(Instant instant) {
        return CREATED_AT_FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }
}
