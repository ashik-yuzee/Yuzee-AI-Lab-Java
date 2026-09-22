package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.pathway.PathwayEdge;
import com.yuzee.tokenlab.model.pathway.PathwayNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the old app's Pathway Whiteboard endpoints (server.ts's {@code POST
 * /api/pathway/generate}, {@code /recommend}, {@code /explain} and {@code GET /api/pathway/stats}).
 * This is a distinct feature from {@link MiniPathwayService} ("mini pathway", a short side-panel
 * report) -- the whiteboard is the full visual, editable career-route node-graph builder
 * (old {@code PathwayWhiteboard.tsx}, 1198 lines).
 *
 * // ponytail: the whiteboard graph is stateless server-side. PathwayWhiteboard.tsx persists its
 * // node/edge list to the browser's localStorage (key "yuzee_pathway_v3"), never to a server-side
 * // conversation history -- unlike mini-pathway/objectives/details, which do have Conversation
 * // fields. server.ts's /api/pathway/* handlers confirm this: each one just calls Gemini and
 * // returns the result, with no read/write of any conversation-scoped store. So this port stays
 * // stateless too -- no new Conversation field. Add persistence here only if a future requirement
 * // wants server-side saved pathways (multi-device sync, etc).
 */
@Service
public class PathwayWhiteboardService {

