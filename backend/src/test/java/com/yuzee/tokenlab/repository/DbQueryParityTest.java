package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.service.JdbcConversationLogService;
import com.yuzee.tokenlab.service.JsJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replays src/test/parity/db.parity.mts (the original db.ts against a recording node-postgres stub) through
 * JdbcConversationRepository and JdbcConversationLogService with a recording JdbcTemplate: the same statements in the
 * same order (SQL compared with `$n` written as `?` and whitespace collapsed) with the same parameter values
 * (JSON text, NULLs, defaults, truncation, timestamps; only the rolling expires_at is masked).
 */
class DbQueryParityTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Records what node-postgres would have been sent. */
    static class RecordingJdbc extends JdbcTemplate {
        final List<Map<String, Object>> log = new ArrayList<>();

        private void record(String sql, Object... args) {
            List<Object> params = new ArrayList<>();
            for (Object a : args) {
                Object v = a instanceof SqlParameterValue p ? p.getValue() : a;
                if (v instanceof Timestamp t) v = Map.of("date", ISO.format(Instant.ofEpochMilli(t.getTime())));
                if (v instanceof BigDecimal d) v = d.doubleValue();
                params.add(v);
            }
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("sql", sql);
            q.put("params", params);
            log.add(q);
        }

        @Override public void execute(String sql) { record(sql); }
        @Override public int update(String sql, Object... args) { record(sql, args); return 0; }
        @Override public int update(String sql) { record(sql); return 0; }
        @Override public Map<String, Object> queryForMap(String sql) { record(sql); return Map.of(); }
        @Override @SuppressWarnings("unchecked") public <T> T queryForObject(String sql, Class<T> requiredType) { record(sql); return (T) Double.valueOf(0); }
        @Override public Map<String, Object> queryForMap(String sql, Object... args) { record(sql, args); return Map.of(); }
        @Override @SuppressWarnings("unchecked") public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) { record(sql, args); return (T) Double.valueOf(0); }
    }

    private static String normalise(JsonNode query) {
        String sql = query.get("sql").asText().replaceAll("\\$\\d+", "?").replaceAll("\\s+", " ").trim();
        StringBuilder params = new StringBuilder();
        Instant soon = Instant.now().plus(1, ChronoUnit.DAYS);
        for (JsonNode p : query.get("params")) {
            boolean rolling = p.has("date") && Instant.parse(p.get("date").asText()).isAfter(soon); // NOW() + 30 days
            params.append(rolling ? "EXPIRES_AT" : JsJson.stringify(p)).append(" | ");
        }
        return sql + "\n  " + params;
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesMatchDbTs() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcConversationRepository repo = new JdbcConversationRepository(jdbc, null);
        JdbcConversationLogService logs = new JdbcConversationLogService(jdbc);
        repo.initSchema();
        logs.initSchema(); // initDb() runs once

        JsonNode files = M.readTree(getClass().getResourceAsStream("/parity/persistence/files.json")).get("files");
        List<Map<String, Object>> convs = M.readValue(files.get("data/conversations.json").asText(), List.class);
        for (Map<String, Object> raw : convs) {
            Conversation conv = Conversation.fromClientJson(raw);
            repo.saveConversation(conv);
            for (ChatMessage m : conv.getMessages()) repo.saveMessage(m, conv.getId());
        }
        for (JsonNode t : M.readTree(getClass().getResourceAsStream("/parity/persistence/db-turns.json"))) {
            String out = t.has("assistantOutput") ? t.get("assistantOutput").asText() : null;
            if ("LONG".equals(out)) out = "x".repeat(4100);
            logs.logTurn(t.get("ip").asText(), t.get("conversationId").asText(), t.get("messageId").asText(), text(t, "model"),
                integer(t, "inputTokens"), integer(t, "uncachedInputTokens"), integer(t, "cachedTokens"), integer(t, "outputTokens"),
                integer(t, "thinkingTokens"), t.hasNonNull("estimatedCostUsd") ? t.get("estimatedCostUsd").asDouble() : null, integer(t, "latencyMs"),
                text(t, "finishReason"), t.path("isMock").asBoolean(false), t.path("isWhiteboard").asBoolean(false),
                text(t, "userInput"), out, text(t, "errorCode"));
        }
        repo.deleteConversation("conv-gone");
        repo.pruneExpired();
        logs.loadLifetimeStats();
        logs.loadDailyCost();
        logs.loadSessionStats();

        JsonNode want = M.readTree(getClass().getResourceAsStream("/parity/persistence/db-queries.json"));
        JsonNode got = M.valueToTree(jdbc.log);
        List<String> w = new ArrayList<>(), g = new ArrayList<>();
        want.forEach(q -> w.add(normalise(q)));
        got.forEach(q -> g.add(normalise(q)));
        for (int i = 0; i < Math.min(w.size(), g.size()); i++) assertEquals(w.get(i), g.get(i), "query " + i);
        assertEquals(w.size(), g.size(), "query count");
    }

    private static String text(JsonNode t, String k) { return t.hasNonNull(k) ? t.get(k).asText() : null; }

    private static Integer integer(JsonNode t, String k) { return t.hasNonNull(k) ? t.get(k).asInt() : null; }
}
