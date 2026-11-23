package com.algolab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubmissionRequestTest {

    private static SubmissionRequest valid(Integer score, List<String> answers) {
        return new SubmissionRequest("Alice Smith", "Algebra", score, answers);
    }

    private static String json(String score, String answers) {
        return "{\"studentName\":\"Alice Smith\",\"testName\":\"Algebra\",\"score\":" + score
                + ",\"answers\":" + answers + "}";
    }

    private static String parseError(String raw) {
        String[] error = new String[1];
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        SubmissionRequest result = SubmissionRequest.fromJson(obj, error);
        if (result == null) {
            assertNotNull(error[0]);
            return error[0];
        }
        assertNull(error[0]);
        return null;
    }

    @Test
    void validateAcceptsInclusiveScoreBounds() {
        assertNull(valid(0, List.of("A")).validate());
        assertNull(valid(1000, List.of("A")).validate());
    }

    @Test
    void validateRejectsScoreOutsideBoundsAndNull() {
        assertNotNull(valid(-1, List.of("A")).validate());
        assertNotNull(valid(1001, List.of("A")).validate());
        assertNotNull(valid(null, List.of("A")).validate());
    }

    @Test
    void validateAcceptsEmptyAnswersButRejectsNull() {
        assertNull(valid(10, Collections.emptyList()).validate());
        assertNotNull(valid(10, null).validate());
    }

    @Test
    void validateRejectsNullBlankAndWhitespaceAnswers() {
        assertNotNull(valid(10, Arrays.asList("A", null)).validate());
        assertNotNull(valid(10, List.of("")).validate());
        assertNotNull(valid(10, List.of("   ")).validate());
    }

    @Test
    void validateEnforcesPerAnswerLength() {
        assertNull(valid(10, List.of("a".repeat(256))).validate());
        assertNotNull(valid(10, List.of("a".repeat(257))).validate());
    }

    @Test
    void validateEnforcesAnswerCount() {
        assertNull(valid(10, Collections.nCopies(200, "A")).validate());
        assertNotNull(valid(10, Collections.nCopies(201, "A")).validate());
    }

    @Test
    void validateEnforcesTotalUtf8Bytes() {
        // "я" is 2 bytes in UTF-8: 128 * 256 * 2 = 65536 (allowed), 129 * 256 * 2 = 66048 (rejected).
        String answer = "я".repeat(256);
        assertNull(valid(10, Collections.nCopies(128, answer)).validate());
        assertNotNull(valid(10, Collections.nCopies(129, answer)).validate());
    }

    @Test
    void fromJsonAcceptsScoreBoundsAndIntegerLiteral() {
        assertNull(parseError(json("0", "[\"A\"]")));
        assertNull(parseError(json("1000", "[\"A\"]")));
    }

    @Test
    void fromJsonRejectsNonIntegerScores() {
        for (String score : new String[] {"\"500\"", "500.5", "1e2", "true", "null", "[]", "{}", "1000.0"}) {
            assertNotNull(parseError(json(score, "[\"A\"]")), "score " + score);
        }
    }

    @Test
    void fromJsonRejectsOutOfRangeScores() {
        for (String score : new String[] {"-1", "1001", "99999999999999999999"}) {
            assertNotNull(parseError(json(score, "[\"A\"]")), "score " + score);
        }
    }

    @Test
    void fromJsonRejectsMissingScore() {
        assertNotNull(parseError(
                "{\"studentName\":\"Alice Smith\",\"testName\":\"Algebra\",\"answers\":[\"A\"]}"));
    }

    @Test
    void fromJsonRejectsNonStringNames() {
        for (String value : new String[] {"123", "true", "null", "[]", "{}"}) {
            assertNotNull(parseError("{\"studentName\":" + value
                    + ",\"testName\":\"Algebra\",\"score\":5,\"answers\":[]}"), "studentName " + value);
            assertNotNull(parseError("{\"studentName\":\"Alice Smith\",\"testName\":" + value
                    + ",\"score\":5,\"answers\":[]}"), "testName " + value);
        }
    }

    @Test
    void fromJsonRejectsInvalidAnswerElements() {
        for (String answers : new String[] {"[1]", "[true]", "[null]", "[\"\"]", "[\"   \"]",
                "[{\"a\":1}]", "[[\"A\"]]", "\"A\"", "{}", "5", "null"}) {
            assertNotNull(parseError(json("5", answers)), "answers " + answers);
        }
    }

    @Test
    void fromJsonAcceptsValidAnswersAndMapsFields() {
        String[] error = new String[1];
        JsonObject obj = JsonParser.parseString(json("95", "[\"A\",\"B\"]")).getAsJsonObject();
        SubmissionRequest request = SubmissionRequest.fromJson(obj, error);

        assertNotNull(request);
        assertEquals("Alice Smith", request.getStudentName());
        assertEquals(Integer.valueOf(95), request.getScore());
        assertEquals(new ArrayList<>(List.of("A", "B")), request.getAnswers());
    }
}