    private static final int GENERATE_MAX_OUTPUT_TOKENS = 8192;
    private static final int RECOMMEND_MAX_OUTPUT_TOKENS = 2048;
    private static final int EXPLAIN_MAX_OUTPUT_TOKENS = 1024;
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    /** The old app's four named pathway presets (PathwayWhiteboard.tsx's PATHWAY_STYLES), same wording
     *  as server.ts's styleGuides. Falls back to "structured" for an unknown/missing style id. */
    private static final Map<String, String> STYLE_GUIDES = Map.of(
        "structured", "STYLE: Structured Learning (methodical, certification-led). Heavy on verified "
            + "online courses (Coursera, edX, LinkedIn Learning). Each phase has 2-3 courses before "
            + "projects. Timeline: 5-6 months at 10-15 hrs/week. Certifications appear as milestones.",
        "project-driven", "STYLE: Project-Driven (learn by building). Include at least 1 real project "
            + "in Phase 1. Courses are brief and supporting; projects are the focus. Each phase ships "
            + "something tangible. Timeline: 4-5 months at 15-20 hrs/week.",
        "fast-track", "STYLE: Fast Track / Intensive. Use FREE resources only (YouTube, freeCodeCamp, "
            + "official docs, GitHub). Skip beginner fluff -- go straight to core skills. Phases are "
            + "short and dense. 2-3 months at 25-40 hrs/week. Maximum 4 phases total.",
        "self-paced", "STYLE: Self-Paced / Flexible. Mix of free and paid resources. Comfortable, "
            + "sustainable pace. Each phase has wider options. 6-12 months at 5-10 hrs/week."
    );

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    // ponytail: process-wide session counters, same pattern as TokenService -- fine for a
    // single-instance dev/demo backend; move to per-conversation or persisted stats if the app
    // ever needs per-user or multi-instance accuracy.
    private final AtomicLong calls = new AtomicLong(0);
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);

    public PathwayWhiteboardService(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    /**
     * Generates a complete phased career pathway for {@code goal}. Returns
     * {@code {"nodes": [PathwayNode...], "edges": [PathwayEdge...]}}.
     *
     * @param goal  the learner's career goal (required).
     * @param style one of "structured" / "project-driven" / "fast-track" / "self-paced"; defaults
     *              to "structured" when null/blank/unrecognised.
     * @param modelId Gemini model id; defaults to {@link GeminiModelRegistry#DEFAULT_MODEL_ID}.
     */
    public Map<String, Object> generate(String goal, String style, String modelId) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("A career goal is needed to generate a pathway.");
        }
        String styleGuide = STYLE_GUIDES.getOrDefault(style, STYLE_GUIDES.get("structured"));
        String prompt = "You are a senior career coach generating a complete phased career roadmap.\n"
            + styleGuide + "\n\n"
            + "Return ONLY raw JSON. No markdown, no code fences. Just the JSON object.\n\n"
            + "Schema: {\"nodes\":[{\"id\":\"n0\",\"label\":\"3-6 word title\","
            + "\"subtitle\":\"tag1 . tag2 . tag3\",\"description\":\"1-2 sentence explanation of why "
            + "this step matters and what to expect\",\"node_type\":\"...\"}],"
            + "\"edges\":[{\"from\":\"n0\",\"to\":\"n1\"}]}\n\n"
            + "NODE TYPES (use exactly these strings):\n"
            + "  \"goal\"      -- Career destination (exactly 1, first). subtitle = inspiring 1-line tagline.\n"
            + "  \"phase\"     -- Section header. subtitle = time estimate e.g. \"4-6 weeks\".\n"
            + "  \"course\"    -- A specific online course. subtitle = \"Platform . duration . cost hint\".\n"
            + "  \"skill\"     -- A tool/technology to learn. subtitle = \"key topics . tool names\".\n"
            + "  \"project\"   -- A real portfolio project. subtitle = \"tech stack . what it delivers\".\n"
            + "  \"resume\"    -- Resume/LinkedIn/portfolio action. subtitle = \"specific actionable tip\".\n"
            + "  \"apply\"     -- Job search / interview step. subtitle = \"concrete tactic . platform\".\n"
            + "  \"milestone\" -- Phase checkpoint / achievement. subtitle = \"what the learner has now\".\n\n"
            + "STRUCTURE: strictly linear. goal -> phase -> items -> milestone -> phase -> items -> milestone -> ...\n"
            + "PHASES: 4-5 phases. 18-24 total nodes. Real names, real platforms, real specifics.\n\n"
            + "Learner's goal: " + goal;

        GeminiService.JsonResult result = callGemini(modelId, prompt, GENERATE_MAX_OUTPUT_TOKENS);
        calls.incrementAndGet();
        inputTokens.addAndGet(result.promptTokens);
        outputTokens.addAndGet(result.outputTokens);

        JsonNode parsed = parseJsonObject(result.text);
        if (parsed == null || !parsed.path("nodes").isArray() || parsed.path("nodes").isEmpty()) {
            throw new IllegalStateException("No pathway generated. Describe your goal more specifically.");
        }

        List<PathwayNode> nodes = new ArrayList<>();
        for (JsonNode n : parsed.path("nodes")) {
            nodes.add(new PathwayNode(
                n.path("id").asText(null),
                n.path("label").asText(null),
                n.path("subtitle").asText(null),
                n.path("description").asText(null),
                n.path("node_type").asText("step")
            ));
        }
        List<PathwayEdge> edges = new ArrayList<>();
        for (JsonNode e : parsed.path("edges")) {
            edges.add(new PathwayEdge(e.path("from").asText(null), e.path("to").asText(null)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /**
     * Suggests exactly 3 next nodes to add, given the current graph. Returns a (possibly empty on
     * error/no-input) list of {@code {type, label, subtitle, reason}} maps.
     */
    public List<Map<String, Object>> recommend(List<Map<String, Object>> existingNodes, String context, String modelId) {
        if (existingNodes == null || existingNodes.isEmpty()) return List.of();

        StringBuilder nodeList = new StringBuilder();
        for (Map<String, Object> n : existingNodes) {
            Object type = n.get("type");
            Object label = n.get("label");
            nodeList.append(type != null ? type : "step").append(": ").append(label != null ? label : "").append('\n');
        }
        String prompt = "Career pathway builder. Current nodes:\n" + nodeList
            + (context != null && !context.isBlank() ? "Goal: " + context + "\n" : "")
            + "\nSuggest exactly 3 next steps to add. Return ONLY JSON:\n"
            + "{\"suggestions\":[{\"type\":\"course|skill|project|resume|apply|milestone\","
            + "\"label\":\"specific real name\",\"subtitle\":\"3-6 word detail\",\"reason\":\"one short reason\"}]}\n\n"
            + "Rules: Be specific (real course names, real skills, real tools). Consider the logical next "
            + "step from what already exists.";

        // ponytail: unlike generate()/explain(), recommend() calls aren't folded into the counters
        // stats() exposes -- the old app tracked these separately under a cheaper "utility" model's
        // counters (utilityStats in server.ts, surfaced via /api/tokens/utility-stats), which is a
        // different endpoint already stubbed elsewhere in SystemController and out of scope here.
        GeminiService.JsonResult result;
        try {
            result = geminiService.generateJson(resolveModel(modelId), null, prompt, RECOMMEND_MAX_OUTPUT_TOKENS);
        } catch (Exception e) {
            return List.of();
        }

        JsonNode parsed = parseJsonObject(result.text);
        if (parsed == null || !parsed.path("suggestions").isArray()) return List.of();

        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (JsonNode s : parsed.path("suggestions")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", s.path("type").asText("step"));
            m.put("label", s.path("label").asText(""));
            m.put("subtitle", s.path("subtitle").asText(""));
            m.put("reason", s.path("reason").asText(""));
            suggestions.add(m);
        }
        return suggestions;
    }

    /**
     * Answers a learner's free-text question about one pathway node. {@code node} carries at
     * least {@code label}; may also carry {@code subtitle} and {@code goalContext}. Never throws --
     * mirrors the old app's fail-soft behaviour, returning an apology string on any error.
     */
    public String explain(Map<String, Object> node, String question, String modelId) {
        if (question == null || question.isBlank()) return "Could not get an explanation.";
        String label = str(node != null ? node.get("label") : null);
        String subtitle = str(node != null ? node.get("subtitle") : null);
        String goalContext = str(node != null ? node.get("goalContext") : null);

        String prompt = "You are a career counsellor. A learner is looking at this step in their career pathway:\n"
            + "Step: \"" + (label != null ? label : "") + "\"\n"
            + "Tags: \"" + (subtitle != null ? subtitle : "") + "\"\n"
            + "Goal context: \"" + (goalContext == null || goalContext.isBlank() ? "career development" : goalContext) + "\"\n\n"
            + "The learner asks: \"" + question + "\"\n\n"
            + "Return ONLY raw JSON, no markdown, no code fences: {\"answer\":\"2-4 short paragraphs, "
            + "specific, practical and encouraging\"}";

        try {
            GeminiService.JsonResult result = callGemini(modelId, prompt, EXPLAIN_MAX_OUTPUT_TOKENS);
            calls.incrementAndGet();
            inputTokens.addAndGet(result.promptTokens);
            outputTokens.addAndGet(result.outputTokens);
            JsonNode parsed = parseJsonObject(result.text);
            String answer = parsed != null ? parsed.path("answer").asText("") : "";
            return answer.isBlank() ? "Sorry, I couldn't get an explanation right now." : answer.trim();
        } catch (Exception e) {
            return "Sorry, I couldn't get an explanation right now.";
        }
    }

    /** Session totals for generate()/explain() calls -- {@code {calls, inputTokens, outputTokens}}. */
    public Map<String, Object> stats() {
        return Map.of(
            "calls", calls.get(),
            "inputTokens", inputTokens.get(),
            "outputTokens", outputTokens.get()
        );
    }

    private GeminiService.JsonResult callGemini(String modelId, String prompt, int maxOutputTokens) {
        try {
            return geminiService.generateJson(resolveModel(modelId), null, prompt, maxOutputTokens);
        } catch (IOException e) {
            throw new IllegalStateException("Pathway generation failed: " + e.getMessage(), e);
        }
    }

    private String resolveModel(String modelId) {
        return (modelId == null || modelId.isBlank()) ? GeminiModelRegistry.DEFAULT_MODEL_ID : modelId;
    }

    private JsonNode parseJsonObject(String text) {
        if (text == null) return null;
        Matcher m = JSON_OBJECT.matcher(text);
        if (!m.find()) return null;
        try {
            return mapper.readTree(m.group());
        } catch (IOException e) {
            return null;
        }
    }

    private String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
