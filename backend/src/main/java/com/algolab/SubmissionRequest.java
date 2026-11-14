package com.algolab;

import java.util.List;

/**
 * Incoming POST /results payload.
 */
public class SubmissionRequest {

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

        if (score == null || score <= 0 || score >= 1000) {
            return "score must be strictly between 0 and 1000";
        }

        if (answers == null) {
            return "answers is required";
        }
        if (answers.size() > 200) {
            return "answers must contain at most 200 elements";
        }

        return null;
    }
}
