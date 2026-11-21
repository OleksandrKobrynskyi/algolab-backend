package com.algolab;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for producing API Gateway responses with consistent CORS headers.
 *
 * <p>The allowed origins are read from the {@code ALLOWED_ORIGINS} environment
 * variable (comma separated). The incoming {@code Origin} header is echoed back
 * only when it matches the allowlist; otherwise no
 * {@code Access-Control-Allow-Origin} header is emitted.</p>
 */
public final class CorsUtil {

    public static final String ALLOWED_ORIGINS_ENV = "ALLOWED_ORIGINS";

    static final String ALLOW_HEADERS = "Content-Type,X-Admin-Login,X-Admin-Password";
    static final String ALLOW_METHODS = "OPTIONS,GET,POST";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private CorsUtil() {
    }

    public static Map<String, String> defaultHeaders() {
        return defaultHeaders(null);
    }

    public static Map<String, String> defaultHeaders(Map<String, String> requestHeaders) {
        return defaultHeaders(requestHeaders, System.getenv(ALLOWED_ORIGINS_ENV));
    }

    static Map<String, String> defaultHeaders(Map<String, String> requestHeaders,
                                              String allowedOriginsConfig) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");

        String origin = header(requestHeaders, "Origin");
        if (origin != null && isOriginAllowed(origin, allowedOriginsConfig)) {
            headers.put("Access-Control-Allow-Origin", origin.trim());
            headers.put("Vary", "Origin");
        }

        headers.put("Access-Control-Allow-Headers", ALLOW_HEADERS);
        headers.put("Access-Control-Allow-Methods", ALLOW_METHODS);
        return headers;
    }

    public static APIGatewayProxyResponseEvent buildResponse(int statusCode, Object bodyObj) {
        return buildResponse(statusCode, bodyObj, null);
    }

    public static APIGatewayProxyResponseEvent buildResponse(int statusCode, Object bodyObj,
                                                             Map<String, String> requestHeaders) {
        return buildResponse(statusCode, bodyObj, requestHeaders,
                System.getenv(ALLOWED_ORIGINS_ENV));
    }

    static APIGatewayProxyResponseEvent buildResponse(int statusCode, Object bodyObj,
                                                      Map<String, String> requestHeaders,
                                                      String allowedOriginsConfig) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(defaultHeaders(requestHeaders, allowedOriginsConfig))
                .withBody(GSON.toJson(bodyObj));
    }

    /**
     * Checks an origin against a comma separated allowlist. The wildcard
     * character is honoured only when it is explicitly configured.
     */
    static boolean isOriginAllowed(String origin, String allowedOriginsConfig) {
        if (origin == null || origin.trim().isEmpty()) {
            return false;
        }
        String candidate = origin.trim();
        for (String allowed : parseAllowlist(allowedOriginsConfig)) {
            if ("*".equals(allowed) || allowed.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parseAllowlist(String allowedOriginsConfig) {
        List<String> allowed = new ArrayList<>();
        if (allowedOriginsConfig == null) {
            return allowed;
        }
        for (String value : allowedOriginsConfig.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                allowed.add(trimmed);
            }
        }
        return allowed;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
