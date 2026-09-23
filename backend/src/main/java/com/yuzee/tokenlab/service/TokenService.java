package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * server.ts session cumulative counters (sessionStats), the local stats file
 * (data/session-stats.json) and the append-only token log (data/token-log.ndjson).
 */
@Service
public class TokenService {

    private static final Path LOCAL_STATS_FILE = Path.of("data", "session-stats.json");
    private static final Path TOKEN_LOG_FILE = Path.of("data", "token-log.ndjson");
    private static final String[] KEYS = {
        "userFacingChatCalls", "totalUserInputTokens", "totalModelInputTokens", "totalUncachedInputTokens",
        "totalModelOutputTokens", "totalThinkingTokens", "totalCachedTokens", "totalUserFacingTokens",
        "compactionCalls", "compactionInputTokens", "compactionOutputTokens", "compactionTotalTokens",
        "baselineEstimatedTokens",
    };

    private static final java.time.format.DateTimeFormatter ISO =
        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private final GeminiModelRegistry modelRegistry;
    private final ObjectMapper mapper = new ObjectMapper();
    /** server.ts sessionStats, same keys and order. */
    private final Map<String, Long> stats = new LinkedHashMap<>();
    private ConversationLogService logService;

    public TokenService(GeminiModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
        for (String k : KEYS) stats.put(k, 0L);
    }

    @Autowired(required = false)
    public void setConversationLogService(ConversationLogService logService) {
        this.logService = logService;
    }

