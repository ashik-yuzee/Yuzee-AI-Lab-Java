package com.yuzee.tokenlab.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** db.ts logTurn / loadLifetimeStats / loadDailyCost / loadSessionStats against conversation_logs, query for query. */
public class JdbcConversationLogService implements ConversationLogService {

    private final JdbcTemplate jdbc;

    public JdbcConversationLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** initDb() (whichever JDBC bean is built first runs it; see DbSchema). */
    @PostConstruct
    public void initSchema() {
        com.yuzee.tokenlab.repository.DbSchema.init(jdbc);
    }

    @Override
    public void logTurn(String ip, String conversationId, String messageId, String modelId,
                         Integer promptTokens, Integer uncachedInputTokens, Integer cachedTokens,
                         Integer outputTokens, Integer thinkingTokens, Double estimatedCostUsd, Integer latencyMs,
                         String finishReason, boolean isMock, boolean isWhiteboard,
                         String userInput, String assistantOutput, String errorCode) {
        try {
        jdbc.update("""
            INSERT INTO conversation_logs (
              ip, conversation_id, message_id, model,
              input_tokens, uncached_input_tokens, cached_tokens, output_tokens, thinking_tokens,
              estimated_cost_usd, latency_ms, finish_reason, is_mock, is_whiteboard,
              user_input, assistant_output, error_code
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT DO NOTHING
            """,
            ip, conversationId, messageId, modelId,
            promptTokens, uncachedInputTokens, cachedTokens, outputTokens, thinkingTokens,
            // node-postgres sends String(number); a numeric parameter keeps NUMERIC(12,8) rounding identical
            estimatedCostUsd != null ? new java.math.BigDecimal(JsJson.number(estimatedCostUsd)) : null,
            latencyMs, finishReason, isMock, isWhiteboard,
            truncate(userInput, 2000), truncate(assistantOutput, 4000), errorCode);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(JdbcConversationLogService.class).error("[db] logTurn failed:", e);
        }
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("calls", asLong(row.get("calls")));
        stats.put("inputTokens", asLong(row.get("input_tokens")));
        stats.put("outputTokens", asLong(row.get("output_tokens")));
        stats.put("cachedTokens", asLong(row.get("cached_tokens")));
        stats.put("thinkingTokens", asLong(row.get("thinking_tokens")));
        stats.put("costUsd", asDouble(row.get("cost_usd")));
        Map<String, Object> whiteboard = new LinkedHashMap<>();
        whiteboard.put("calls", asLong(row.get("wb_calls")));
        whiteboard.put("inputTokens", asLong(row.get("wb_input")));
        whiteboard.put("outputTokens", asLong(row.get("wb_output")));
        whiteboard.put("costUsd", asDouble(row.get("wb_cost")));
        stats.put("whiteboard", whiteboard);
        return stats;
    }

    @Override
    public double loadDailyCost() {
        Double total = jdbc.queryForObject("""
            SELECT COALESCE(SUM(estimated_cost_usd), 0)::float AS total
                   FROM conversation_logs WHERE logged_at >= CURRENT_DATE AND NOT is_mock""", Double.class);
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("calls", asLong(row.get("calls")));
        stats.put("modelInput", asLong(row.get("model_input")));
        stats.put("uncachedInput", asLong(row.get("uncached_input")));
        stats.put("modelOutput", asLong(row.get("model_output")));
        stats.put("thinking", asLong(row.get("thinking")));
        stats.put("cached", asLong(row.get("cached")));
        return stats;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return null; // db.ts: turn.userInput ? slice : null
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
