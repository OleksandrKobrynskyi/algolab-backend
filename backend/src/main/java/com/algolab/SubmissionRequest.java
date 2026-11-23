package com.algolab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Incoming POST /results payload.
 */
public class SubmissionRequest {

    static final int MAX_ANSWERS = 200;
    static final int MAX_ANSWER_LENGTH = 256;
    static final int MAX_ANSWERS_TOTAL_BYTES = 65536;
    static final int MIN_SCORE = 0;
    static final int MAX_SCORE = 1000;

    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?(0|[1-9][0-9]*)");

    private String studentName;
    private String testName;
    private Integer score;
    private List<String> answers;

    public SubmissionRequest() {
    }

    public SubmissionRequest(String studentName, String testName, Integer score, List<String> answers) {
        this.studentName = studentName;
        this.testName = testName;
        this.score = score;
        this.answers = answers;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public List<String> getAnswers() {
        return answers;
    }

    public void setAnswers(List<String> answers) {
        this.answers = answers;
    }

    /**
     * Strictly checks a parsed JSON object and, when valid, builds the request.
     * No Gson type coercion is involved: every field type is checked explicitly.
     *
     * @param json     parsed request root
     * @param errorOut single element array that receives the error message on failure
     * @return the request, or {@code null} when invalid (message in {@code errorOut[0]})
     */
    static SubmissionRequest fromJson(JsonObject json, String[] errorOut) {
        String studentName = stringField(json, "studentName", errorOut);
        if (studentName == null) {
            return null;
        }
        String testName = stringField(json, "testName", errorOut);
        if (testName == null) {
            return null;
        }

        JsonElement scoreElement = json.get("score");
        if (scoreElement == null || scoreElement.isJsonNull()) {
            errorOut[0] = "score is required";
            return null;
        }
        if (!scoreElement.isJsonPrimitive() || !scoreElement.getAsJsonPrimitive().isNumber()) {
            errorOut[0] = "score must be an integer number";
            return null;
        }
        String literal = scoreElement.getAsJsonPrimitive().getAsString();
        if (!INTEGER_LITERAL.matcher(literal).matches()) {
            errorOut[0] = "score must be an integer number";
            return null;
        }
        if (literal.length() > 9) {
            errorOut[0] = scoreRangeMessage();
            return null;
        }
        Integer score = Integer.valueOf(literal);

        JsonElement answersElement = json.get("answers");
        if (answersElement == null || answersElement.isJsonNull()) {
            errorOut[0] = "answers is required";
            return null;
        }
        if (!answersElement.isJsonArray()) {
            errorOut[0] = "answers must be an array of strings";
            return null;
        }
        JsonArray array = answersElement.getAsJsonArray();
        if (array.size() > MAX_ANSWERS) {
            errorOut[0] = "answers must contain at most " + MAX_ANSWERS + " elements";
            return null;
        }
        List<String> answers = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                errorOut[0] = "every answer must be a string";
                return null;
            }
            answers.add(element.getAsString());
        }

        SubmissionRequest request = new SubmissionRequest(studentName, testName, score, answers);
        errorOut[0] = request.validate();
        return errorOut[0] == null ? request : null;
    }

    private static String stringField(JsonObject json, String name, String[] errorOut) {
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) {
            errorOut[0] = name + " is required";
            return null;
        }
        if (!element.isJsonPrimitive()) {
            errorOut[0] = name + " must be a string";
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            errorOut[0] = name + " must be a string";
            return null;
        }
        return primitive.getAsString();
    }

    private static String scoreRangeMessage() {
        return "score must be between " + MIN_SCORE + " and " + MAX_SCORE + " inclusive";
    }

    /**
     * Validates the payload.
     *
     * @return an error message, or {@code null} when the payload is valid.
     */
    public String validate() {
        if (studentName == null) {
            return "studentName is required";
        }
        int studentNameLength = studentName.trim().length();
        if (studentNameLength < 2 || studentNameLength > 100) {
            return "studentName must be between 2 and 100 characters";
        }

        if (testName == null) {
            return "testName is required";
        }
        int testNameLength = testName.trim().length();
        if (testNameLength < 1 || testNameLength > 100) {
            return "testName must be between 1 and 100 characters";
        }

        if (score == null) {
            return "score is required";
        }
        if (score < MIN_SCORE || score > MAX_SCORE) {
            return scoreRangeMessage();
        }

        if (answers == null) {
            return "answers is required";
        }
        if (answers.size() > MAX_ANSWERS) {
            return "answers must contain at most " + MAX_ANSWERS + " elements";
        }
        long totalBytes = 0;
        for (String answer : answers) {
            if (answer == null || answer.trim().isEmpty()) {
                return "every answer must be a non-blank string";
            }
            if (answer.length() > MAX_ANSWER_LENGTH) {
                return "each answer must be at most " + MAX_ANSWER_LENGTH + " characters";
            }
            totalBytes += answer.getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalBytes > MAX_ANSWERS_TOTAL_BYTES) {
            return "answers must not exceed " + MAX_ANSWERS_TOTAL_BYTES + " bytes in total";
        }

        return null;
    }
}
