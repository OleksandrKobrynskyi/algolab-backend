package com.algolab;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsUtilTest {

    private static final String PRODUCTION_ORIGIN = "https://oleksandrkobrynskyi.github.io";
    private static final String LOCAL_ORIGIN = "http://localhost:5173";
    private static final String ALLOWED_ORIGINS =
            PRODUCTION_ORIGIN + ", " + LOCAL_ORIGIN;

    @Test
    void shouldEchoAllowedOriginAndVaryHeader() {
        Map<String, String> headers = CorsUtil.defaultHeaders(
                Map.of("Origin", PRODUCTION_ORIGIN), ALLOWED_ORIGINS);

        assertEquals(PRODUCTION_ORIGIN,
                headers.get("Access-Control-Allow-Origin"));
        assertEquals("Origin", headers.get("Vary"));
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("Content-Type,X-Admin-Login,X-Admin-Password",
                headers.get("Access-Control-Allow-Headers"));
        assertEquals("OPTIONS,GET,POST", headers.get("Access-Control-Allow-Methods"));
    }

    @Test
    void shouldOmitAllowOriginForDisallowedOrigin() {
        Map<String, String> headers = CorsUtil.defaultHeaders(
                Map.of("Origin", "https://evil.example"), ALLOWED_ORIGINS);

        assertFalse(headers.containsKey("Access-Control-Allow-Origin"));
        assertFalse(headers.containsKey("Vary"));
    }

    @Test
    void shouldOmitAllowOriginWhenOriginIsMissing() {
        Map<String, String> headers = CorsUtil.defaultHeaders(Map.of(), ALLOWED_ORIGINS);

        assertFalse(headers.containsKey("Access-Control-Allow-Origin"));
        assertNull(headers.get("Access-Control-Allow-Origin"));
        assertFalse(headers.containsKey("Vary"));
    }

    @Test
    void shouldOmitAllowOriginWhenHeadersAreNull() {
        Map<String, String> headers = CorsUtil.defaultHeaders(null, ALLOWED_ORIGINS);

        assertFalse(headers.containsKey("Access-Control-Allow-Origin"));
    }

    @Test
    void shouldAllowLocalDevelopmentOrigin() {
        Map<String, String> headers = CorsUtil.defaultHeaders(
                Map.of("Origin", LOCAL_ORIGIN), ALLOWED_ORIGINS);

        assertEquals(LOCAL_ORIGIN, headers.get("Access-Control-Allow-Origin"));
        assertEquals("Origin", headers.get("Vary"));
    }

    @Test
    void shouldLookUpOriginHeaderCaseInsensitively() {
        Map<String, String> lowerCase = CorsUtil.defaultHeaders(
                Map.of("origin", LOCAL_ORIGIN), ALLOWED_ORIGINS);
        Map<String, String> upperCase = CorsUtil.defaultHeaders(
                Map.of("ORIGIN", LOCAL_ORIGIN), ALLOWED_ORIGINS);

        assertEquals(LOCAL_ORIGIN,
                lowerCase.get("Access-Control-Allow-Origin"));
        assertEquals(LOCAL_ORIGIN,
                upperCase.get("Access-Control-Allow-Origin"));
    }

    @Test
    void shouldNotAllowWildcardUnlessExplicitlyConfigured() {
        assertFalse(CorsUtil.isOriginAllowed("https://any.example.com", ALLOWED_ORIGINS));
        assertFalse(CorsUtil.isOriginAllowed("https://any.example.com", ""));
        assertFalse(CorsUtil.isOriginAllowed("https://any.example.com", null));

        assertTrue(CorsUtil.isOriginAllowed("https://any.example.com", "*"));

        Map<String, String> headers = CorsUtil.defaultHeaders(
                Map.of("Origin", "https://any.example.com"), "*");
        assertEquals("https://any.example.com",
                headers.get("Access-Control-Allow-Origin"));
        assertEquals("Origin", headers.get("Vary"));
    }

    @Test
    void shouldBuildResponseWithCorsHeadersForAllowedOrigin() {
        APIGatewayProxyResponseEvent response = CorsUtil.buildResponse(
                201, Map.of("status", "saved"),
                Map.of("Origin", LOCAL_ORIGIN), ALLOWED_ORIGINS);

        assertEquals(201, response.getStatusCode());
        assertEquals(LOCAL_ORIGIN,
                response.getHeaders().get("Access-Control-Allow-Origin"));
        assertEquals("Origin", response.getHeaders().get("Vary"));
        assertEquals("{\"status\":\"saved\"}", response.getBody());
    }
}
