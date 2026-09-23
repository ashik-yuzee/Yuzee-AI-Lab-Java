package com.yuzee.tokenlab.service;

import java.util.Map;

/**
 * Per-turn telemetry logging — one row per model call, kept forever (never pruned), used to
 * compute lifetime/daily/session cost & token stats. Port of the aggregate-producing half of the
 * old Node app's db.ts.
 * <p>
 * Two implementations wired in config.PersistenceConfig: JdbcConversationLogService (Postgres)
 * and FileConversationLogService (no DB: turn logs are dropped, as in the original).
 */
public interface ConversationLogService {

    void logTurn(String ip, String conversationId, String messageId, String modelId,
                 Integer promptTokens, Integer uncachedInputTokens, Integer cachedTokens,
                 Integer outputTokens, Integer thinkingTokens, Double estimatedCostUsd, Integer latencyMs,
                 String finishReason, boolean isMock, boolean isWhiteboard,
                 String userInput, String assistantOutput, String errorCode);

    /** Lifetime totals: calls, inputTokens, outputTokens, cachedTokens, thinkingTokens, costUsd, whiteboard{...}. */
    Map<String, Object> loadLifetimeStats();

    /** Total estimated cost logged today (server-local date), excluding mock calls. */
    double loadDailyCost();

    /** Session-scope totals: calls, modelInput, uncachedInput, modelOutput, thinking, cached. */
    Map<String, Object> loadSessionStats();
}
