package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary of a memory-assembly pass, exposed to the client as part of an SSE 'compaction' event.
 * Simplified deliberately from the old Node app's CompactionMetrics (which tracked simulated
 * summarization cost/ROI) since this port does not run a real summarization step.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactionMetrics {
    private int turnsKept;
    private int turnsDropped;
    private int tokensUsed;
    private int tokenBudget;
    private String strategy;

    public CompactionMetrics() {
    }

    public CompactionMetrics(int turnsKept, int turnsDropped, int tokensUsed, int tokenBudget, String strategy) {
        this.turnsKept = turnsKept;
        this.turnsDropped = turnsDropped;
        this.tokensUsed = tokensUsed;
        this.tokenBudget = tokenBudget;
        this.strategy = strategy;
    }

    public int getTurnsKept() { return turnsKept; }
    public void setTurnsKept(int turnsKept) { this.turnsKept = turnsKept; }
    public int getTurnsDropped() { return turnsDropped; }
    public void setTurnsDropped(int turnsDropped) { this.turnsDropped = turnsDropped; }
    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
    public int getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(int tokenBudget) { this.tokenBudget = tokenBudget; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
}
