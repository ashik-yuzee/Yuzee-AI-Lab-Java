package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the original's POST /api/benchmark (server.ts): per strategy, memoryManager.assembleMemory
 * with BENCHMARK_BUDGETS/BENCHMARK_TURNS, then requestAssembler.assembleRequest (single-text
 * contents, no structured output), then either a modelled estimate or one live Gemini stream.
 */
@Service
public class BenchmarkService {

    public static final String DEFAULT_PROMPT = "Help me transition into cybersecurity and build a 6-month study roadmap.";
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    public static final List<String> DEFAULT_STRATEGIES = List.of("BASELINE", "SUMMARY_RECENT", "ADAPTIVE_HYBRID", "SEMANTIC_EVIDENCE");
    private static final Map<String, Integer> BUDGETS = Map.of(
        "BASELINE", 8000, "SUMMARY_RECENT", 1500, "ADAPTIVE_HYBRID", 1500, "SEMANTIC_EVIDENCE", 6000);
    private static final Map<String, Integer> TURNS = Map.of(
        "BASELINE", 10, "SUMMARY_RECENT", 2, "ADAPTIVE_HYBRID", 2, "SEMANTIC_EVIDENCE", 20);
    private static final int ESTIMATED_OUTPUT_TOKENS = 240;
    private static final int OUTPUT_BUDGET = 65536; // resolveOutputBudget()
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private final ConversationMemoryService memoryService;
    private final GeminiService geminiService;
    private final SystemPromptService systemPromptService;
    private final RequestAssemblerService requestAssembler;
    private final ProtocolValidator protocolValidator;
    private final ObjectMapper mapper = new ObjectMapper();

    public BenchmarkService(ConversationMemoryService memoryService, GeminiService geminiService,
                             SystemPromptService systemPromptService, RequestAssemblerService requestAssembler,
                             ProtocolValidator protocolValidator) {
        this.memoryService = memoryService;
        this.geminiService = geminiService;
        this.systemPromptService = systemPromptService;
        this.requestAssembler = requestAssembler;
        this.protocolValidator = protocolValidator;
    }

    public List<Map<String, Object>> run(Conversation conv, String prompt, String model, List<String> strategies, boolean live) {
        String p = prompt == null ? DEFAULT_PROMPT : prompt;
        String m = model == null ? DEFAULT_MODEL : model;
        List<String> strats = strategies == null ? DEFAULT_STRATEGIES : strategies;
        List<ChatMessage> history = conv != null ? conv.getMessages() : List.of();
        String summary = conv != null && conv.getSummaryText() != null ? conv.getSummaryText() : "";
        Map<String, Object> career = conv != null && conv.getCareerContext() != null ? conv.getCareerContext()
            : defaultCareer();
        String systemInstruction = systemPromptService.getPrompt();
        List<Map<String, Object>> results = new ArrayList<>();

        for (String strat : strats) {
            MemoryResult mem = memoryService.assembleMemory(history, BUDGETS.getOrDefault(strat, 1500),
                TURNS.getOrDefault(strat, 2), strat, summary, p);

            List<String> dynamic = new ArrayList<>();
            String careerStr = formatCareerContext(career);
            if (!careerStr.isEmpty()) dynamic.add(careerStr);
            if (mem.summaryText != null && !mem.summaryText.trim().isEmpty()) dynamic.add("PREVIOUS_CONVERSATION_SUMMARY:\n" + mem.summaryText.trim());
            if (mem.recentHistoryText != null && !mem.recentHistoryText.trim().isEmpty()) dynamic.add("RECENT_DIALOGUE_TURNS:\n" + mem.recentHistoryText.trim());
            String dynamicContext = String.join("\n\n", dynamic);
            int dynTokens = ConversationMemoryService.estimateTokens(dynamicContext);
            String userStr = p.trim();
            String contents = dynamicContext.isEmpty() ? userStr : dynamicContext + "\n\n" + userStr;
            int compactionCost = mem.compactionMetrics != null ? mem.compactionMetrics.compactionTotalCost : 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", strat);
            if (live && geminiService.isConfigured()) {
                runLive(row, strat, m, p, systemInstruction, contents, dynTokens, compactionCost);
            } else {
                int userTokens = ConversationMemoryService.estimateTokens(p);
                int sysTokens = ConversationMemoryService.estimateTokens(systemInstruction);
                int totalInput = sysTokens + dynTokens + userTokens;
                row.put("label", strat + " (Modelled Estimate)");
                row.put("model", m);
                row.put("mode", "estimated");
                row.put("inputTokens", totalInput);
                row.put("outputTokens", ESTIMATED_OUTPUT_TOKENS);
                row.put("thinkingTokens", null);
                row.put("cachedTokens", null);
                row.put("totalTokens", totalInput + ESTIMATED_OUTPUT_TOKENS);
                row.put("latencyMs", null);
                row.put("ttftMs", null);
                row.put("generationMs", null);
                row.put("compactionCost", compactionCost);
                row.put("responsePreview", "Modelled structured response adhering to Response Protocol v1.3 envelope.");
                row.put("retainedContextTokens", dynTokens);
                row.put("schemaValid", null);
                row.put("sources", sources("estimate", "estimate", "unavailable", "unavailable"));
                row.put("notes", "Modelled estimate (0 provider calls). Select 'Live Gemini' in benchmark controls to execute live API measurements.");
            }
            results.add(row);
        }
        return results;
    }

