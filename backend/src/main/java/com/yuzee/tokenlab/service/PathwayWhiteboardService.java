package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the original server.ts Pathway Whiteboard handlers (POST /api/pathway/generate,
 * /recommend, /explain, GET /api/pathway/stats) and its in-memory whiteboardStats/utilityStats
 * counters (GET /api/tokens/utility-stats). Same prompts, models and fail-soft behaviour.
 * The whiteboard graph itself is stateless server-side, as in the original.
 */
@Service
public class PathwayWhiteboardService {

    private static final String WHITEBOARD_MODEL = "gemini-3.7-flash";
    private static final String RECOMMEND_MODEL = "gemini-2.5-flash-lite-preview-06-17";
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final Map<String, String> STYLE_GUIDES = Map.of(
        "structured", "STYLE: Structured Learning (methodical, certification-led).\n"
            + "Heavy on verified online courses (Coursera, edX, LinkedIn Learning). Each phase has 2-3 courses before projects.\n"
            + "Timeline: 5-6 months at 10-15 hrs/week. Certifications appear as milestones.",
        "project-driven", "STYLE: Project-Driven (learn by building).\n"
            + "Include at least 1 real project in Phase 1. Courses are brief and supporting; projects are the focus.\n"
            + "Each phase ships something tangible. Timeline: 4-5 months at 15-20 hrs/week.",
        "fast-track", "STYLE: Fast Track / Intensive.\n"
            + "Use FREE resources only (YouTube, freeCodeCamp, official docs, GitHub). Skip beginner fluff — go straight to core skills.\n"
            + "Phases are short and dense. 2-3 months at 25-40 hrs/week. Maximum 4 phases total.",
        "self-paced", "STYLE: Self-Paced / Flexible.\n"
            + "Mix of free and paid resources. Comfortable, sustainable pace. Each phase has wider options.\n"
            + "6-12 months at 5-10 hrs/week. Learning can pause and resume without losing momentum."
    );

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();
    private TokenService tokenService;
    private ConversationLogService logService;

    // server.ts whiteboardStats / utilityStats: process-wide, reset on restart.
    private final long[] whiteboard = new long[3];
    private final long[] utility = new long[3];

