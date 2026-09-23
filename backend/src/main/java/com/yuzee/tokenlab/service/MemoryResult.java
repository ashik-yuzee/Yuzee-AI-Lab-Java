package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.CompactionMetrics;
import com.yuzee.tokenlab.model.DialogueTurn;

import java.util.List;
import java.util.Map;

/** Port of TokenBudgetMemoryManager.ts MemoryAssemblyResult (same JSON keys). */
public class MemoryResult {
    public String summaryText;
    public List<DialogueTurn> keptTurns;
    public String recentHistoryText;
    public int recentTurnsCount;
    public int removedTokens;
    public CompactionMetrics compactionMetrics;
    /** ExcludedItem {name, reason, tokens, preview}. */
    public List<Map<String, Object>> excludedItems;

    public MemoryResult() {
    }

    public MemoryResult(String summaryText, List<DialogueTurn> keptTurns, String recentHistoryText, int recentTurnsCount,
                        int removedTokens, CompactionMetrics compactionMetrics, List<Map<String, Object>> excludedItems) {
        this.summaryText = summaryText;
        this.keptTurns = keptTurns;
        this.recentHistoryText = recentHistoryText;
        this.recentTurnsCount = recentTurnsCount;
        this.removedTokens = removedTokens;
        this.compactionMetrics = compactionMetrics;
        this.excludedItems = excludedItems;
    }
}
