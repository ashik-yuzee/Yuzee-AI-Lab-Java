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

    public Map<String, Object> getInfo() {
        String text = getPrompt();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hash = HexFormat.of().formatHex(md.digest(bytes)).substring(0, 16);
        } catch (Exception e) {
            hash = "unknown";
        }
        return Map.of(
            "version", "1.11",
            "hash", hash,
            "byteSize", bytes.length,
            "label", "Yuzee Quiz Prompt v1.11"
        );
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
