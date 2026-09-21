package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Postgres-backed ConversationRepository. Mirrors the schema and upsert behavior of the old
 * Node app's {@code db.ts}: unconditional {@code CREATE TABLE IF NOT EXISTS} / {@code ADD
 * COLUMN IF NOT EXISTS} DDL on every boot (no migration framework), and a rolling 30-day TTL
 * recomputed on every save.
 * <p>
 * Nested/flexible fields (career context, profile facts, pathway/detail/objective lists, message
 * content and its parsed/telemetry maps) are stored as plain JSON text columns, serialized
 * through the existing Conversation/ChatMessage POJOs via Jackson.
 */
public class JdbcConversationRepository implements ConversationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcConversationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void initSchema() {
        // CREATE TABLE IF NOT EXISTS only bootstraps a table that doesn't exist at all yet — on
        // a database where `conversations`/`messages` already exists from a prior/other
        // deployment (e.g. the old Node app, sharing the same DATABASE_URL), it is a total no-op
        // and does NOT add columns. So every column this app needs — not just ones added after
        // the fact — goes through an idempotent ADD COLUMN IF NOT EXISTS below, matching the old
        // app's own db.ts approach exactly (unconditional additive migration on every boot, no
        // migration framework).
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS messages (
              id TEXT PRIMARY KEY,
              conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE
            )""");
        for (String ddl : List.of(
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS model_id TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '30 days')",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS optimization_mode TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS response_mode TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS strategy TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS career_context TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS summary_text TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS profile_facts TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS mini_pathways TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS details TEXT",
                "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS objectives TEXT",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS role TEXT",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS content TEXT",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS parsed_response TEXT",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS token_usage TEXT",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS stream_stopped BOOLEAN",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS validation_failed BOOLEAN",
                "ALTER TABLE messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()",
                "CREATE INDEX IF NOT EXISTS idx_conv_updated ON conversations (updated_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_conv_expires ON conversations (expires_at)",
                "CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages (conversation_id, created_at ASC)")) {
            jdbc.execute(ddl);
        }
    }

    @Override
    public List<Conversation> listAll() {
        List<Conversation> conversations = jdbc.query(
            "SELECT * FROM conversations WHERE expires_at > NOW() ORDER BY updated_at DESC LIMIT 500",
            this::mapConversation);
        for (Conversation c : conversations) {
            c.setMessages(loadMessages(c.getId()));
        }
        return conversations;
    }

    @Override
    public Optional<Conversation> findById(String id) {
        List<Conversation> rows = jdbc.query("SELECT * FROM conversations WHERE id = ?", this::mapConversation, id);
        if (rows.isEmpty()) return Optional.empty();
        Conversation c = rows.get(0);
        c.setMessages(loadMessages(id));
        return Optional.of(c);
    }

    @Override
    public Conversation save(Conversation conv) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO conversations (
              id, title, model_id, created_at, updated_at, expires_at,
              optimization_mode, response_mode, strategy, career_context, summary_text,
              profile_facts, mini_pathways, details, objectives
            ) VALUES (?,?,?,?,?, NOW() + INTERVAL '30 days', ?,?,?,?,?,?,?,?,?)
            ON CONFLICT (id) DO UPDATE SET
              title              = EXCLUDED.title,
              model_id           = EXCLUDED.model_id,
              updated_at         = EXCLUDED.updated_at,
              expires_at         = NOW() + INTERVAL '30 days',
              optimization_mode  = EXCLUDED.optimization_mode,
              response_mode      = EXCLUDED.response_mode,
              strategy           = EXCLUDED.strategy,
              career_context     = EXCLUDED.career_context,
              summary_text       = EXCLUDED.summary_text,
              profile_facts      = EXCLUDED.profile_facts,
              mini_pathways      = EXCLUDED.mini_pathways,
              details            = EXCLUDED.details,
              objectives         = EXCLUDED.objectives
            """,
            conv.getId(), conv.getTitle(), conv.getModelId(),
            Timestamp.from(conv.getCreatedAt() != null ? conv.getCreatedAt() : now),
            Timestamp.from(conv.getUpdatedAt() != null ? conv.getUpdatedAt() : now),
            conv.getOptimizationMode(), conv.getResponseMode(), conv.getStrategy(),
            toJson(conv.getCareerContext()), conv.getSummaryText(),
            toJson(conv.getProfileFacts()), toJson(conv.getMiniPathways()),
            toJson(conv.getDetails()), toJson(conv.getObjectives())
        );

        // Replace-all is simpler and safer than diffing (messages are only ever appended in
        // this app), matching the old app's fire-and-forget per-call persistence.
        jdbc.update("DELETE FROM messages WHERE conversation_id = ?", conv.getId());
        for (ChatMessage m : conv.getMessages()) {
            jdbc.update("""
                INSERT INTO messages (id, conversation_id, role, content, parsed_response, token_usage,
                                       stream_stopped, validation_failed, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                m.getId(), conv.getId(), m.getRole(), toJson(m.getContent()),
                toJson(m.getParsedResponse()), toJson(m.getTokenUsage()),
                m.getStreamStopped(), m.getValidationFailed(),
                Timestamp.from(m.getTimestamp() != null ? m.getTimestamp() : now));
        }
        return conv;
    }

    @Override
    public boolean delete(String id) {
        return jdbc.update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }

    @Override
    public int pruneExpired() {
        return jdbc.update("DELETE FROM conversations WHERE expires_at < NOW()");
    }

    private List<ChatMessage> loadMessages(String conversationId) {
        return jdbc.query("SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC",
            this::mapMessage, conversationId);
    }

    private Conversation mapConversation(ResultSet rs, int rowNum) throws SQLException {
        Conversation c = new Conversation();
        c.setId(rs.getString("id"));
        c.setTitle(rs.getString("title"));
        c.setModelId(rs.getString("model_id"));
        c.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        c.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        c.setOptimizationMode(rs.getString("optimization_mode"));
        c.setResponseMode(rs.getString("response_mode"));
        c.setStrategy(rs.getString("strategy"));
        c.setCareerContext(fromJsonMap(rs.getString("career_context")));
        c.setSummaryText(rs.getString("summary_text"));
        c.setProfileFacts(fromJsonList(rs.getString("profile_facts")));
        c.setMiniPathways(fromJsonList(rs.getString("mini_pathways")));
        c.setDetails(fromJsonList(rs.getString("details")));
        c.setObjectives(fromJsonList(rs.getString("objectives")));
        return c;
    }

    private ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        ChatMessage m = new ChatMessage();
        m.setId(rs.getString("id"));
        m.setRole(rs.getString("role"));
        m.setContent(fromJsonAny(rs.getString("content")));
        m.setParsedResponse(fromJsonMap(rs.getString("parsed_response")));
        m.setTokenUsage(fromJsonMap(rs.getString("token_usage")));
        boolean streamStopped = rs.getBoolean("stream_stopped");
        m.setStreamStopped(rs.wasNull() ? null : streamStopped);
        boolean validationFailed = rs.getBoolean("validation_failed");
        m.setValidationFailed(rs.wasNull() ? null : validationFailed);
        m.setTimestamp(rs.getTimestamp("created_at").toInstant());
        return m;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize value to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Object fromJsonAny(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
