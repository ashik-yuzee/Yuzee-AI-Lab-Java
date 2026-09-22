package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ported from the inline Gemini calls in yuzee-ai-token-lab/server.ts
 * (POST /api/extract-profile-facts and POST /api/detect-contradictions).
 * Both are cheap, best-effort classification side-calls: on any failure or
 * unparsable model output they return an empty list rather than throwing,
 * matching the old app's try/catch-to-empty-array behavior.
 */
@Service
public class ProfileFactService {

    // ponytail: hardcoded cheap classification model id (matches old app's
    // gemini-3.5-flash-lite for these side-calls) instead of a registry lookup —
    // promote to a shared constant if another utility service needs the same default.
    private static final String UTILITY_MODEL = "gemini-3.5-flash-lite";
    private static final Set<String> CATEGORIES = Set.of("general", "like", "dislike");

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileFactService(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    public List<Map<String, Object>> extractFacts(String userTurnText, String modelId) {
        if (userTurnText == null || userTurnText.isBlank()) return List.of();
        String model = (modelId == null || modelId.isBlank()) ? UTILITY_MODEL : modelId;

        String prompt = "Extract 0-4 SHORT factual statements about the user from this conversation turn.\n"
            + "Focus on: name, location, job/role, years of experience, certifications held, career goals, "
            + "budget constraints, time availability, learning preferences, explicit likes (\"I love\", \"I prefer\", "
            + "\"I enjoy\"), explicit dislikes (\"I hate\", \"I don't like\", \"I avoid\").\n\n"
            + "For each fact, output an object with \"text\" (the fact) and \"category\" "
            + "(one of: \"general\", \"like\", \"dislike\").\n\n"
            + "User message: \"" + truncate(userTurnText, 400) + "\"\n\n"
            + "Return ONLY a JSON array of fact objects. If none, return [].\n"
            + "Example: [{\"text\":\"Works as IT support\",\"category\":\"general\"},"
            + "{\"text\":\"Likes hands-on learning\",\"category\":\"like\"}]";

        try {
            String raw = geminiService.generate(model, null, prompt);
            JsonNode arr = extractJsonArray(raw);
            if (arr == null) return List.of();

            List<Map<String, Object>> facts = new ArrayList<>();
            for (JsonNode node : arr) {
                if (facts.size() >= 4) break;
                String text = node.isTextual() ? node.asText() : node.path("text").asText("");
                if (text.isBlank()) continue;
                String category = node.isTextual() ? "general" : node.path("category").asText("general");
                if (!CATEGORIES.contains(category)) category = "general";
                facts.add(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "text", text,
                    "category", category
                ));
            }
            return facts;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> detectContradictions(String newStatement, List<Map<String, Object>> storedFacts, String modelId) {
        if (newStatement == null || newStatement.isBlank() || storedFacts == null || storedFacts.isEmpty()) {
            return List.of();
        }
        String model = (modelId == null || modelId.isBlank()) ? UTILITY_MODEL : modelId;

        List<Map<String, Object>> trimmed = storedFacts.size() > 15 ? storedFacts.subList(0, 15) : storedFacts;
        StringBuilder factsJson = new StringBuilder("[");
        for (int i = 0; i < trimmed.size(); i++) {
            Map<String, Object> f = trimmed.get(i);
            if (i > 0) factsJson.append(",");
            factsJson.append("{\"id\":\"").append(esc(String.valueOf(f.getOrDefault("id", ""))))
                .append("\",\"text\":\"").append(esc(String.valueOf(f.getOrDefault("text", ""))))
                .append("\"}");
        }
        factsJson.append("]");

        String prompt = "Check if the user's new statement contradicts any of their stored profile facts.\n\n"
            + "New statement: \"" + truncate(newStatement, 400) + "\"\n"
            + "Stored facts: " + factsJson + "\n\n"
            + "Return ONLY a JSON array of contradiction objects. Each object: "
            + "{\"factId\": \"<id of the conflicting stored fact>\", \"conflictingStatement\": \"what the user said that conflicts\", "
            + "\"reason\": \"why it conflicts\"}.\n"
            + "If no contradictions, return []. Keep it short - only clear factual conflicts, not vague differences.";

        try {
            String raw = geminiService.generate(model, null, prompt);
            JsonNode arr = extractJsonArray(raw);
            if (arr == null) return List.of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode node : arr) {
                if (out.size() >= 3) break;
                String factId = node.path("factId").asText("");
                String conflicting = node.path("conflictingStatement").asText("");
                if (factId.isBlank() || conflicting.isBlank()) continue;
                out.add(Map.of(
                    "factId", factId,
                    "conflictingStatement", conflicting,
                    "reason", node.path("reason").asText("")
                ));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
