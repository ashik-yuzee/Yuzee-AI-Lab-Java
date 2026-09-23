package com.yuzee.tokenlab.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.service.JsJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * LocalConversationStore.ts: one JSON list per file (data/conversations.json, data/mini-pathways.json,
 * data/detail-research.json), no cache (every list() re-reads the file), updates serialised and written as
 * JSON.stringify(all, null, 2) to `<file>.<uuid>.tmp` (mode 0600) and renamed over the file.
 */
public class LocalJsonStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() { };

    private final Path file;

    public LocalJsonStore(Path file) {
        this.file = file;
    }

    public Path file() { return file; }

    /** read(): [] when the file does not exist; any other failure is thrown (never overwrite unreadable history). */
    private List<Map<String, Object>> read() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            if (!MAPPER.readTree(text).isArray()) throw new IllegalStateException("Conversation storage must contain a list.");
            return MAPPER.readValue(text, LIST);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ponytail: `synchronized` stands in for the original's promise chain (one writer at a time per store).
    private synchronized void update(UnaryOperator<List<Map<String, Object>>> change) {
        writeAtomic(file, JsJson.pretty(change.apply(read())));
    }

    public synchronized List<Map<String, Object>> list() { return read(); }

    /** save(): replace the record with the same id in place, else append (a snapshot of the value). */
    public void save(Map<String, Object> record) {
        Map<String, Object> snapshot = MAPPER.convertValue(record, new TypeReference<Map<String, Object>>() { });
        update(all -> {
            int index = -1;
            for (int i = 0; i < all.size(); i++) if (java.util.Objects.equals(all.get(i).get("id"), snapshot.get("id"))) { index = i; break; }
            if (index < 0) all.add(snapshot); else all.set(index, snapshot);
            return all;
        });
    }

    public void delete(String id) {
        update(all -> { all.removeIf(c -> id.equals(c.get("id"))); return all; });
    }

    /** mkdir -p, writeFile(`${file}.${uuid}.tmp`, text, {mode: 0o600}), rename over the file, always rm the temp file. */
    public static void writeAtomic(Path file, String text) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
