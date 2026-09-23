package com.yuzee.tokenlab.model;

/** Exact port of TokenBudgetMemoryManager.ts CompactionMetrics (simulated compaction model). */
public class CompactionMetrics {
    public String compactionEventId;
    public String sourceTurnsRange;
    public int sourceTokens;
    public int summaryTokens;
    public int tokensRemoved;
    public int compactionInputTokens;
    public int compactionOutputTokens;
    public int compactionTotalCost;
    public int estimatedNetSavingsPerTurn;
    /** Math.round(x * 10) / 10 in the original; a Long when whole so JSON prints 2 not 2.0, like JS. */
    public Number estimatedBreakEvenTurns;
    public long timestamp;
    public boolean isSimulated;
}
