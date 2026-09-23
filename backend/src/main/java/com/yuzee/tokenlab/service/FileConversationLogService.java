package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * ConversationLogService without a database: db.ts logTurn() returns immediately when there is no pool, and the
 * stats loaders return null (the endpoints then answer with their no-database fallbacks).
 */
public class FileConversationLogService implements ConversationLogService {

    public FileConversationLogService(ObjectMapper mapper) {
    }

    @Override
    public void logTurn(String ip, String conversationId, String messageId, String modelId,
                        Integer promptTokens, Integer uncachedInputTokens, Integer cachedTokens,
                        Integer outputTokens, Integer thinkingTokens, Double estimatedCostUsd, Integer latencyMs,
                        String finishReason, boolean isMock, boolean isWhiteboard,
                        String userInput, String assistantOutput, String errorCode) {
        // if (!pool) return;
    }

    @Override
    public Map<String, Object> loadLifetimeStats() { return null; }

    @Override
    public double loadDailyCost() { return 0; }

    @Override
    public Map<String, Object> loadSessionStats() { return null; }
}
