package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class GeminiService {

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

    public static final String DEFAULT_MODEL = "gemini-3.6-flash";

    /**
     * Build a Gemini contents array from conversation messages.
     */
    public ArrayNode buildContents(List<Map<String, Object>> messages) {
        ArrayNode contents = mapper.createArrayNode();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            ObjectNode item = mapper.createObjectNode();
            item.put("role", "user".equals(role) ? "user" : "model");
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode part = mapper.createObjectNode();
            part.put("text", content != null ? content.toString() : "");
            parts.add(part);
            item.set("parts", parts);
            contents.add(item);
        }
        return contents;
    }

    /**
     * Stream a Gemini response and emit SSE events.
     * Calls onChunk for each text chunk, onDone when complete.
     */
    public void streamGenerate(
        String model,
        String systemInstruction,
        ArrayNode contents,
        String responseMimeType,
        Consumer<String> onChunk,
        Consumer<Map<String, Object>> onDone,
        Consumer<Exception> onError
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            onError.accept(new IllegalStateException("GEMINI_API_KEY not configured"));
            return;
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            body.set("contents", contents);

            if (systemInstruction != null && !systemInstruction.isBlank()) {
                ObjectNode si = mapper.createObjectNode();
                ArrayNode siParts = mapper.createArrayNode();
                ObjectNode siPart = mapper.createObjectNode();
                siPart.put("text", systemInstruction);
                siParts.add(siPart);
                si.set("parts", siParts);
                body.set("system_instruction", si);
            }

            ObjectNode genConfig = mapper.createObjectNode();
            if (responseMimeType != null) {
                genConfig.put("responseMimeType", responseMimeType);
            }
            genConfig.put("maxOutputTokens", 8192);
            body.set("generationConfig", genConfig);

            String url = baseUrl + "/models/" + model + ":streamGenerateContent?alt=sse&key=" + apiKey;

            Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Unknown error";
                    onError.accept(new RuntimeException("Gemini error " + response.code() + ": " + err));
                    return;
                }

                StringBuilder fullText = new StringBuilder();
                int promptTokens = 0;
                int outputTokens = 0;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JsonNode chunk = mapper.readTree(data);
                                JsonNode candidates = chunk.path("candidates");
                                if (candidates.isArray() && !candidates.isEmpty()) {
                                    JsonNode parts = candidates.get(0).path("content").path("parts");
                                    if (parts.isArray()) {
                                        for (JsonNode part : parts) {
                                            String text = part.path("text").asText("");
                                            if (!text.isEmpty()) {
                                                fullText.append(text);
                                                onChunk.accept(text);
                                            }
                                        }
                                    }
                                }
                                JsonNode usage = chunk.path("usageMetadata");
                                if (!usage.isMissingNode()) {
                                    promptTokens = usage.path("promptTokenCount").asInt(promptTokens);
                                    outputTokens = usage.path("candidatesTokenCount").asInt(outputTokens);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                onDone.accept(Map.of(
                    "text", fullText.toString(),
                    "promptTokens", promptTokens,
                    "outputTokens", outputTokens
                ));
            }
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /** Richer result for {@link #streamGenerateRich}, carrying the full usage breakdown Gemini reports. */
    public static class StreamResult {
        public String text = "";
        public int promptTokens;
        public int outputTokens;
        public int cachedTokens;
        public int thinkingTokens;
        public String finishReason;
    }

    /**
     * Streaming generate with the full request surface RequestAssemblerService/
     * SystemPromptCacheManager need: merged-in generationConfig extras (thinkingConfig,
     * sanitized responseSchema, maxOutputTokens, responseMimeType) and an optional Gemini
     * explicit cache reference. When {@code cachedContentName} is set, {@code systemInstruction}
     * is omitted from the request body (it is already baked into the cache).
     */
    public void streamGenerateRich(
        String model,
        String systemInstruction,
        ArrayNode contents,
        ObjectNode generationConfigExtras,
        String cachedContentName,
        Consumer<String> onChunk,
        Consumer<StreamResult> onDone,
        Consumer<Exception> onError
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            onError.accept(new IllegalStateException("GEMINI_API_KEY not configured"));
            return;
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            body.set("contents", contents);

            if (cachedContentName != null && !cachedContentName.isBlank()) {
                body.put("cachedContent", cachedContentName);
            } else if (systemInstruction != null && !systemInstruction.isBlank()) {
                ObjectNode si = mapper.createObjectNode();
                ArrayNode siParts = mapper.createArrayNode();
                siParts.add(mapper.createObjectNode().put("text", systemInstruction));
                si.set("parts", siParts);
                body.set("system_instruction", si);
            }

            ObjectNode genConfig = mapper.createObjectNode();
            genConfig.put("maxOutputTokens", 8192);
            if (generationConfigExtras != null) {
                generationConfigExtras.fields().forEachRemaining(e -> genConfig.set(e.getKey(), e.getValue()));
            }
            body.set("generationConfig", genConfig);

            String url = baseUrl + "/models/" + model + ":streamGenerateContent?alt=sse&key=" + apiKey;
            Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Unknown error";
                    onError.accept(new RuntimeException("Gemini error " + response.code() + ": " + err));
                    return;
                }

                StreamResult result = new StreamResult();
                StringBuilder fullText = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if (data.equals("[DONE]")) break;
                        try {
                            JsonNode chunk = mapper.readTree(data);
                            JsonNode candidates = chunk.path("candidates");
                            if (candidates.isArray() && !candidates.isEmpty()) {
                                JsonNode candidate = candidates.get(0);
                                String fr = candidate.path("finishReason").asText("");
                                if (!fr.isEmpty()) result.finishReason = fr;
                                JsonNode parts = candidate.path("content").path("parts");
                                if (parts.isArray()) {
                                    for (JsonNode part : parts) {
                                        String text = part.path("text").asText("");
                                        if (!text.isEmpty()) {
                                            fullText.append(text);
                                            onChunk.accept(text);
                                        }
                                    }
                                }
                            }
                            JsonNode usage = chunk.path("usageMetadata");
                            if (!usage.isMissingNode()) {
                                result.promptTokens = usage.path("promptTokenCount").asInt(result.promptTokens);
                                result.outputTokens = usage.path("candidatesTokenCount").asInt(result.outputTokens);
                                result.cachedTokens = usage.path("cachedContentTokenCount").asInt(result.cachedTokens);
                                result.thinkingTokens = usage.path("thoughtsTokenCount").asInt(result.thinkingTokens);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                result.text = fullText.toString();
                onDone.accept(result);
            }
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * Non-streaming generate — returns the full text response.
     */
    public String generate(String model, String systemInstruction, String userMessage) throws IOException {
        if (apiKey == null || apiKey.isBlank()) return "GEMINI_API_KEY not configured";

        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = mapper.createArrayNode();
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode parts = mapper.createArrayNode();
        ObjectNode part = mapper.createObjectNode();
        part.put("text", userMessage);
        parts.add(part);
        userContent.set("parts", parts);
        contents.add(userContent);
        body.set("contents", contents);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            ObjectNode si = mapper.createObjectNode();
            ArrayNode siParts = mapper.createArrayNode();
            ObjectNode siPart = mapper.createObjectNode();
            siPart.put("text", systemInstruction);
            siParts.add(siPart);
            si.set("parts", siParts);
            body.set("system_instruction", si);
        }

        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("maxOutputTokens", 4096);
        body.set("generationConfig", genConfig);

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error: " + response.code();
            JsonNode result = mapper.readTree(response.body().string());
            return result.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText("");
        }
    }

    /** True when GEMINI_API_KEY is configured -- callers can fail fast with a friendly message instead. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** One grounding source, ported from a Gemini {@code groundingChunks} entry. */
    public static final class GroundingChunk {
        public final String uri;
        public final String title;

        public GroundingChunk(String uri, String title) {
            this.uri = uri;
            this.title = title;
        }
    }

    /** One grounded excerpt, ported from a Gemini {@code groundingSupports} entry. */
    public static final class GroundingSupport {
        public final String segmentText;
        public final List<Integer> chunkIndices;

        public GroundingSupport(String segmentText, List<Integer> chunkIndices) {
            this.segmentText = segmentText;
            this.chunkIndices = chunkIndices;
        }
    }

    /** Result of {@link #generateGrounded}: text plus the grounding metadata and usage Gemini reports. */
    public static final class GroundedResult {
        public String text = "";
        public String finishReason;
        public int promptTokens;
        public int outputTokens; // candidatesTokenCount + thoughtsTokenCount
        public int searchQueries;
        public String searchSuggestionsHtml = "";
        public List<GroundingChunk> groundingChunks = new java.util.ArrayList<>();
        public List<GroundingSupport> groundingSupports = new java.util.ArrayList<>();
    }

    /**
     * Non-streaming generate with Gemini's Google Search grounding tool enabled ({@code tools:
     * [{"google_search": {}}]}), so the model can ground its answer in live search results. Built
     * the same way {@link #generate} builds its request (same OkHttp client, same auth pattern),
     * with the {@code tools} field added and {@code groundingMetadata} parsed out of the response
     * alongside the usual {@code content.parts[].text}.
     */
    public GroundedResult generateGrounded(String model, String systemInstruction, String userMessage, int maxOutputTokens) throws IOException {
        if (!isConfigured()) throw new IllegalStateException("GEMINI_API_KEY not configured");

        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = mapper.createArrayNode();
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode parts = mapper.createArrayNode();
        parts.add(mapper.createObjectNode().put("text", userMessage));
        userContent.set("parts", parts);
        contents.add(userContent);
        body.set("contents", contents);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            ObjectNode si = mapper.createObjectNode();
            ArrayNode siParts = mapper.createArrayNode();
            siParts.add(mapper.createObjectNode().put("text", systemInstruction));
            si.set("parts", siParts);
            body.set("system_instruction", si);
        }

        ArrayNode tools = mapper.createArrayNode();
        tools.add(mapper.createObjectNode().set("google_search", mapper.createObjectNode()));
        body.set("tools", tools);

        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("maxOutputTokens", maxOutputTokens);
        body.set("generationConfig", genConfig);

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("Gemini error " + response.code() + ": " + err);
            }
            JsonNode root = mapper.readTree(response.body().string());
            GroundedResult result = new GroundedResult();
            JsonNode candidate = root.path("candidates").path(0);
            result.finishReason = candidate.path("finishReason").asText(null);

            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            result.text = text.toString();

            JsonNode grounding = candidate.path("groundingMetadata");
            for (JsonNode chunk : grounding.path("groundingChunks")) {
                JsonNode web = chunk.path("web");
                result.groundingChunks.add(new GroundingChunk(web.path("uri").asText(null), web.path("title").asText("")));
            }
            for (JsonNode support : grounding.path("groundingSupports")) {
                String segmentText = support.path("segment").path("text").asText("").trim();
                List<Integer> indices = new java.util.ArrayList<>();
                for (JsonNode i : support.path("groundingChunkIndices")) indices.add(i.asInt());
                result.groundingSupports.add(new GroundingSupport(segmentText, indices));
            }
            for (JsonNode q : grounding.path("webSearchQueries")) {
                if (q.isTextual() && !q.asText().isBlank()) result.searchQueries++;
            }
            result.searchSuggestionsHtml = grounding.path("searchEntryPoint").path("renderedContent").asText("");

            JsonNode usage = root.path("usageMetadata");
            result.promptTokens = usage.path("promptTokenCount").asInt(0);
            result.outputTokens = usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
            return result;
        }
    }

    /** Result of {@link #generateJson}: text plus the finish reason and usage Gemini reports. */
    public static final class JsonResult {
        public String text = "";
        public String finishReason;
        public int promptTokens;
        public int outputTokens;
    }

    /**
     * Non-streaming JSON-mode generate that (unlike {@link #generate}) reports back the finish
     * reason and token usage, and takes an explicit output-token budget. Built the same way
     * {@link #generate} builds its request.
     */
    public JsonResult generateJson(String model, String systemInstruction, String userMessage, int maxOutputTokens) throws IOException {
        if (!isConfigured()) throw new IllegalStateException("GEMINI_API_KEY not configured");

        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = mapper.createArrayNode();
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode parts = mapper.createArrayNode();
        parts.add(mapper.createObjectNode().put("text", userMessage));
        userContent.set("parts", parts);
        contents.add(userContent);
        body.set("contents", contents);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            ObjectNode si = mapper.createObjectNode();
            ArrayNode siParts = mapper.createArrayNode();
            siParts.add(mapper.createObjectNode().put("text", systemInstruction));
            si.set("parts", siParts);
            body.set("system_instruction", si);
        }

        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("maxOutputTokens", maxOutputTokens);
        body.set("generationConfig", genConfig);

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("Gemini error " + response.code() + ": " + err);
            }
            JsonNode root = mapper.readTree(response.body().string());
            JsonResult result = new JsonResult();
            JsonNode candidate = root.path("candidates").path(0);
            result.finishReason = candidate.path("finishReason").asText(null);
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            result.text = text.toString();
            JsonNode usage = root.path("usageMetadata");
            result.promptTokens = usage.path("promptTokenCount").asInt(0);
            result.outputTokens = usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
            return result;
        }
    }
}
