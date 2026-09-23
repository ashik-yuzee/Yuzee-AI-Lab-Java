package com.yuzee.tokenlab.repository;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context) for LocalConversationStore semantics on data/conversations.json:
 * missing file = empty list, upsert in place, delete, JSON.stringify(all, null, 2) bytes, the original's key
 * order round-tripping through a reload, and an unreadable file never being overwritten.
 */
class FileConversationRepositoryTest {

    @Test
    void missingFileIsAnEmptyList(@TempDir Path dir) {
        assertTrue(new FileConversationRepository(dir.resolve("conversations.json")).loadConversations().isEmpty());
    }

    @Test
    void upsertDeleteAndExactBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("conversations.json");
        FileConversationRepository repo = new FileConversationRepository(file);
        Conversation a = Conversation.fromClientJson(new java.util.LinkedHashMap<>(Map.of("id", "a")));
        a.setTitle("v1");
        Conversation b = Conversation.fromClientJson(new java.util.LinkedHashMap<>(Map.of("id", "b")));
        repo.saveConversation(a);
        repo.saveConversation(b);
        a.setTitle("v2");
        repo.saveConversation(a);
        assertEquals("[\n  {\n    \"id\": \"a\",\n    \"title\": \"v2\",\n    \"messages\": []\n  },\n  {\n    \"id\": \"b\",\n    \"messages\": []\n  }\n]",
            Files.readString(file));
        repo.deleteConversation("a");
        assertEquals(List.of("b"), repo.loadConversations().stream().map(Conversation::getId).toList());
    }

    @Test
    void reloadKeepsKeyOrderAndUnknownKeys(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("conversations.json");
        String stored = "[\n  {\n    \"messages\": [\n      {\n        \"role\": \"user\",\n        \"id\": \"m1\",\n        \"content\": \"hi\",\n"
            + "        \"custom\": 1.5,\n        \"createdAt\": 5\n      }\n    ],\n    \"id\": \"x\",\n    \"extra\": {},\n    \"activeInteraction\": null\n  }\n]";
        Files.writeString(file, stored);
        FileConversationRepository repo = new FileConversationRepository(file);
        Conversation c = repo.loadConversations().get(0);
        ChatMessage m = c.getMessages().get(0);
        assertTrue(m.isSaved());
        repo.saveConversation(c);
        assertEquals(stored, Files.readString(file));
    }

    @Test
    void unreadableHistoryIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("conversations.json");
        Files.writeString(file, "{\"not\":\"a list\"}");
        FileConversationRepository repo = new FileConversationRepository(file);
        assertThrows(RuntimeException.class, repo::loadConversations);
        assertThrows(RuntimeException.class, () -> repo.saveConversation(new Conversation()));
        assertEquals("{\"not\":\"a list\"}", Files.readString(file));
    }
}
