package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The DDL the Java app sends equals db.ts initDb()'s statements, text and order (parity/persistence/db-schema.json is
 * written by persistence.parity.mts from the original db.ts), and no query references a column db.ts does not create.
 */
class DbSchemaParityTest {

    @Test
    void ddlEqualsDbTs() throws Exception {
        List<String> original = new ObjectMapper().readValue(getClass().getResourceAsStream("/parity/persistence/db-schema.json"),
            new TypeReference<>() { });
        assertEquals(original, DbSchema.statements());
    }

    @Test
    void noJavaOnlyColumns() {
        Pattern javaOnly = Pattern.compile("\\b(model_id|optimization_mode|summary_text|profile_facts|mini_pathways|details|objectives|"
            + "parsed_response|token_usage|stream_stopped|validation_failed|micro_tool_name)\\b");
        for (String sql : List.of(JdbcConversationRepository.SAVE_CONVERSATION_SQL, JdbcConversationRepository.SAVE_MESSAGE_SQL)) {
            assertFalse(javaOnly.matcher(sql).find(), sql);
        }
        for (String sql : DbSchema.statements()) assertFalse(javaOnly.matcher(sql).find(), sql);
    }
}
