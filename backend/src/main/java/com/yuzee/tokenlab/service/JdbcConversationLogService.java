package com.yuzee.tokenlab.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Postgres-backed ConversationLogService. Mirrors the conversation_logs table from the old db.ts. */
public class JdbcConversationLogService implements ConversationLogService {

    private final JdbcTemplate jdbc;

    public JdbcConversationLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        // See JdbcConversationRepository.initSchema for why every column — not just ones added
        // after the fact — goes through ADD COLUMN IF NOT EXISTS: on a database where this table
        // already exists from a prior/other deployment, CREATE TABLE IF NOT EXISTS is a no-op.
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation_logs (id BIGSERIAL PRIMARY KEY)");
        for (String ddl : List.of(
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS logged_at TIMESTAMPTZ NOT NULL DEFAULT NOW()",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS ip TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS conversation_id TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS message_id TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS model TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS input_tokens INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS uncached_input_tokens INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS cached_tokens INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS output_tokens INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS thinking_tokens INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS estimated_cost_usd NUMERIC(12,8)",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS latency_ms INT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS finish_reason TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS is_mock BOOLEAN NOT NULL DEFAULT FALSE",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS is_whiteboard BOOLEAN NOT NULL DEFAULT FALSE",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS user_input TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS assistant_output TEXT",
                "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS error_code TEXT",
                "CREATE INDEX IF NOT EXISTS idx_convlog_ip ON conversation_logs (ip, logged_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_convlog_conv ON conversation_logs (conversation_id)")) {
            jdbc.execute(ddl);
        }
    }

    @Override
    public void logTurn(String ip, String conversationId, String messageId, String modelId,
                         Integer promptTokens, Integer uncachedInputTokens, Integer cachedTokens,
                         Integer outputTokens, Integer thinkingTokens, Double estimatedCostUsd, Integer latencyMs,
                         String finishReason, boolean isMock, boolean isWhiteboard,
                         String userInput, String assistantOutput, String errorCode) {
        jdbc.update("""
            INSERT INTO conversation_logs (
              ip, conversation_id, message_id, model,
              input_tokens, uncached_input_tokens, cached_tokens, output_tokens, thinking_tokens,
              estimated_cost_usd, latency_ms, finish_reason, is_mock, is_whiteboard,
              user_input, assistant_output, error_code
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            ip, conversationId, messageId, modelId,
            promptTokens, uncachedInputTokens, cachedTokens, outputTokens, thinkingTokens,
            estimatedCostUsd, latencyMs, finishReason, isMock, isWhiteboard,
            truncate(userInput, 2000), truncate(assistantOutput, 4000), errorCode);
    }

    @Override
    public Map<String, Object> loadLifetimeStats() {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT
              COUNT(*)          FILTER (WHERE NOT is_mock AND NOT is_whiteboard)                    AS calls,
              COALESCE(SUM(input_tokens)   FILTER (WHERE NOT is_mock AND NOT is_whiteboard), 0)    AS input_tokens,
              COALESCE(SUM(output_tokens)  FILTER (WHERE NOT is_mock AND NOT is_whiteboard), 0)    AS output_tokens,
              COALESCE(SUM(cached_tokens)  FILTER (WHERE NOT is_mock AND NOT is_whiteboard), 0)    AS cached_tokens,
              COALESCE(SUM(thinking_tokens)FILTER (WHERE NOT is_mock AND NOT is_whiteboard), 0)    AS thinking_tokens,
              COALESCE(SUM(estimated_cost_usd) FILTER (WHERE NOT is_mock AND NOT is_whiteboard), 0) AS cost_usd,
              COUNT(*)          FILTER (WHERE is_whiteboard AND NOT is_mock)                        AS wb_calls,
              COALESCE(SUM(input_tokens)   FILTER (WHERE is_whiteboard AND NOT is_mock), 0)        AS wb_input,
              COALESCE(SUM(output_tokens)  FILTER (WHERE is_whiteboard AND NOT is_mock), 0)        AS wb_output,
              COALESCE(SUM(estimated_cost_usd) FILTER (WHERE is_whiteboard AND NOT is_mock), 0)    AS wb_cost
            FROM conversation_logs
            """);
        Map<String, Object> stats = new HashMap<>();
        stats.put("calls", asLong(row.get("calls")));
        stats.put("inputTokens", asLong(row.get("input_tokens")));
        stats.put("outputTokens", asLong(row.get("output_tokens")));
        stats.put("cachedTokens", asLong(row.get("cached_tokens")));
        stats.put("thinkingTokens", asLong(row.get("thinking_tokens")));
        stats.put("costUsd", asDouble(row.get("cost_usd")));
        stats.put("whiteboard", Map.of(
            "calls", asLong(row.get("wb_calls")),
            "inputTokens", asLong(row.get("wb_input")),
            "outputTokens", asLong(row.get("wb_output")),
            "costUsd", asDouble(row.get("wb_cost"))
        ));
        return stats;
    }

    @Override
    public double loadDailyCost() {
        Double total = jdbc.queryForObject(
            "SELECT COALESCE(SUM(estimated_cost_usd), 0)::float8 FROM conversation_logs " +
            "WHERE logged_at >= ? AND NOT is_mock",
            Double.class, java.sql.Date.valueOf(LocalDate.now()));
        return total != null ? total : 0.0;
    }

    @Override
    public Map<String, Object> loadSessionStats() {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT
              COUNT(*)                                                          AS calls,
              COALESCE(SUM(input_tokens)          FILTER (WHERE NOT is_mock), 0) AS model_input,
              COALESCE(SUM(uncached_input_tokens) FILTER (WHERE NOT is_mock), 0) AS uncached_input,
              COALESCE(SUM(output_tokens)         FILTER (WHERE NOT is_mock), 0) AS model_output,
              COALESCE(SUM(thinking_tokens)       FILTER (WHERE NOT is_mock), 0) AS thinking,
              COALESCE(SUM(cached_tokens)         FILTER (WHERE NOT is_mock), 0) AS cached
            FROM conversation_logs
            """);
        Map<String, Object> stats = new HashMap<>();
        stats.put("calls", asLong(row.get("calls")));
        stats.put("modelInput", asLong(row.get("model_input")));
        stats.put("uncachedInput", asLong(row.get("uncached_input")));
        stats.put("modelOutput", asLong(row.get("model_output")));
        stats.put("thinking", asLong(row.get("thinking")));
        stats.put("cached", asLong(row.get("cached")));
        return stats;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
