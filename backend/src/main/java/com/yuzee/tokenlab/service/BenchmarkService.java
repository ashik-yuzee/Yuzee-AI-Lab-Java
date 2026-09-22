package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yuzee.tokenlab.model.Conversation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Java port of the old Node app's POST /api/benchmark (server.ts). Compares the three retention
 * strategies {@link MemoryStrategy} offers for a conversation, either as a modelled token/cost
 * estimate (no provider calls) or as a live Gemini benchmark (one real call per strategy).
 */
@Service
public class BenchmarkService {

    /** Same fixed benchmark prompt the old app used when no live conversation is being probed. */
    private static final String BENCHMARK_PROMPT =
        "Help me transition into cybersecurity and build a 6-month study roadmap.";

    /** Old app's flat modelled-estimate output guess (models.ts / server.ts benchmark route) -- no real generation happens in modelled mode. */
    private static final int ESTIMATED_OUTPUT_TOKENS = 240;

    private final ConversationMemoryService memoryService;
    private final MultiTurnRequestBuilder requestBuilder;
    private final GeminiModelRegistry modelRegistry;
    private final GeminiService geminiService;
    private final SystemPromptService systemPromptService;
    private final ObjectMapper mapper = new ObjectMapper();

    public BenchmarkService(ConversationMemoryService memoryService, MultiTurnRequestBuilder requestBuilder,
                             GeminiModelRegistry modelRegistry, GeminiService geminiService,
                             SystemPromptService systemPromptService) {
        this.memoryService = memoryService;
        this.requestBuilder = requestBuilder;
        this.modelRegistry = modelRegistry;
        this.geminiService = geminiService;
        this.systemPromptService = systemPromptService;
    }

    /**
     * Modelled-estimate mode: zero provider calls. Assembles each strategy's retained context via
     * the real {@link ConversationMemoryService}/{@link MultiTurnRequestBuilder} pipeline and
     * projects the cost via {@link GeminiModelRegistry#calcTurnCost}.
     */
    public List<Map<String, Object>> runModelledBenchmark(Conversation conv, int tokenBudget) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (MemoryStrategy strategy : MemoryStrategy.values()) {
            MemoryResult mem = memoryService.assembleMemory(conv, tokenBudget, strategy, BENCHMARK_PROMPT);
            ArrayNode contents = requestBuilder.buildMultiTurnContents(
                mem.retainedTurns, careerCapsuleText(conv), mem.compactedSummary == null ? "" : mem.compactedSummary,
                BENCHMARK_PROMPT);
            int tokensUsed = requestBuilder.estimateContentsTokens(contents)
                + memoryService.estimateTokens(systemPromptService.getPrompt());
            double cost = modelRegistry.calcTurnCost(GeminiModelRegistry.DEFAULT_MODEL_ID, tokensUsed, ESTIMATED_OUTPUT_TOKENS, 0);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", strategy.name());
            row.put("mode", "estimated");
            row.put("turnsKept", mem.metrics.getTurnsKept());
            row.put("turnsDropped", mem.metrics.getTurnsDropped());
            row.put("tokensUsed", tokensUsed);
            row.put("estimatedCostUsd", roundCost(cost));
            results.add(row);
        }
        return results;
    }

    /**
     * Live mode: actually calls Gemini once per strategy (via the same streaming path real chat
     * turns use) and reports real promptTokens/outputTokens/latency instead of an estimate. Only
     * invoke this when the caller explicitly asked for a live benchmark -- it spends real quota.
     */
    public List<Map<String, Object>> runLiveBenchmark(Conversation conv, String modelId, int tokenBudget) {
        String effectiveModel = (modelId != null && !modelId.isBlank()) ? modelId : GeminiModelRegistry.DEFAULT_MODEL_ID;
        List<Map<String, Object>> results = new ArrayList<>();

        for (MemoryStrategy strategy : MemoryStrategy.values()) {
            MemoryResult mem = memoryService.assembleMemory(conv, tokenBudget, strategy, BENCHMARK_PROMPT);
            ArrayNode contents = requestBuilder.buildMultiTurnContents(
                mem.retainedTurns, careerCapsuleText(conv), mem.compactedSummary == null ? "" : mem.compactedSummary,
                BENCHMARK_PROMPT);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", strategy.name());
            row.put("mode", "live");
            row.put("turnsKept", mem.metrics.getTurnsKept());
            row.put("turnsDropped", mem.metrics.getTurnsDropped());

            long startedAt = System.currentTimeMillis();
            GeminiService.StreamResult[] resultHolder = new GeminiService.StreamResult[1];
            Exception[] errorHolder = new Exception[1];
            CountDownLatch latch = new CountDownLatch(1);

            geminiService.streamGenerateRich(
                effectiveModel,
                systemPromptService.getPrompt(),
                contents,
                null,
                null,
                chunk -> { /* not needed for benchmark comparison */ },
                r -> { resultHolder[0] = r; latch.countDown(); },
                e -> { errorHolder[0] = e; latch.countDown(); }
            );
            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            long latencyMs = System.currentTimeMillis() - startedAt;
            row.put("latencyMs", latencyMs);

            if (errorHolder[0] != null) {
                row.put("error", errorHolder[0].getMessage());
                row.put("tokensUsed", 0);
                row.put("estimatedCostUsd", 0.0);
            } else {
                GeminiService.StreamResult r = resultHolder[0];
                int totalOutput = r.outputTokens + r.thinkingTokens;
                double cost = modelRegistry.calcTurnCost(effectiveModel, r.promptTokens, totalOutput, r.cachedTokens);
                row.put("promptTokens", r.promptTokens);
                row.put("outputTokens", r.outputTokens);
                row.put("thinkingTokens", r.thinkingTokens);
                row.put("cachedTokens", r.cachedTokens);
                row.put("tokensUsed", r.promptTokens + totalOutput);
                row.put("estimatedCostUsd", roundCost(cost));
            }
            results.add(row);
        }
        return results;
    }

    private String careerCapsuleText(Conversation conv) {
        if (conv == null || conv.getCareerContext() == null || conv.getCareerContext().isEmpty()) return "";
        try {
            return mapper.writeValueAsString(conv.getCareerContext());
        } catch (Exception e) {
            return "";
        }
    }

    private static double roundCost(double usd) {
        return Math.round(usd * 1_000_000.0) / 1_000_000.0;
    }
}
