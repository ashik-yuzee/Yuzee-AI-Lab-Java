package com.yuzee.tokenlab.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * db.ts schema, statement for statement (the SQL text is copied from db.ts's template literals, whitespace
 * included) and run in initDb()'s order: conversation_logs, conversations, messages, then each index and each
 * additive migration with its own warning-only error handling. DbSchemaParityTest diffs {@link #statements()}
 * against the statements parsed out of the original db.ts.
 */
public final class DbSchema {

    private static final Logger log = LoggerFactory.getLogger(DbSchema.class);

    public static final String TABLE_CONVERSATIONS = """

        CREATE TABLE IF NOT EXISTS conversations (
          id                    TEXT PRIMARY KEY,
          title                 TEXT NOT NULL DEFAULT '',
          created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          expires_at            TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '30 days'),
          model                 TEXT NOT NULL DEFAULT 'gemini-3.5-flash',
          mode                  TEXT DEFAULT 'AUTO',
          strategy              TEXT DEFAULT 'ADAPTIVE_HYBRID',
          preset                TEXT DEFAULT 'BALANCED',
          response_mode         TEXT DEFAULT 'standard',
          thinking_level        TEXT DEFAULT 'adaptive',
          context_budget        INT DEFAULT 270000,
          recent_turns_to_keep  INT DEFAULT 100,
          summary               TEXT DEFAULT '',
          summary_version       INT DEFAULT 0,
          system_prompt_mode    TEXT DEFAULT 'default',
          custom_system_prompt  TEXT DEFAULT '',
          use_interactions_api  BOOLEAN DEFAULT FALSE,
          use_flash_lite_utility BOOLEAN DEFAULT TRUE,
          career_context        JSONB DEFAULT '{}',
          compaction_history    JSONB DEFAULT '[]',
          active_interaction    JSONB
        )""";

    public static final String TABLE_MESSAGES = """

        CREATE TABLE IF NOT EXISTS messages (
          id                  TEXT PRIMARY KEY,
          conversation_id     TEXT NOT NULL,
          role                TEXT NOT NULL,
          content             TEXT NOT NULL,
          structured_response JSONB,
          user_event          JSONB,
          telemetry           JSONB,
          feedback            JSONB,
          created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
        )""";

    public static final String TABLE_TURN_LOGS = """

        CREATE TABLE IF NOT EXISTS conversation_logs (
          id            BIGSERIAL PRIMARY KEY,
          logged_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          ip            TEXT        NOT NULL,
          conversation_id TEXT      NOT NULL,
          message_id    TEXT        NOT NULL,
          model         TEXT,
          input_tokens        INT,
          uncached_input_tokens INT,
          cached_tokens       INT,
          output_tokens       INT,
          thinking_tokens     INT,
          estimated_cost_usd  NUMERIC(12,8),
          latency_ms          INT,
          finish_reason       TEXT,
          is_mock       BOOLEAN     NOT NULL DEFAULT FALSE,
          is_whiteboard BOOLEAN     NOT NULL DEFAULT FALSE,
          user_input    TEXT,
          assistant_output TEXT,
          error_code    TEXT
        )""";

    public static final List<String> INDEX_SQLS = List.of(
        "CREATE INDEX IF NOT EXISTS idx_convlog_ip      ON conversation_logs (ip, logged_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_convlog_conv    ON conversation_logs (conversation_id)",
        "CREATE INDEX IF NOT EXISTS idx_conv_updated    ON conversations (updated_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_conv_expires    ON conversations (expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_msg_conv        ON messages (conversation_id, created_at ASC)");

    public static final List<String> MIGRATION_SQLS = List.of(
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS uncached_input_tokens INT",
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS thinking_tokens INT",
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS user_input TEXT",
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS assistant_output TEXT",
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS error_code TEXT",
        "ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS is_whiteboard BOOLEAN NOT NULL DEFAULT FALSE",
        "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS mode TEXT DEFAULT 'AUTO'",
        "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS compaction_history JSONB DEFAULT '[]'",
        "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS active_interaction JSONB");

    /** One initDb() per DataSource: both JDBC beans call {@link #init} and whichever is built first runs it. */
    private static final Set<Object> INITIALISED = Collections.newSetFromMap(new IdentityHashMap<>());

    private DbSchema() {
    }

    /** Every statement initDb() sends, in order. */
    public static List<String> statements() {
        List<String> all = new ArrayList<>(List.of(TABLE_TURN_LOGS, TABLE_CONVERSATIONS, TABLE_MESSAGES));
        all.addAll(INDEX_SQLS);
        all.addAll(MIGRATION_SQLS);
        return all;
    }

    /** initDb(): the tables in one try (a failure is logged and ends init), indexes and migrations one try each. */
    public static void init(JdbcTemplate jdbc) {
        synchronized (INITIALISED) {
            if (!INITIALISED.add(jdbc.getDataSource() != null ? jdbc.getDataSource() : jdbc)) return;
        }
        try {
            jdbc.execute(TABLE_TURN_LOGS);
            jdbc.execute(TABLE_CONVERSATIONS);
            jdbc.execute(TABLE_MESSAGES);
            for (String sql : INDEX_SQLS) {
                try { jdbc.execute(sql); } catch (Exception e) { log.warn("[db] Index warning:", e); }
            }
            for (String sql : MIGRATION_SQLS) {
                try { jdbc.execute(sql); } catch (Exception e) { log.warn("[db] Migration warning:", e); }
            }
            log.info("[db] Schema ready");
        } catch (Exception e) {
            log.error("[db] Init failed:", e);
        }
    }
}
