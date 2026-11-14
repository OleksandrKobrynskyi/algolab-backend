package com.algolab;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small helper for producing API Gateway responses with consistent CORS headers.
 */
public final class CorsUtil {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private CorsUtil() {
    }

    public static Map<String, String> defaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "Content-Type,X-Admin-Login,X-Admin-Password");
        headers.put("Access-Control-Allow-Methods", "OPTIONS,POST,GET");
        return headers;
    }

    public static APIGatewayProxyResponseEvent buildResponse(int statusCode, Object bodyObj) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(defaultHeaders())
                .withBody(GSON.toJson(bodyObj));
    }
}
