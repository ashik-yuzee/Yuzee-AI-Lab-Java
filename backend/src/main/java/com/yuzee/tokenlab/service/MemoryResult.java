package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.CompactionMetrics;

import java.util.List;

/** Result of {@link ConversationMemoryService#assembleMemory}. */
public class MemoryResult {
    /** Messages to include, in chronological order (user/assistant pairs, oldest first). */
    public List<ChatMessage> retainedTurns;
    /** Nullable text summary of dropped turns. This port never synthesizes one (no fake summary prose). */
    public String compactedSummary;
    public CompactionMetrics metrics;

    public MemoryResult() {
    }

    public MemoryResult(List<ChatMessage> retainedTurns, String compactedSummary, CompactionMetrics metrics) {
        this.retainedTurns = retainedTurns;
        this.compactedSummary = compactedSummary;
        this.metrics = metrics;
    }
}
