package com.yuzee.tokenlab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SystemPromptService {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptService.class);
    private static final String CLASSPATH = "prompts/system-prompt.md";
    /**
     * YuzeeRequestAssembler reads the prompt from the working tree, so /api/system-prompt/reload picks up edits.
     * The same file on disk: cwd is backend/ under spring-boot:run and the repo root for a jar (as the .env import).
     */
    private static final List<Path> DISK = List.of(Path.of("src/main/resources", CLASSPATH), Path.of("backend/src/main/resources", CLASSPATH));

    private final AtomicReference<String> prompt = new AtomicReference<>();

    public String getPrompt() {
        return prompt.updateAndGet(p -> p != null ? p : load());
    }

    /** Disk copy first, then the packaged copy; "" when neither exists (the original's promptContent stays empty). */
    private static String load() {
        try {
            for (Path file : DISK) if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
            ClassPathResource res = new ClassPathResource(CLASSPATH);
            if (res.exists()) {
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.warn("[SystemPromptService] Prompt file could not be read", e);
            return "";
        }
        log.warn("[SystemPromptService] Prompt file not found at {}", CLASSPATH);
        return "";
    }

    public static final String VERSION = "1.11";
    public static final String FILENAME = "Yuzee_Quiz_Counsellor_Training_v" + VERSION + ".md";

    /** Full hex SHA-256 of the default prompt, matching the original's requestAssembler.getPromptHash(). */
    public String getHash() { return sha256(getPrompt()); }

    public int getBytes() { return getPrompt().getBytes(StandardCharsets.UTF_8).length; }

    public Map<String, Object> getInfo() {
        return Map.of(
            "content", getPrompt(),
            "hash", getHash(),
            "bytes", getBytes(),
            "filename", FILENAME,
            "version", VERSION,
            "filepath", "src/main/resources/prompts/system-prompt.md",
            "label", "Yuzee Quiz Prompt v" + VERSION
        );
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** requestAssembler.reload(): re-read the prompt file now. */
    public void reload() {
        prompt.set(load());
    }
}
