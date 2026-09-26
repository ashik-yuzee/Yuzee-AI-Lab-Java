package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.service.JsJson;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * db.ts with a pool, query for query: the same SQL (placeholders `?` instead of `$n`), the same parameter values
 * and the same JSON-as-text serialisation (JSON.stringify text sent untyped, as node-postgres does, so PostgreSQL
 * casts it to JSONB), the same row mapping, and the same error handling (logged, never thrown). Only the columns
 * db.ts creates are written or read.
 */
public class JdbcConversationRepository implements ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcConversationRepository.class);
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;

    static final String SAVE_CONVERSATION_SQL = """
        INSERT INTO conversations (
                id, title, created_at, updated_at, expires_at,
                model, mode, strategy, preset, response_mode, thinking_level,
                context_budget, recent_turns_to_keep,
                summary, summary_version,
                system_prompt_mode, custom_system_prompt,
                use_interactions_api, use_flash_lite_utility,
                career_context, compaction_history, active_interaction
              ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
              ON CONFLICT (id) DO UPDATE SET
                title                 = EXCLUDED.title,
                updated_at            = EXCLUDED.updated_at,
                expires_at            = NOW() + INTERVAL '30 days',
                model                 = EXCLUDED.model,
                mode                  = EXCLUDED.mode,
                strategy              = EXCLUDED.strategy,
                preset                = EXCLUDED.preset,
                response_mode         = EXCLUDED.response_mode,
                thinking_level        = EXCLUDED.thinking_level,
                context_budget        = EXCLUDED.context_budget,
                recent_turns_to_keep  = EXCLUDED.recent_turns_to_keep,
                summary               = EXCLUDED.summary,
                summary_version       = EXCLUDED.summary_version,
                system_prompt_mode    = EXCLUDED.system_prompt_mode,
                custom_system_prompt  = EXCLUDED.custom_system_prompt,
                use_interactions_api  = EXCLUDED.use_interactions_api,
                use_flash_lite_utility = EXCLUDED.use_flash_lite_utility,
                career_context        = EXCLUDED.career_context,
                compaction_history    = EXCLUDED.compaction_history,
                active_interaction    = EXCLUDED.active_interaction""";

    static final String SAVE_MESSAGE_SQL = """
        INSERT INTO messages (id, conversation_id, role, content, structured_response, user_event, telemetry, feedback, created_at)
               VALUES (?,?,?,?,?,?,?,?,?)
               ON CONFLICT (id) DO UPDATE SET
                 content             = EXCLUDED.content,
                 structured_response = EXCLUDED.structured_response,
                 telemetry           = EXCLUDED.telemetry,
                 feedback            = EXCLUDED.feedback""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcConversationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = new ObjectMapper();
    }

    /** initDb(). */
    @PostConstruct
    public void initSchema() {
        DbSchema.init(jdbc);
    }

    @Override
    public boolean isDbEnabled() { return true; }

    /** A JSON.stringify string sent untyped (node-postgres sends every parameter as untyped text). */
    private static SqlParameterValue json(Object value) {
        return new SqlParameterValue(Types.OTHER, JsJson.stringify(value));
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    @Override
    public void saveConversation(Conversation conv) {
        long now = System.currentTimeMillis();
        try {
            jdbc.update(SAVE_CONVERSATION_SQL,
                conv.getId(),
                or(conv.getTitle(), ""),
                new Timestamp(conv.getCreatedAt() != null && conv.getCreatedAt().toEpochMilli() != 0 ? conv.getCreatedAt().toEpochMilli() : now),
                new Timestamp(conv.getUpdatedAt() != null && conv.getUpdatedAt().toEpochMilli() != 0 ? conv.getUpdatedAt().toEpochMilli() : now),
                new Timestamp(now + THIRTY_DAYS_MS),
                or(conv.getModelId(), "gemini-3.5-flash"),
                or(conv.getOptimizationMode(), "AUTO"),
                or(conv.getStrategy(), "ADAPTIVE_HYBRID"),
                or(conv.getPreset(), "BALANCED"),
                or(conv.getResponseMode(), "standard"),
                or(conv.getThinkingLevel(), "adaptive"),
                or(conv.getContextBudget(), 270000),
                or(conv.getRecentTurnsToKeep(), 100),
                or(conv.getSummaryText(), ""),
                or(conv.getSummaryVersion(), 0),
                or(conv.getSystemPromptMode(), "default"),
                or(conv.getCustomSystemPrompt(), ""),
                or(conv.getUseInteractionsApi(), false),
                or(conv.getUseFlashLiteUtility(), true),
                json(conv.getCareerContext() != null ? conv.getCareerContext() : Map.of()),
                json(conv.getCompactionHistory() != null ? conv.getCompactionHistory() : List.of()),
                conv.getActiveInteraction() != null ? json(conv.getActiveInteraction()) : new SqlParameterValue(Types.OTHER, null));
        } catch (Exception e) {
            log.error("[db] saveConversation failed:", e);
        }
    }

    @Override
    public void saveMessage(ChatMessage message, String conversationId) {
        Map<String, Object> msg = message.toClientJson();
        long created = msg.get("createdAt") instanceof Number n && n.longValue() != 0 ? n.longValue() : System.currentTimeMillis();
        try {
            jdbc.update(SAVE_MESSAGE_SQL,
                msg.get("id"),
                conversationId,
                msg.get("role"),
                msg.get("content"),
                jsonOrNull(msg.get("structuredResponse")),
                jsonOrNull(msg.get("userEvent")),
                jsonOrNull(msg.get("telemetry")),
                jsonOrNull(msg.get("feedback")),
                new Timestamp(created));
        } catch (Exception e) {
            log.error("[db] saveMessage failed:", e);
        }
    }

    private static SqlParameterValue jsonOrNull(Object value) {
        return value != null ? json(value) : new SqlParameterValue(Types.OTHER, null);
    }

    @Override
    public void deleteConversation(String id) {
        try {
            jdbc.update("DELETE FROM conversations WHERE id = ?", id);
        } catch (Exception e) {
            log.error("[db] deleteConversation failed:", e);
        }
    }

    @Override
    public void pruneExpired() {
        try {
            int r1 = jdbc.update("DELETE FROM conversations WHERE expires_at < NOW()");
            if (r1 != 0) log.info("[db] Pruned {} expired conversations", r1);
            int r2 = jdbc.update("DELETE FROM conversation_logs WHERE logged_at < NOW() - INTERVAL '90 days'");
            if (r2 != 0) log.info("[db] Pruned {} old token log rows (>90 days)", r2);
        } catch (Exception e) {
            log.error("[db] Prune failed:", e);
        }
    }

    @Override
    public void keepAlive() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            log.error("[db] keepAlive failed:", e);
        }
    }

    @Override
    public List<Conversation> loadConversations() {
        try {
            List<Map<String, Object>> convRows = jdbc.query(
                "SELECT * FROM conversations WHERE expires_at > NOW() ORDER BY updated_at DESC LIMIT 500", this::row);
            if (convRows.isEmpty()) return new ArrayList<>();

            String[] ids = convRows.stream().map(r -> (String) r.get("id")).toArray(String[]::new);
            List<Map<String, Object>> msgRows = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement("SELECT * FROM messages WHERE conversation_id = ANY(?) ORDER BY created_at ASC");
                ps.setArray(1, con.createArrayOf("text", ids));
                return ps;
            }, this::row);

            Map<String, List<Map<String, Object>>> msgsByConv = new LinkedHashMap<>();
            for (Map<String, Object> m : msgRows) msgsByConv.computeIfAbsent((String) m.get("conversation_id"), k -> new ArrayList<>()).add(m);

            List<Conversation> out = new ArrayList<>();
            for (Map<String, Object> r : convRows) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", r.get("id"));
                c.put("title", r.get("title"));
                c.put("createdAt", millis(r.get("created_at")));
                c.put("updatedAt", millis(r.get("updated_at")));
                c.put("model", r.get("model"));
                c.put("mode", r.get("mode"));
                c.put("strategy", r.get("strategy"));
                c.put("preset", r.get("preset"));
                c.put("responseMode", r.get("response_mode"));
                c.put("thinkingLevel", r.get("thinking_level"));
                c.put("contextBudget", r.get("context_budget"));
                c.put("recentTurnsToKeep", r.get("recent_turns_to_keep"));
                c.put("summary", truthy(r.get("summary")) ? r.get("summary") : "");
                c.put("summaryVersion", truthy(r.get("summary_version")) ? r.get("summary_version") : 0);
                c.put("systemPromptMode", r.get("system_prompt_mode"));
                c.put("customSystemPrompt", truthy(r.get("custom_system_prompt")) ? r.get("custom_system_prompt") : "");
                c.put("useInteractionsApi", truthy(r.get("use_interactions_api")) ? r.get("use_interactions_api") : false);
                c.put("useFlashLiteUtility", r.get("use_flash_lite_utility") != null ? r.get("use_flash_lite_utility") : true);
                c.put("careerContext", truthy(r.get("career_context")) ? r.get("career_context") : new LinkedHashMap<>());
                c.put("compactionHistory", truthy(r.get("compaction_history")) ? r.get("compaction_history") : new ArrayList<>());
                c.put("activeInteraction", truthy(r.get("active_interaction")) ? r.get("active_interaction") : null);
                List<Map<String, Object>> messages = new ArrayList<>();
                for (Map<String, Object> m : msgsByConv.getOrDefault((String) r.get("id"), List.of())) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("id", m.get("id"));
                    msg.put("role", m.get("role"));
                    msg.put("content", m.get("content"));
                    msg.put("structuredResponse", m.get("structured_response"));
                    msg.put("userEvent", m.get("user_event"));
                    msg.put("telemetry", m.get("telemetry"));
                    msg.put("feedback", m.get("feedback"));
                    msg.put("createdAt", millis(m.get("created_at")));
                    msg.values().removeIf(java.util.Objects::isNull); // `?? undefined`
                    messages.add(msg);
                }
                c.put("messages", messages);
                Conversation conv = Conversation.fromClientJson(c);
                conv.useKeyOrder(Conversation.DB_ORDER);
                for (ChatMessage m : conv.getMessages()) {
                    m.useKeyOrder(ChatMessage.DB_ORDER);
                    m.setSaved(true);
                }
                out.add(conv);
            }
            return out;
        } catch (Exception e) {
            log.error("[db] loadConversations failed:", e);
            return new ArrayList<>();
        }
    }

    /** node-postgres row: JSONB parsed, TIMESTAMPTZ as a Date (epoch ms here), other columns as-is. */
    private Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            String type = meta.getColumnTypeName(i);
            Object value;
            if ("jsonb".equalsIgnoreCase(type) || "json".equalsIgnoreCase(type)) {
                String text = rs.getString(i);
                try {
                    value = text == null ? null : mapper.readValue(text, Object.class);
                } catch (Exception e) {
                    throw new SQLException("Unreadable JSON in " + name, e);
                }
            } else if ("timestamptz".equalsIgnoreCase(type) || "timestamp".equalsIgnoreCase(type)) {
                Timestamp t = rs.getTimestamp(i);
                value = t != null ? t.getTime() : null;
            } else {
                value = rs.getObject(i);
            }
            out.put(name, value);
        }
        return out;
    }

    private static Long millis(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** JavaScript truthiness for the `||` defaults of loadConversations(). */
    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    /** Same chat-bubble label the old UI synthesises for a structured answer (TokenLabContext.sendMessage). */
    public static String userEventLabel(Map<?, ?> ev) {
        if (ev.get("value") instanceof String v && !v.isBlank()) return v;
        Object nested = ev.get("userEvent") instanceof Map<?, ?> ue ? ue.get("interaction") : ev.get("interaction");
        if (nested instanceof Map<?, ?> inter) {
            if (inter.get("self_input") instanceof String si && !si.isBlank()) return si;
            if (inter.get("selected_option_ids") instanceof List<?> sel && !sel.isEmpty()) {
                return "Selected option: " + String.join(", ", sel.stream().map(String::valueOf).toList());
            }
            if (inter.get("ranked_option_ids") instanceof List<?> ranked && !ranked.isEmpty()) {
                return String.join(" → ", ranked.stream().map(String::valueOf).toList());
            }
            if (inter.get("fields") instanceof Map<?, ?> fields) {
                List<String> parts = new ArrayList<>();
                fields.forEach((k, v) -> parts.add(k + ": " + v));
                return "Submitted details: " + String.join(", ", parts);
            }
            return "Submitted response";
        }
        if (ev.get("message") instanceof String msg && !msg.isBlank()) return msg;
        return "Submitted interaction";
    }
}
