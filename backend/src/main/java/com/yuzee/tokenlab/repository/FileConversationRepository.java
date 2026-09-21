package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.Conversation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Local JSON-file ConversationRepository, used when no DATABASE_URL is configured. Port of the
 * old Node app's LocalConversationStore.ts: a single file holding every conversation, written
 * via temp-file-then-atomic-rename so a crash mid-write can never corrupt it.
 * <p>
 * The old store had no TTL (that lived only in the Postgres schema). Since Conversation has no
 * expiresAt field, this repository treats "30 days since last update" as the expiry, computed
 * from updatedAt at prune time — same 30-day window, no schema needed.
 * <p>
 * // ponytail: writes are serialized with a plain `synchronized` (blocking) instead of the old
 * // app's promise queue — simplest thing that prevents concurrent writers from corrupting the
 * // file. Revisit only if save() ever needs to be non-blocking under real concurrent load.
 */
public class FileConversationRepository implements ConversationRepository {

    private static final int TTL_DAYS = 30;

    private final Path file;
    private final ObjectMapper mapper;

    public FileConversationRepository(ObjectMapper mapper) {
        this(mapper, Paths.get("data", "conversations.json"));
    }

    FileConversationRepository(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    private List<Conversation> readAll() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            Conversation[] all = mapper.readValue(file.toFile(), Conversation[].class);
            return new ArrayList<>(List.of(all));
        } catch (IOException e) {
            // Never overwrite unreadable history with an empty list — surface the failure instead.
            throw new UncheckedIOException("Failed reading conversation store: " + file, e);
        }
    }

    private void writeAll(List<Conversation> all) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing conversation store: " + file, e);
        }
    }

    @Override
    public synchronized List<Conversation> listAll() {
        return readAll().stream()
            .sorted(Comparator.comparing(Conversation::getUpdatedAt).reversed())
            .toList();
    }

    @Override
    public synchronized Optional<Conversation> findById(String id) {
        return readAll().stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    @Override
    public synchronized Conversation save(Conversation conversation) {
        List<Conversation> all = readAll();
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(conversation.getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) all.add(conversation); else all.set(index, conversation);
        writeAll(all);
        return conversation;
    }

    @Override
    public synchronized boolean delete(String id) {
        List<Conversation> all = readAll();
        boolean removed = all.removeIf(c -> c.getId().equals(id));
        if (removed) writeAll(all);
        return removed;
    }

    @Override
    public synchronized int pruneExpired() {
        List<Conversation> all = readAll();
        Instant cutoff = Instant.now().minus(TTL_DAYS, ChronoUnit.DAYS);
        int before = all.size();
        all.removeIf(c -> c.getUpdatedAt() != null && c.getUpdatedAt().isBefore(cutoff));
        int removed = before - all.size();
        if (removed > 0) writeAll(all);
        return removed;
    }
}
