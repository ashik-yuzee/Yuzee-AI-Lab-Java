package com.yuzee.tokenlab.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SystemPromptService {

    private final AtomicReference<String> prompt = new AtomicReference<>();

    public String getPrompt() {
        return prompt.updateAndGet(p -> {
            if (p != null) return p;
            try {
                ClassPathResource res = new ClassPathResource("prompts/system-prompt.md");
                if (res.exists()) {
                    try (InputStream is = res.getInputStream()) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                return defaultPrompt();
            } catch (IOException e) {
                return defaultPrompt();
            }
        });
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

    public void reload() {
        prompt.set(null);
    }

    private String defaultPrompt() {
        return """
            You are Yuzee, an expert career counsellor specialising in vocational education and training (VET).
            You help learners understand their options, explore pathways, and make informed decisions
            about courses, qualifications, and career directions.

            Always respond with a structured JSON response following the Yuzee Response Protocol v1.3.
            """;
    }
}
