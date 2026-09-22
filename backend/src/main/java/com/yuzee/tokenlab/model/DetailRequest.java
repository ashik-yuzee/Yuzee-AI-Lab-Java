package com.yuzee.tokenlab.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scoped follow-up research request. Ported from yuzee-ai-token-lab/src/research/types.ts
 * (DetailRequest) and contract.ts (parseDetailRequest).
 */
public class DetailRequest {
    private String parentMessageId;
    private String target;
    private String question;
    private String studyYear;
    private String location;
    private Boolean refresh;

    /**
     * Validates and trims a raw request body, ported from contract.ts's parseDetailRequest().
     * Throws IllegalArgumentException (same field-length limits and required-field checks as the
     * TS original) on anything malformed -- callers should turn that into a 400 response.
     */
    public static DetailRequest parse(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("Enter the course or option and your question.");
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("parentMessageId", 150);
        limits.put("target", 350);
        limits.put("question", 1500);
        limits.put("studyYear", 20);
        limits.put("location", 150);

        DetailRequest result = new DetailRequest();
        for (Map.Entry<String, Integer> e : limits.entrySet()) {
            Object raw = body.getOrDefault(e.getKey(), "");
            if (!(raw instanceof String value) || value.length() > e.getValue()) {
                throw new IllegalArgumentException("Please shorten " + e.getKey() + ".");
            }
            value = value.trim();
            switch (e.getKey()) {
                case "parentMessageId" -> result.parentMessageId = value;
                case "target" -> result.target = value;
                case "question" -> result.question = value;
                case "studyYear" -> result.studyYear = value;
                case "location" -> result.location = value;
                default -> throw new IllegalStateException("unreachable");
            }
        }
        if (isBlank(result.parentMessageId) || isBlank(result.target) || isBlank(result.question)) {
            throw new IllegalArgumentException("Enter the course or option and your question.");
        }
        if (body.containsKey("refresh") && body.get("refresh") != null) {
            Object refresh = body.get("refresh");
            if (!(refresh instanceof Boolean b)) throw new IllegalArgumentException("Refresh must be a yes or no choice.");
            result.refresh = b;
        }
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    public String getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(String parentMessageId) { this.parentMessageId = parentMessageId; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getStudyYear() { return studyYear; }
    public void setStudyYear(String studyYear) { this.studyYear = studyYear; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Boolean getRefresh() { return refresh; }
    public void setRefresh(Boolean refresh) { this.refresh = refresh; }
}