    /** startServer(): local stats file first, then (DB mode) rehydrate from conversation_logs. */
    @PostConstruct
    @SuppressWarnings("unchecked")
    synchronized void loadOnStartup() {
        try {
            if (Files.exists(LOCAL_STATS_FILE)) {
                Map<String, Object> local = mapper.readValue(LOCAL_STATS_FILE.toFile(), Map.class);
                local.forEach((k, v) -> { if (v instanceof Number n) stats.put(k, n.longValue()); });
            }
        } catch (Exception ignored) { /* loadLocalStats: null on any failure */ }
        if (logService instanceof JdbcConversationLogService) {
            try {
                Map<String, Object> s = logService.loadSessionStats();
                long calls = num(s.get("calls")), modelInput = num(s.get("modelInput")),
                    uncachedInput = num(s.get("uncachedInput")), modelOutput = num(s.get("modelOutput"));
                stats.put("userFacingChatCalls", calls);
                stats.put("totalModelInputTokens", modelInput);
                stats.put("totalUncachedInputTokens", uncachedInput);
                stats.put("totalModelOutputTokens", modelOutput);
                stats.put("totalThinkingTokens", num(s.get("thinking")));
                stats.put("totalCachedTokens", num(s.get("cached")));
                stats.put("totalUserFacingTokens", uncachedInput + modelOutput);
                stats.put("baselineEstimatedTokens", modelInput + calls * 120);
            } catch (Exception ignored) { /* loadSessionStats returns null on failure */ }
        }
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private void add(String key, long delta) {
        stats.merge(key, delta, Long::sum);
    }

    /** server.ts "Cumulative accounting" block for a non-mock chat turn, then saveLocalStats(). */
    public synchronized void recordChatTurn(int userPromptTokens, int inputTokens, int uncachedInputTokens,
                                            int outputTokens, Integer thinkingTokens, Integer cachedTokens,
                                            int totalTokens, long baselineEst) {
        add("userFacingChatCalls", 1);
        add("totalUserInputTokens", userPromptTokens);
        add("totalModelInputTokens", inputTokens);
        add("totalUncachedInputTokens", uncachedInputTokens);
        add("totalModelOutputTokens", outputTokens);
        if (thinkingTokens != null && thinkingTokens != 0) add("totalThinkingTokens", thinkingTokens);
        if (cachedTokens != null && cachedTokens != 0) add("totalCachedTokens", cachedTokens);
        add("totalUserFacingTokens", totalTokens);
        add("baselineEstimatedTokens", baselineEst);
        saveLocalStats();
    }

    /** A real (non-simulated) compaction: memory-assembly metrics or the post-response summariser. */
    public synchronized void recordCompaction(long inputTokens, long outputTokens, long totalTokens) {
        add("compactionCalls", 1);
        add("compactionInputTokens", inputTokens);
        add("compactionOutputTokens", outputTokens);
        add("compactionTotalTokens", totalTokens);
    }

    /** GET /api/tokens/session-stats response body. */
    public synchronized Map<String, Object> getSessionStats() {
        long trueTotal = stats.get("totalUserFacingTokens") + stats.get("compactionTotalTokens");
        long baseline = stats.get("baselineEstimatedTokens");
        long saved = Math.max(0, baseline - trueTotal);
        Number netSavingsPercent = baseline > 0 ? permille(saved / (double) baseline) : 0;
        long modelInput = stats.get("totalModelInputTokens");
        Number cacheHitRatio = modelInput > 0 ? permille(stats.get("totalCachedTokens") / (double) modelInput) : 0;
        long calls = stats.get("userFacingChatCalls");
        long totalUncached = stats.get("totalUncachedInputTokens") > 0 ? stats.get("totalUncachedInputTokens")
            : Math.max(0, modelInput - stats.get("totalCachedTokens"));

        Map<String, Object> out = new LinkedHashMap<>(stats);
        out.put("totalUncachedInputTokens", totalUncached);
        out.put("trueTotalConsumption", trueTotal);
        out.put("tokensSaved", saved);
        out.put("netSavingsPercentage", netSavingsPercent);
        out.put("cacheHitRatio", cacheHitRatio);
        out.put("averageTokensPerTurn", calls > 0 ? Math.round(stats.get("totalUserFacingTokens") / (double) calls) : 0);
        out.put("averageOutputPerTurn", calls > 0 ? Math.round(stats.get("totalModelOutputTokens") / (double) calls) : 0);
        out.put("averageThinkingPerTurn", calls > 0 ? Math.round(stats.get("totalThinkingTokens") / (double) calls) : 0);
        return out;
    }

    /** Math.round(ratio * 1000) / 10 as a JS number (whole values serialise without ".0"). */
    static Number permille(double ratio) {
        double r = Math.round(ratio * 1000) / 10.0;
        return r == Math.rint(r) ? (Number) (long) r : (Number) r;
    }

    /** POST /api/tokens/session-reset: zero every counter (the original does not rewrite the stats file here). */
    public synchronized void resetSession() {
        stats.replaceAll((k, v) -> 0L);
    }

    private void saveLocalStats() {
        try {
            Files.createDirectories(LOCAL_STATS_FILE.getParent());
            Files.writeString(LOCAL_STATS_FILE, JsJson.stringify(stats), StandardCharsets.UTF_8); // fs.writeFile(JSON.stringify(sessionStats))
        } catch (Exception ignored) { /* saveLocalStats swallows errors */ }
    }

    /** server.ts appendTokenLog(): one JSON line per call, cost computed when not supplied. */
    public void appendTokenLog(String endpoint, String model, long inputTokens, long outputTokens,
                               Long cachedTokens, Double estimatedCostUsd, String conversationId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", System.currentTimeMillis());
        entry.put("endpoint", endpoint);
        entry.put("model", model);
        entry.put("inputTokens", inputTokens);
        entry.put("outputTokens", outputTokens);
        if (cachedTokens != null) entry.put("cachedTokens", cachedTokens);
        if (estimatedCostUsd != null) entry.put("estimatedCostUsd", estimatedCostUsd);
        if (conversationId != null) entry.put("conversationId", conversationId);
        appendTokenLog(entry);
    }

    /**
     * appendTokenLog(entry) with the caller's entry literal (its key order is kept):
     * `{...entry, estimatedCostUsd: entry.estimatedCostUsd ?? calcTurnCost(...) ?? 0, datetime}` appended to
     * data/token-log.ndjson; errors swallowed.
     */
    public void appendTokenLog(Map<String, Object> entry) {
        long ts = ((Number) entry.get("ts")).longValue();
        long inputTokens = ((Number) entry.get("inputTokens")).longValue();
        long outputTokens = ((Number) entry.get("outputTokens")).longValue();
        long cached = entry.get("cachedTokens") instanceof Number n ? n.longValue() : 0;
        Object cost = entry.get("estimatedCostUsd");
        if (cost == null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("uncachedInputTokens", inputTokens - cached);
            usage.put("cachedTokens", cached);
            Double c = modelRegistry.calcTurnCost(String.valueOf(entry.get("model")), usage);
            cost = c != null ? c : 0.0;
        }
        Map<String, Object> line = new LinkedHashMap<>(entry);
        line.put("estimatedCostUsd", cost);
        line.put("datetime", ISO.format(Instant.ofEpochMilli(ts))); // toISOString() always has .SSS
        synchronized (TOKEN_LOG_FILE) {
            try {
                Files.createDirectories(TOKEN_LOG_FILE.getParent());
                Files.writeString(TOKEN_LOG_FILE, JsJson.stringify(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) { /* appendTokenLog swallows errors */ }
        }
    }

    /** estimateTokens() (JS trim and \s semantics). */
    public int estimate(String text) {
        return ConversationMemoryService.estimateTokens(text);
    }
}