    public PathwayWhiteboardService(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @Autowired(required = false)
    void setTokenService(TokenService tokenService) { this.tokenService = tokenService; }

    @Autowired(required = false)
    void setLogService(ConversationLogService logService) { this.logService = logService; }

    public boolean aiAvailable() {
        return geminiService != null && geminiService.isConfigured();
    }

    /**
     * POST /api/pathway/generate after the controller's "AI unavailable" check. Returns
     * {nodes: parsed.nodes (unchanged), edges: parsed.edges || []}.
     * @throws IllegalArgumentException "No messages" (400)
     * @throws IllegalStateException "Invalid AI response" / "Missing nodes" / provider message (500)
     */
    public Map<String, Object> generate(List<Map<String, Object>> messages, String style,
                                        Map<String, Object> answers, String ip) {
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Map<String, Object> m : messages != null ? messages : List.<Map<String, Object>>of()) {
            if ("user".equals(m.get("role")) || "assistant".equals(m.get("role"))) turns.add(m);
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : turns.subList(Math.max(0, turns.size() - 16), turns.size())) {
            String content = truthy(m.get("content")) ? String.valueOf(m.get("content")) : "";
            lines.add(("user".equals(m.get("role")) ? "User" : "AI") + ": " + content.substring(0, Math.min(250, content.length())));
        }
        String recent = String.join("\n", lines);
        if (recent.isBlank()) throw new IllegalArgumentException("No messages");

        String styleGuide = STYLE_GUIDES.getOrDefault(style == null ? "structured" : style, STYLE_GUIDES.get("structured"));
        List<String> answerLines = new ArrayList<>();
        if (answers != null) {
            for (Map.Entry<String, Object> a : answers.entrySet()) {
                if (truthy(a.getValue())) answerLines.add("- " + a.getKey() + ": " + a.getValue());
            }
        }
        String learnerProfile = answerLines.isEmpty() ? ""
            : "\nLEARNER PROFILE (tailor the pathway to this):\n" + String.join("\n", answerLines) + "\n";

        String prompt = "You are a senior career coach generating a complete phased career roadmap.\n"
            + learnerProfile + "\n"
            + styleGuide + "\n\n"
            + "Return ONLY raw JSON. No markdown, no code fences. Just the JSON object.\n\n"
            + "Schema: {\"nodes\":[{\"id\":\"n0\",\"label\":\"3-6 word title\",\"subtitle\":\"tag1 · tag2 · tag3\",\"description\":\"1-2 sentence explanation of why this step matters and what to expect\",\"node_type\":\"...\"}],\"edges\":[{\"from\":\"n0\",\"to\":\"n1\"}]}\n\n"
            + "NODE TYPES (use exactly these strings):\n"
            + "  \"goal\"      — Career destination (exactly 1, first). subtitle = inspiring 1-line tagline.\n"
            + "  \"phase\"     — Section header. subtitle = time estimate e.g. \"4-6 weeks\".\n"
            + "  \"course\"    — A specific online course. subtitle = \"Platform · duration · cost hint\".\n"
            + "  \"skill\"     — A tool/technology to learn. subtitle = \"key topics · tool names\".\n"
            + "  \"project\"   — A real portfolio project. subtitle = \"tech stack · what it delivers\".\n"
            + "  \"resume\"    — Resume/LinkedIn/portfolio action. subtitle = \"specific actionable tip\".\n"
            + "  \"apply\"     — Job search / interview step. subtitle = \"concrete tactic · platform\".\n"
            + "  \"milestone\" — Phase checkpoint / achievement. subtitle = \"what the learner has now\".\n\n"
            + "IMPORTANT — subtitles as tag-style: use \" · \" to separate 2-4 concise tags (max 4 words each).\n"
            + "STRUCTURE: strictly linear. goal → phase → items → milestone → phase → items → milestone → ...\n"
            + "PHASES: 4-5 phases. 18-24 total nodes. Real names, real platforms, real specifics.\n\n"
            + "Conversation:\n" + recent;

        try {
            GeminiService.TextResult resp = geminiService.generateText(WHITEBOARD_MODEL, prompt, null);
            recordWhiteboard("/api/pathway/generate", "wb-gen-", ip, resp);
            Matcher match = JSON_OBJECT.matcher(resp.text.trim());
            if (!match.find()) throw new IllegalStateException("Invalid AI response");
            JsonNode parsed = mapper.readTree(match.group());
            if (!parsed.path("nodes").isArray()) throw new IllegalStateException("Missing nodes");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nodes", parsed.get("nodes"));
            JsonNode edges = parsed.get("edges");
            out.put("edges", edges != null && truthyJson(edges) ? edges : mapper.createArrayNode());
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : "Generation failed", e);
        }
    }

    /** POST /api/pathway/recommend after the controller's no-AI (503) and empty-nodes checks: parsed.suggestions || []. */
    public Object recommend(List<Map<String, Object>> nodes, String goalContext) {
        List<String> nodeList = new ArrayList<>();
        for (Map<String, Object> n : nodes) nodeList.add(js(n.get("type")) + ": " + js(n.get("label")));
        String prompt = "Career pathway builder. Current nodes:\n" + String.join("\n", nodeList) + "\n"
            + (goalContext != null && !goalContext.isEmpty() ? "Goal: " + goalContext : "") + "\n\n"
            + "Suggest exactly 3 next steps to add. Return ONLY JSON:\n"
            + "{\"suggestions\":[{\"type\":\"course|skill|project|resume|apply|milestone\",\"label\":\"specific real name\",\"subtitle\":\"3-6 word detail\",\"reason\":\"one short reason\"}]}\n\n"
            + "Rules: Be specific (real course names, real skills, real tools). Consider logical next step from what exists.";
        try {
            GeminiService.TextResult resp = geminiService.generateText(RECOMMEND_MODEL, prompt, null);
            recordUtility("/api/pathway/recommend", RECOMMEND_MODEL, resp);
            Matcher match = JSON_OBJECT.matcher(resp.text.trim());
            if (!match.find()) return List.of();
            JsonNode suggestions = mapper.readTree(match.group()).get("suggestions");
            return suggestions != null && truthyJson(suggestions) ? suggestions : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** POST /api/pathway/explain: always an answer string, never an HTTP error. */
    public String explain(String nodeLabel, String nodeSubtitle, String question, String goalContext, String ip) {
        try {
            String prompt = "You are a career counsellor. A learner is looking at this step in their career pathway:\n"
                + "Step: \"" + nodeLabel + "\"\n"
                + "Tags: \"" + nodeSubtitle + "\"\n"
                + "Goal context: \"" + (goalContext == null || goalContext.isEmpty() ? "career development" : goalContext) + "\"\n\n"
                + "The learner asks: \"" + question + "\"\n\n"
                + "Reply in 2-4 short paragraphs. Be specific, practical, and encouraging. No JSON, just plain text.";
            if (!aiAvailable()) return "AI not configured.";
            GeminiService.TextResult resp = geminiService.generateText(WHITEBOARD_MODEL, prompt, null);
            recordWhiteboard("/api/pathway/explain", "wb-exp-", ip, resp);
            return resp.text.trim();
        } catch (Exception e) {
            return "Sorry, I couldn't get an explanation right now.";
        }
    }

    /** GET /api/pathway/stats: {calls, inputTokens, outputTokens}. */
    public synchronized Map<String, Object> stats() {
        return counters(whiteboard);
    }

    /** GET /api/tokens/utility-stats: {whiteboard, utility}. */
    public synchronized Map<String, Object> utilityStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whiteboard", counters(whiteboard));
        out.put("utility", counters(utility));
        return out;
    }

    /** utilityStats++ and appendTokenLog for a utility-model call (profile facts, contradictions, pre-check, title, recommend). */
    public void recordUtility(String endpoint, String model, GeminiService.TextResult resp) {
        synchronized (this) {
            utility[0]++;
            utility[1] += resp.promptTokens;
            utility[2] += resp.candidatesTokens;
        }
        if (tokenService != null) tokenService.appendTokenLog(endpoint, model, resp.promptTokens, resp.candidatesTokens, null, null, null);
    }

    private void recordWhiteboard(String endpoint, String idPrefix, String ip, GeminiService.TextResult resp) {
        int in = resp.promptTokens, out = resp.candidatesTokens;
        synchronized (this) {
            whiteboard[0]++;
            whiteboard[1] += in;
            whiteboard[2] += out;
        }
        if (tokenService != null) tokenService.appendTokenLog(endpoint, WHITEBOARD_MODEL, in, out, null, null, null);
        // logTurn is a no-op without a database in the original (and never fails the request).
        if (logService instanceof JdbcConversationLogService) {
            try {
                String cleanIp = (ip != null ? ip : "unknown").replaceFirst("^::ffff:", "");
                logService.logTurn(cleanIp, "whiteboard", idPrefix + System.currentTimeMillis(), WHITEBOARD_MODEL,
                    in, null, null, out, null, (in * 0.10 + out * 0.40) / 1_000_000, null, null, false, true,
                    null, null, null);
            } catch (Exception ignored) { /* .catch(() => {}) */ }
        }
    }

    private static Map<String, Object> counters(long[] c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", c[0]);
        m.put("inputTokens", c[1]);
        m.put("outputTokens", c[2]);
        return m;
    }

    /** JS template-literal rendering of a missing value. */
    private static String js(Object v) {
        return v == null ? "undefined" : String.valueOf(v);
    }

    /** JS truthiness for JSON-decoded values. */
    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        return true;
    }

    private static boolean truthyJson(JsonNode v) {
        if (v.isNull() || v.isMissingNode() || (v.isBoolean() && !v.asBoolean())) return false;
        if (v.isTextual()) return !v.asText().isEmpty();
        if (v.isNumber()) return v.asDouble() != 0;
        return true;
    }
}
