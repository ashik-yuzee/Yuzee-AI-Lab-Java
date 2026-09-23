package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment-wide settings every user shares: system prompt mode and retention defaults.
 * Port of the original's src/shared-settings.ts (same fields, defaults, file location and
 * patch validation), so the chat turn resolves budget/strategy/prompt the same way.
 */
@Service
public class SharedSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SharedSettingsService.class);
    private static final Path FILE = Path.of("data", "shared-settings.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> settings;

    public SharedSettingsService() {
        settings = load();
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("systemPromptMode", "default");
        d.put("customSystemPrompt", "");
        d.put("contextBudget", 270000);
        d.put("recentTurnsToKeep", 100);
        d.put("strategy", "ADAPTIVE_HYBRID");
        d.put("updatedAt", 0L);
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        Map<String, Object> s = defaults();
        try {
            if (Files.exists(FILE)) s.putAll(mapper.readValue(FILE.toFile(), Map.class));
        } catch (IOException e) {
            log.warn("Shared settings load failed; using defaults", e);
        }
        return s;
    }

    public synchronized Map<String, Object> get() {
        return new LinkedHashMap<>(settings);
    }

    /** Applies only well-formed fields, exactly like the original's PUT /api/shared-settings. */
    public synchronized Map<String, Object> update(Map<String, Object> body) {
        Object mode = body.get("systemPromptMode");
        if ("default".equals(mode) || "custom".equals(mode)) settings.put("systemPromptMode", mode);
        if (body.get("customSystemPrompt") instanceof String s) settings.put("customSystemPrompt", s);
        if (body.get("contextBudget") instanceof Number n && n.doubleValue() > 0) settings.put("contextBudget", n.intValue());
        if (body.get("recentTurnsToKeep") instanceof Number n && n.doubleValue() > 0) settings.put("recentTurnsToKeep", n.intValue());
        if (body.get("strategy") instanceof String s) settings.put("strategy", s);
        settings.put("updatedAt", System.currentTimeMillis());
        save();
        return get();
    }

    public synchronized Map<String, Object> resetPrompt() {
        return update(Map.of("systemPromptMode", "default", "customSystemPrompt", ""));
    }

    /** The custom prompt when one is active, otherwise the file-loaded default. */
    public synchronized String effectivePrompt(String defaultContent) {
        if ("custom".equals(settings.get("systemPromptMode")) && settings.get("customSystemPrompt") instanceof String c && !c.isBlank()) {
            return c.trim();
        }
        return defaultContent;
    }

    public synchronized int contextBudget() {
        return settings.get("contextBudget") instanceof Number n ? n.intValue() : 270000;
    }

    public synchronized String strategy() {
        return settings.get("strategy") instanceof String s ? s : null;
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, JsJson.pretty(settings), java.nio.charset.StandardCharsets.UTF_8); // writeFileSync(JSON.stringify(s, null, 2))
        } catch (IOException e) {
            log.warn("Shared settings save failed", e);
        }
    }
}
