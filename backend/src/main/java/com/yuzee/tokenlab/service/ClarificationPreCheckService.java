package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ported from the inline "pre-flight contradiction check" Gemini call in
 * yuzee-ai-token-lab/server.ts (POST /api/pre-check). Runs BEFORE the main
 * turn to decide whether to interrupt with a clarification question. Fail-safe:
 * any error or unparsable model output yields an empty list, so the caller's
 * default (no clarification needed) always proceeds.
 */
@Service
public class ClarificationPreCheckService {

    // ponytail: same cheap classification model as ProfileFactService's side-calls.
    private static final String UTILITY_MODEL = "gemini-3.5-flash-lite";

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClarificationPreCheckService(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    public List<Map<String, Object>> preCheck(String pendingMessageText, List<Map<String, Object>> unresolvedContradictions, String modelId) {
        if (pendingMessageText == null || pendingMessageText.isBlank()
            || unresolvedContradictions == null || unresolvedContradictions.isEmpty()) {
            return List.of();
        }
        String model = (modelId == null || modelId.isBlank()) ? UTILITY_MODEL : modelId;

        List<Map<String, Object>> trimmed = unresolvedContradictions.size() > 3
            ? unresolvedContradictions.subList(0, 3) : unresolvedContradictions;
        StringBuilder contraList = new StringBuilder();
        for (Map<String, Object> c : trimmed) {
            // Accept either the new detectContradictions shape (conflictingStatement/reason)
            // or the old app's shape (fact/contradiction), whichever the caller has on hand.
            String statement = firstNonBlank(c.get("conflictingStatement"), c.get("contradiction"));
            String reason = firstNonBlank(c.get("reason"), c.get("fact"));
            contraList.append("- User previously said: \"").append(truncate(statement, 120))
                .append("\" (").append(truncate(reason, 120)).append(")\n");
        }

        String prompt = "You are a pre-response classifier. A user has unresolved profile contradictions.\n\n"
            + "User's message: \"" + truncate(pendingMessageText, 500) + "\"\n\n"
            + "Unresolved contradictions:\n" + contraList + "\n"
            + "Task: Does the user's message relate to any of these contradictions? If yes, write 1-2 targeted "
            + "questions to resolve them, each with 2-4 short answer options. If no, or the message is a simple "
            + "greeting, return [].\n\n"
            + "Reply ONLY with a JSON array, no text before or after. Each item: "
            + "{\"question\": \"Question?\", \"options\": [\"Option A\", \"Option B\"]}.\n"
            + "If nothing needs clarifying, return [].";

        try {
            String raw = geminiService.generate(model, null, prompt);
            JsonNode arr = extractJsonArray(raw);
            if (arr == null) return List.of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String question = node.path("question").asText("");
                if (question.isBlank()) continue;
                List<String> options = new ArrayList<>();
                for (JsonNode o : node.path("options")) {
                    if (o.isTextual()) options.add(o.asText());
                }
                out.add(Map.of(
                    "questionId", UUID.randomUUID().toString(),
                    "question", question,
                    "options", options
                ));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String firstNonBlank(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank()) return String.valueOf(a);
        if (b != null && !String.valueOf(b).isBlank()) return String.valueOf(b);
        return "";
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private JsonNode extractJsonArray(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < start) return null;
        try {
            JsonNode node = mapper.readTree(raw.substring(start, end + 1));
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