    private void runLive(Map<String, Object> row, String strat, String model, String prompt, String systemInstruction,
                         String contentsText, int dynTokens, int compactionCost) {
        ObjectNode extras = mapper.createObjectNode();
        extras.put("maxOutputTokens", OUTPUT_BUDGET);
        RequestAssemblerService.ThinkingResolution thinking = requestAssembler.resolveThinkingConfig(model, "adaptive", prompt);
        if (thinking.thinkingConfig != null) extras.set("thinkingConfig", thinking.thinkingConfig);
        ArrayNode contents = mapper.createArrayNode();
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", contentsText);

        long start = System.currentTimeMillis();
        long[] firstChunk = {0};
        GeminiService.StreamResult[] result = new GeminiService.StreamResult[1];
        CountDownLatch latch = new CountDownLatch(1);
        geminiService.streamGenerateRich(model, systemInstruction, contents, extras, null,
            chunk -> { if (firstChunk[0] == 0) firstChunk[0] = System.currentTimeMillis(); },
            r -> { result[0] = r; latch.countDown(); },
            e -> latch.countDown());
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        GeminiService.StreamResult r = result[0];
        String text = r != null && r.text != null ? r.text : "";
        boolean valid = false;
        if (r != null) {
            try {
                Matcher fence = FENCE.matcher(text);
                valid = protocolValidator.validateProtocolResponse(fence.find() ? fence.group(1).trim() : text, "1.3").isValid();
            } catch (Exception ignored) { /* isValid = false */ }
        }
        long end = System.currentTimeMillis();
        JsonNode usage = r != null ? r.usageMetadata : null;
        int input = usage != null && usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt()
            : ConversationMemoryService.estimateTokens(contentsText) + ConversationMemoryService.estimateTokens(systemInstruction);
        int output = usage != null && usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt()
            : ConversationMemoryService.estimateTokens(text);
        // The original reads usageMeta.thinkingTokenCount, which the provider never sends.
        Integer thinkingTokens = usage != null && usage.hasNonNull("thinkingTokenCount") ? usage.get("thinkingTokenCount").asInt() : null;
        Integer cachedTokens = usage != null && usage.hasNonNull("cachedContentTokenCount") ? usage.get("cachedContentTokenCount").asInt() : null;

        row.put("label", strat + " (Live Gemini Benchmark)");
        row.put("model", model);
        row.put("mode", "live");
        row.put("inputTokens", input);
        row.put("outputTokens", output);
        row.put("thinkingTokens", thinkingTokens);
        row.put("cachedTokens", cachedTokens);
        row.put("totalTokens", usage != null && usage.hasNonNull("totalTokenCount") ? usage.get("totalTokenCount").asInt()
            : input + output + (thinkingTokens == null ? 0 : thinkingTokens));
        row.put("latencyMs", end - start);
        row.put("ttftMs", firstChunk[0] > 0 ? firstChunk[0] - start : null);
        row.put("generationMs", firstChunk[0] > 0 ? end - firstChunk[0] : null);
        row.put("compactionCost", compactionCost);
        row.put("responsePreview", text.length() > 120 ? text.substring(0, 120) : text);
        row.put("retainedContextTokens", dynTokens);
        row.put("schemaValid", valid);
        row.put("sources", sources(usage != null ? "provider" : "estimate", usage != null ? "provider" : "estimate",
            thinkingTokens != null ? "provider" : "unavailable", cachedTokens != null ? "provider" : "unavailable"));
        row.put("notes", "Real Gemini measurement with responseJsonSchema enforcement. Protocol accepted: " + valid);
    }

    private static Map<String, Object> sources(String in, String out, String thinking, String cached) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("inputTokens", in);
        s.put("outputTokens", out);
        s.put("thinkingTokens", thinking);
        s.put("cachedTokens", cached);
        return s;
    }

    private static Map<String, Object> defaultCareer() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("goals", "Transition into Junior SOC Analyst");
        c.put("constraints", "Under $1000, 12 hrs/week");
        return c;
    }

    /** YuzeeRequestAssembler.formatCareerContext(). */
    private static String formatCareerContext(Map<String, Object> capsule) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : capsule.entrySet()) {
            if (!(e.getValue() instanceof String v) || v.trim().isEmpty()) continue;
            lines.add("- [" + e.getKey() + "]: " + v.trim());
        }
        return lines.isEmpty() ? "" : "YUZEE_STRUCTURED_MEMORY_CAPSULE:\n" + String.join("\n", lines);
    }
}
