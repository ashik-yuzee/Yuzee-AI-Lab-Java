package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context) for the local JSON-file fallback used when no
 * DATABASE_URL is set. Exercises the bits that matter most: save/find round-trips a full
 * conversation (including nested messages), delete and prune actually rewrite the file, and a
 * fresh/empty file behaves like an empty store instead of throwing.
 */
class FileConversationRepositoryTest {

    private ConversationRepository newRepo(Path dir) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new FileConversationRepository(mapper, dir.resolve("conversations.json"));
    }

    @Test
    void listAllOnMissingFileReturnsEmptyInsteadOfThrowing(@TempDir Path dir) {
        assertTrue(newRepo(dir).listAll().isEmpty());
    }

    @Test
    void saveThenFindByIdRoundTripsMessagesAndNestedFields(@TempDir Path dir) {
        ConversationRepository repo = newRepo(dir);
        Conversation conv = new Conversation();
        conv.setTitle("Test convo");
        conv.setModelId("gemini-3.5-flash");
        conv.setCareerContext(java.util.Map.of("goal", "become a vet"));
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent("hello there");
        conv.getMessages().add(msg);

        repo.save(conv);

        Conversation found = repo.findById(conv.getId()).orElseThrow();
        assertEquals("Test convo", found.getTitle());
        assertEquals(1, found.getMessages().size());
        assertEquals("hello there", found.getMessages().get(0).getContent());
        assertEquals("become a vet", found.getCareerContext().get("goal"));
    }

    @Test
    void saveIsUpsertNotAppend(@TempDir Path dir) {
        ConversationRepository repo = newRepo(dir);
        Conversation conv = new Conversation();
        conv.setTitle("v1");
        repo.save(conv);
        conv.setTitle("v2");
        repo.save(conv);

        List<Conversation> all = repo.listAll();
        assertEquals(1, all.size());
        assertEquals("v2", all.get(0).getTitle());
    }

    @Test
    void deleteRemovesOnlyTheMatchingConversation(@TempDir Path dir) {
        ConversationRepository repo = newRepo(dir);
        Conversation keep = new Conversation();
        Conversation gone = new Conversation();
        repo.save(keep);
        repo.save(gone);

        assertTrue(repo.delete(gone.getId()));
        assertFalse(repo.delete("does-not-exist"));
        assertEquals(List.of(keep.getId()), repo.listAll().stream().map(Conversation::getId).toList());
    }

    @Test
    void pruneExpiredRemovesOnlyConversationsPastThirtyDayTtl(@TempDir Path dir) {
        ConversationRepository repo = newRepo(dir);
        Conversation fresh = new Conversation();
        fresh.setUpdatedAt(Instant.now());
        Conversation stale = new Conversation();
        stale.setUpdatedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        repo.save(fresh);
        repo.save(stale);

        int removed = repo.pruneExpired();

        assertEquals(1, removed);
        assertEquals(List.of(fresh.getId()), repo.listAll().stream().map(Conversation::getId).toList());
    }
}
