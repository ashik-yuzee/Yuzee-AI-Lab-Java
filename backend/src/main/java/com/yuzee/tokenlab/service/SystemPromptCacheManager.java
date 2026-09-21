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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
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
 *  - Rebuilds the cache when the system instruction text changes (its hash no longer
 *    matches the cached entry); the stale remote cache is deleted in the background.
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
     * Returns the Gemini cache resource name to pass as {@code cachedContent} on a
     * generateContent/streamGenerateContent request, or empty when caching isn't usable for
     * this model right now (creation/refresh is still in flight, or this model has
     * permanently failed to cache). The caller must fall back to sending
     * {@code systemInstructionText} inline in that case -- a caching failure must never break
     * the actual chat request.
     *
     * Kicks off cache creation in the background on first call per model, and refreshes the
     * TTL proactively in the background when close to expiry. Rebuilds automatically when
     * {@code systemInstructionText} changes (detected via its hash).
     */
    public Optional<String> getOrCreateCache(String modelId, String systemInstructionText) {
        if (apiKey == null || apiKey.isBlank() || modelId == null || modelId.isBlank()
            || systemInstructionText == null || systemInstructionText.isBlank()) {
            return Optional.empty();
        }

        String promptHash = hash(systemInstructionText);
        CacheEntry entry = caches.get(modelId);
        if (entry != null) {
            long msRemaining = entry.expiresAt - System.currentTimeMillis();
            boolean stale = !entry.promptHash.equals(promptHash);

            if (!stale && msRemaining > 0) {
                if (msRemaining < REFRESH_BEFORE_MS && creating.add(modelId)) {
                    backgroundExecutor.submit(() -> refresh(modelId, entry.name, promptHash));
                }
                return Optional.of(entry.name);
            }

            // Expired or stale prompt -- remove and rebuild.
            caches.remove(modelId);
            if (stale) {
                // Prompt changed: delete the old remote cache so we don't pay storage for unused content.
                backgroundExecutor.submit(() -> deleteCache(entry.name));
            }
        }

        if (!failed.contains(modelId) && creating.add(modelId)) {
            backgroundExecutor.submit(() -> create(modelId, systemInstructionText, promptHash));
        }
        return Optional.empty();
    }

    private void create(String modelId, String systemInstructionText, String promptHash) {
        try {
            String geminiModel = modelId.startsWith("models/") ? modelId : "models/" + modelId;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", geminiModel);
            ObjectNode systemInstruction = mapper.createObjectNode();
            ArrayNode parts = mapper.createArrayNode();
            parts.add(mapper.createObjectNode().put("text", systemInstructionText));
            systemInstruction.set("parts", parts);
            body.set("systemInstruction", systemInstruction);
            body.put("ttl", TTL_SECONDS + "s");
            body.put("displayName", "yuzee-prompt-" + promptHash.substring(0, Math.min(16, promptHash.length())) + "-" + modelId);

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
                if (name != null && !name.isBlank()) {
                    caches.put(modelId, new CacheEntry(name, promptHash, System.currentTimeMillis() + (TTL_SECONDS - 60) * 1000));
                } else {
                    failed.add(modelId);
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

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
