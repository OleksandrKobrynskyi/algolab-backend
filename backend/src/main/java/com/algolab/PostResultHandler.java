package com.algolab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles POST /results: validates a submission, stores it in DynamoDB and
 * returns the generated submission id.
 */
public class PostResultHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Gson GSON = new Gson();

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

        SubmissionRequest submission;
        try {
            String body = request == null ? null : request.getBody();
            submission = GSON.fromJson(body, SubmissionRequest.class);
        } catch (JsonSyntaxException e) {
            return CorsUtil.buildResponse(400, Map.of("error", "Malformed JSON"), requestHeaders);
        }

        if (submission == null) {
            return CorsUtil.buildResponse(400, Map.of("error", "Malformed JSON"), requestHeaders);
        }

        String validationError = submission.validate();
        if (validationError != null) {
            return CorsUtil.buildResponse(400, Map.of("error", validationError), requestHeaders);
        }

        try {
            String submissionId = UUID.randomUUID().toString();
            String createdAt = Instant.now().toString();

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
}
