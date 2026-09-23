package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the original server.ts utility handlers POST /api/extract-profile-facts,
 * /api/detect-contradictions and /api/pre-check: same prompts, model, response shapes, and the
 * same fail-safe fallbacks ({facts:[]}, {contradictions:[]}, {needsClarification:false}).
 */
@Service
public class ProfileFactService {

    private static final String UTILITY_MODEL = "gemini-3.5-flash-lite";
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[\\s\\S]*\\]");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final GeminiService geminiService;
    private final PathwayWhiteboardService stats;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileFactService(GeminiService geminiService, PathwayWhiteboardService stats) {
        this.geminiService = geminiService;
        this.stats = stats;
    }

    /** {facts: [...]} — string facts become {text, category:"general"}, objects pass through. */
    public Map<String, Object> extractFacts(Map<String, Object> body) {
        Object userMessage = body.getOrDefault("userMessage", "");
        Object existingFacts = body.getOrDefault("existingFacts", List.of());
        if (!geminiService.isConfigured() || !truthy(userMessage)) return Map.of("facts", List.of());
        try {
            String prompt = "Extract 0-4 SHORT factual statements about the user from this conversation turn.\n"
                + "Focus on: name, location, job/role, years of experience, certifications held, career goals, budget constraints, time availability, learning preferences, explicit likes (\"I love\", \"I prefer\", \"I enjoy\"), explicit dislikes (\"I hate\", \"I don't like\", \"I avoid\").\n\n"
                + "For each fact, output an object with \"text\" (the fact) and \"category\" (one of: \"general\", \"like\", \"dislike\").\n\n"
                + "User message: \"" + slice(String.valueOf(userMessage), 400) + "\"\n"
                + "Already known facts: " + mapper.writeValueAsString(head(existingFacts, 10)) + "\n\n"
                + "Return ONLY a JSON array of NEW fact objects not already known. If none, return [].\n"
                + "Example: [{\"text\":\"Works as IT support\",\"category\":\"general\"},{\"text\":\"Likes hands-on learning\",\"category\":\"like\"},{\"text\":\"Dislikes online-only courses\",\"category\":\"dislike\"}]";
            GeminiService.TextResult resp = geminiService.generateText(UTILITY_MODEL, prompt, null);
            stats.recordUtility("/api/extract-profile-facts", UTILITY_MODEL, resp);
            String text = (resp.text.isEmpty() ? "[]" : resp.text).trim();
            Matcher match = JSON_ARRAY.matcher(text);
            JsonNode raw = match.find() ? mapper.readTree(match.group()) : mapper.createArrayNode();
            List<Object> facts = new ArrayList<>();
            if (raw.isArray()) {
                for (int i = 0; i < Math.min(4, raw.size()); i++) {
                    JsonNode f = raw.get(i);
                    if (f.isTextual()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("text", f.asText());
                        m.put("category", "general");
                        facts.add(m);
                    } else {
                        facts.add(f);
                    }
                }
            }
            return Map.of("facts", facts);
        } catch (Exception e) {
            return Map.of("facts", List.of());
        }
    }

    /** {contradictions: [...]} (at most 3, passed through as the model wrote them). */
    public Map<String, Object> detectContradictions(Map<String, Object> body) {
        Object userMessage = body.getOrDefault("userMessage", "");
        Object profileFacts = body.getOrDefault("profileFacts", List.of());
        if (!geminiService.isConfigured() || !truthy(userMessage) || length(profileFacts) == 0) {
            return Map.of("contradictions", List.of());
        }
        try {
            String prompt = "Check if the user's message contradicts any of their stored profile facts.\n\n"
                + "User message: \"" + slice(String.valueOf(userMessage), 400) + "\"\n"
                + "Profile facts: " + mapper.writeValueAsString(head(profileFacts, 15)) + "\n\n"
                + "Return ONLY a JSON array of contradiction objects. Each object: {\"fact\": \"the stored fact\", \"contradiction\": \"what the user said that conflicts\"}.\n"
                + "If no contradictions, return []. Keep it short — only clear factual conflicts, not vague differences.";
            GeminiService.TextResult resp = geminiService.generateText(UTILITY_MODEL, prompt, null);
            stats.recordUtility("/api/detect-contradictions", UTILITY_MODEL, resp);
            String text = (resp.text.isEmpty() ? "[]" : resp.text).trim();
            Matcher match = JSON_ARRAY.matcher(text);
            JsonNode contradictions = match.find() ? mapper.readTree(match.group()) : mapper.createArrayNode();
            List<JsonNode> out = new ArrayList<>();
            if (contradictions.isArray()) for (int i = 0; i < Math.min(3, contradictions.size()); i++) out.add(contradictions.get(i));
            return Map.of("contradictions", out);
        } catch (Exception e) {
            return Map.of("contradictions", List.of());
        }
    }

    /** {needsClarification:false} or {needsClarification:true, questions, bridgeMessage}. */
    public Map<String, Object> preCheck(Map<String, Object> body) {
        Object userMessage = body.getOrDefault("userMessage", "");
        Object unresolved = body.getOrDefault("unresolvedContradictions", List.of());
        Map<String, Object> no = Map.of("needsClarification", false);
        if (!geminiService.isConfigured() || !truthy(userMessage) || length(unresolved) == 0) return no;
        try {
            List<String> contra = new ArrayList<>();
            for (Object c : head(unresolved, 3)) {
                Map<?, ?> m = c instanceof Map<?, ?> map ? map : Map.of();
                contra.add("• Previously stated: \"" + slice(js(m.get("fact")), 120) + "\" — Now saying: \"" + slice(js(m.get("contradiction")), 120) + "\"");
            }
            String prompt = "You are a pre-response classifier. A user has unresolved profile contradictions.\n\n"
                + "User's message: \"" + slice(String.valueOf(userMessage), 500) + "\"\n\n"
                + "Unresolved contradictions:\n" + String.join("\n", contra) + "\n\n"
                + "Task: Does the user's message relate to any of these contradictions? If yes, write 1–2 targeted questions to resolve them. If no, or if the message is a simple greeting/skip, return needsClarification:false.\n\n"
                + "Reply ONLY with valid JSON — no text before or after:\n"
                + "Related → {\"needsClarification\":true,\"bridgeMessage\":\"One sentence explaining why you need to ask\",\"questions\":[{\"dimension\":\"snake_case\",\"text\":\"Question?\",\"ui_type\":\"single_select\",\"required\":true,\"options\":[{\"id\":\"a\",\"label\":\"Option A\"},{\"id\":\"b\",\"label\":\"Option B\"}]}]}\n"
                + "Not related → {\"needsClarification\":false}";
            GeminiService.TextResult resp = geminiService.generateText(UTILITY_MODEL, prompt, null);
            stats.recordUtility("/api/pre-check", UTILITY_MODEL, resp);
            String text = (resp.text.isEmpty() ? "{}" : resp.text).trim();
            Matcher match = JSON_OBJECT.matcher(text);
            if (!match.find()) return no;
            JsonNode parsed = mapper.readTree(match.group());
            JsonNode questions = parsed.path("questions");
            if (parsed.path("needsClarification").isBoolean() && parsed.path("needsClarification").asBoolean()
                && questions.isArray() && questions.size() > 0) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("needsClarification", true);
                out.put("questions", questions);
                if (parsed.has("bridgeMessage")) out.put("bridgeMessage", parsed.get("bridgeMessage"));
                return out;
            }
            return no;
        } catch (Exception e) {
            return no;
        }
    }

    private static String slice(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String js(Object v) {
        return v == null ? "undefined" : String.valueOf(v);
    }

    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    private static int length(Object v) {
        if (v instanceof List<?> l) return l.size();
        if (v instanceof String s) return s.length();
        return 0;
    }

    private static List<?> head(Object v, int n) {
        if (!(v instanceof List<?> l)) return List.of();
        return l.subList(0, Math.min(n, l.size()));
    }
}
