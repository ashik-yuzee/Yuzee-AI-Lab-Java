package com.yuzee.tokenlab.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TokenService {

    private final GeminiModelRegistry modelRegistry;

    private final AtomicLong sessionPromptTokens = new AtomicLong(0);
    private final AtomicLong sessionOutputTokens = new AtomicLong(0);
    private final AtomicLong sessionTurns = new AtomicLong(0);
    // ponytail: global session cost accumulator — per-model rates mean cost is no longer
    // derivable from raw token totals alone, so it's tracked alongside them. Switch to
    // per-conversation tracking if multi-tenant session isolation is ever needed.
    private final AtomicLong sessionCostMicros = new AtomicLong(0); // USD * 1_000_000, for lock-free accumulation

    public TokenService(GeminiModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /** Backward-compatible overload: prices using the default (flash) model, as before. */
    public void recordTurn(int promptTokens, int outputTokens) {
        recordTurn(GeminiModelRegistry.DEFAULT_MODEL_ID, promptTokens, outputTokens);
    }

    /** Prices the turn using modelId's real per-model rates instead of a flat flash rate. */
    public void recordTurn(String modelId, int promptTokens, int outputTokens) {
        double cost = modelRegistry.calcTurnCost(modelId, promptTokens, outputTokens, 0);
        sessionPromptTokens.addAndGet(promptTokens);
        sessionOutputTokens.addAndGet(outputTokens);
        sessionTurns.incrementAndGet();
        sessionCostMicros.addAndGet(Math.round(cost * 1_000_000.0));
    }

    public Map<String, Object> getSessionStats() {
        long pt = sessionPromptTokens.get();
        long ot = sessionOutputTokens.get();
        double cost = sessionCostMicros.get() / 1_000_000.0;
        return Map.of(
            "promptTokens", pt,
            "outputTokens", ot,
            "totalTokens", pt + ot,
            "turns", sessionTurns.get(),
            "estimatedCostUsd", Math.round(cost * 10000.0) / 10000.0
        );
    }

    public void resetSession() {
        sessionPromptTokens.set(0);
        sessionOutputTokens.set(0);
        sessionTurns.set(0);
        sessionCostMicros.set(0);
    }

    /** Rough estimate: 1 token ≈ 4 characters */
    public int estimate(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 4);
    }
}
