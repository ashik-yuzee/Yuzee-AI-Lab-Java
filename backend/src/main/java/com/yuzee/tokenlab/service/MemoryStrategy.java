package com.yuzee.tokenlab.service;

/**
 * Ported from TokenBudgetMemoryManager.ts's `strategy` string parameter
 * ('BASELINE' | 'SEMANTIC_EVIDENCE' | default whole-turn budget eviction).
 */
public enum MemoryStrategy {
    /** Keep every historical turn, no eviction. */
    BASELINE,
    /** Keyword-overlap relevance scoring + always keep the last 3 turns. */
    SEMANTIC_EVIDENCE,
    /** Newest-first greedy whole-turn pack until the token budget is exhausted (the old app's default). */
    BUDGET_EVICTION
}
