package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-model explicit Gemini context caches for the Yuzee system prompt.
 * Caching the shared system instructions avoids resending the full prompt on cache hits.
 * Cache creation/refresh happens in the background -- the first request(s) per model fall
 * back to sending the system instruction inline while the cache is being (re)built.
 *
 * Behavior ported from yuzee-ai-token-lab/src/services/SystemPromptCacheManager.ts:
 *  - 1 hour TTL, refreshed proactively once less than 10 minutes remain.
 *  - Rebuilds the cache when the caller-supplied promptHash no longer matches the cached
 *    entry; the stale remote cache is deleted in the background.
 *  - A model that rejects caching once (e.g. tier doesn't support it) is marked permanently
 *    failed and is never retried again for this process's lifetime.
 *  - Uses Gemini's REST context-caching endpoints ({@code POST/PATCH/DELETE
 *    {baseUrl}/cachedContents}) instead of the {@code @google/genai} Node SDK the TS version used.
 *
 * State is kept in-memory only (per model), same as the original in-process cache -- it is
 * fine for it to reset on restart.
 */
@Service
public class SystemPromptCacheManager {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptCacheManager.class);
    private static final long TTL_SECONDS = 3600L; // 1 hour
    private static final long REFRESH_BEFORE_MS = 10 * 60 * 1000L; // refresh when < 10 min left

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.base-url}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build();

    // ponytail: a small daemon pool for background create/refresh/delete calls is enough here;
    // add a bounded queue + rejection policy only if cache churn ever becomes a real problem.
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "system-prompt-cache-manager");
        thread.setDaemon(true);
        return thread;
    });

    private static final class CacheEntry {
        final String name; // e.g. "cachedContents/abc123"
        final String promptHash;
        final long expiresAt;

        CacheEntry(String name, String promptHash, long expiresAt) {
            this.name = name;
            this.promptHash = promptHash;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, CacheEntry> caches = new ConcurrentHashMap<>();
    private final Set<String> creating = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet(); // models that don't support caching on this tier

    /**
     * Returns the cachedContent name when ready, null otherwise (never blocks: null while the
     * cache is being created). Kicks off background creation on first call per model.
     * Refreshes TTL proactively when close to expiry. If the prompt hash changed (new deploy),
     * rebuilds the cache. Returns null without a Gemini key (the original skips the lookup
     * when there is no Gemini client).
     */
    public String getCacheForModel(String modelId, String systemInstruction, String promptHash) {
        if (apiKey == null || apiKey.isBlank() || modelId == null) {
            return null;
        }
        CacheEntry entry = caches.get(modelId);
        if (entry != null) {
            long msRemaining = entry.expiresAt - System.currentTimeMillis();
            boolean isStale = !entry.promptHash.equals(promptHash);

            if (!isStale && msRemaining > 0) {
                if (msRemaining < REFRESH_BEFORE_MS && creating.add(modelId)) {
                    backgroundExecutor.submit(() -> refresh(modelId, entry.name, promptHash));
                }
                return entry.name;
            }
            // Expired or stale prompt -- remove and rebuild
            caches.remove(modelId);
            if (isStale) {
                // Prompt changed: delete old remote cache to avoid paying storage for unused content
                backgroundExecutor.submit(() -> deleteCache(entry.name));
            }
        }

        if (!failed.contains(modelId) && creating.add(modelId)) {
            backgroundExecutor.submit(() -> create(modelId, systemInstruction, promptHash));
        }
        return null;
    }

    /** CacheStatus: {active:true, name, ttlMs, creating:false} or {active:false, creating}. */
    public Map<String, Object> getStatus(String modelId) {
        Map<String, Object> status = new LinkedHashMap<>();
        CacheEntry entry = modelId == null ? null : caches.get(modelId);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAt > now) {
            status.put("active", true);
            status.put("name", entry.name);
            status.put("ttlMs", entry.expiresAt - now);
            status.put("creating", false);
            return status;
        }
        status.put("active", false);
        status.put("creating", modelId != null && creating.contains(modelId));
        return status;
    }

    private void create(String modelId, String systemInstructionText, String promptHash) {
        try {
            String geminiModel = modelId.startsWith("models/") ? modelId : "models/" + modelId;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", geminiModel);
            ObjectNode systemInstruction = mapper.createObjectNode().put("role", "user"); // SDK tContent(string)
            ArrayNode parts = mapper.createArrayNode();
            parts.add(mapper.createObjectNode().put("text", systemInstructionText));
            systemInstruction.set("parts", parts);
            body.set("systemInstruction", systemInstruction);
            body.put("ttl", TTL_SECONDS + "s");
            body.put("displayName", "yuzee-prompt-v" + SystemPromptService.VERSION + "-" + modelId);

            Request request = new Request.Builder()
                .url(baseUrl + "/cachedContents?key=" + apiKey)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    failed.add(modelId); // don't retry -- caching not supported for this model/tier
                    return;
                }
                JsonNode result = mapper.readTree(response.body().string());
                String name = result.path("name").asText(null);
                if (name != null && !name.isEmpty()) {
                    caches.put(modelId, new CacheEntry(name, promptHash, System.currentTimeMillis() + (TTL_SECONDS - 60) * 1000));
                    log.info("[CacheManager] Created cache {} for {}", name, modelId);
                }
            }
        } catch (Exception e) {
            failed.add(modelId); // don't retry -- caching not supported for this model/tier
        } finally {
            creating.remove(modelId);
        }
    }

    private void refresh(String modelId, String name, String promptHash) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("ttl", TTL_SECONDS + "s");

            Request request = new Request.Builder()
                .url(baseUrl + "/" + name + "?key=" + apiKey)
                .patch(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    caches.put(modelId, new CacheEntry(name, promptHash, System.currentTimeMillis() + (TTL_SECONDS - 60) * 1000));
                } else {
                    caches.remove(modelId); // will be recreated on next request
                }
            }
        } catch (Exception e) {
            caches.remove(modelId); // will be recreated on next request
        } finally {
            creating.remove(modelId);
        }
    }

    private void deleteCache(String name) {
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/" + name + "?key=" + apiKey)
                .delete()
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                // Best-effort cleanup of an unused remote cache; ignore the result either way.
            }
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }
}
