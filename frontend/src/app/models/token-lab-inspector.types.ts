/**
 * Shared shapes for the power-user inspector panels (token inspector, context inspector,
 * memory timeline). Kept separate from models/types.ts to avoid colliding with the parallel
 * work already touching that file.
 */

/** Turn-level token usage — matches ChatController's SSE `done` payload's `tokenUsage` field. */
export interface TurnTokenUsage {
  promptTokens: number;
  outputTokens: number;
  cachedTokens?: number;
  thinkingTokens?: number;
}

/** Matches backend model CompactionMetrics.java, sent as the SSE `done` payload's `compaction` field. */
export interface CompactionInfo {
  turnsKept: number;
  turnsDropped: number;
  tokensUsed: number;
  tokenBudget: number;
  strategy: string;
}
