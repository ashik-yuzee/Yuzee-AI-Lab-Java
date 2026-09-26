package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Persistence parity: runs the flows of src/test/parity/persistence.parity.mts (create, update, a bypass turn, a
 * Gemini turn, feedback, reset-memory, generate-title, two demo loads, restore, create+delete, shared settings, and
 * the mini-pathway / detail-research / objective stores) through the app in file-store mode and requires every file
 * under data/ to match what the original wrote for the same flows, byte for byte after masking clock values and
 * random ids. Responses are compared as JSON (key order included). Runs only in surefire's target/test-work sandbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "auth.admin-username=persist-user", "auth.admin-password=persist-pass", "auth.secret=persist-secret",
    "gemini.api-key=test-key", "spring.datasource.url=", "cloudflare.account-id=", "cloudflare.api-token=",
    "warehouse.db-path="
})
class PersistenceParityTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Deque<List<JsonNode>> OPENS = new ArrayDeque<>();

    @Autowired MockMvc mvc;
    @Autowired MiniPathwayService miniPathways;
    @Autowired DetailResearchService details;
    @Autowired ObjectiveService objectives;
    private String token;

    @BeforeAll
    static void sandboxOnly() {
        assumeTrue(Path.of("").toAbsolutePath().endsWith("test-work"), "runs only in the surefire sandbox");
        FileSystemUtils.deleteRecursively(Path.of("data").toFile());
    }

    /** The same scripted Gemini as persistence.parity.mts (a subclass: Mockito cannot instrument classes on this JDK). */
    static class ScriptedGemini extends GeminiService {
        @Override public boolean isConfigured() { return true; }
        @Override public Integer countTokens(String model, String text) throws IOException { throw new IOException("countTokens unsupported"); }
        @Override public TextResult generateText(String model, String prompt, Integer maxOutputTokens) {
            TextResult r = new TextResult();
            r.promptTokens = 11;
            r.candidatesTokens = 5;
            r.text = prompt.startsWith("Write a short title") ? "Career Change Plan." : "- Goal: nursing";
            return r;
        }
        @Override public JsonNode generateContentRaw(String model, ObjectNode body, long timeoutMs, java.util.function.Consumer<okhttp3.Call> onCall) throws IOException {
            return M.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"- Goal: nursing\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":5,\"totalTokenCount\":16}}");
        }
        @Override public OpenStream openStream(String model, ObjectNode body) throws IOException {
            List<JsonNode> chunks = OPENS.poll();
            if (chunks == null) throw new GeminiHttpException(400, "unscripted stream");
            return new OpenStream(null, null) {
                @Override public void forEachChunk(java.util.function.Consumer<JsonNode> onChunk, java.util.function.BooleanSupplier stop) {
                    for (JsonNode c : chunks) { if (stop.getAsBoolean()) break; onChunk.accept(c); }
                }
                @Override public void cancel() { }
                @Override public void close() { }
            };
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean @Primary GeminiService scriptedGemini() { return new ScriptedGemini(); }
        /** The stub's caches.create throws in the original, so no explicit cache is ever used. */
        @Bean @Primary SystemPromptCacheManager noCache() {
            return new SystemPromptCacheManager() {
                @Override public String getCacheForModel(String modelId, String systemInstruction, String promptHash) { return null; }
            };
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private MockHttpServletResponse raw(MockHttpServletRequestBuilder req, String body) throws Exception {
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(req).andReturn();
        if (r.getRequest().isAsyncStarted()) r.getAsyncResult(30_000);
        return r.getResponse();
    }

    private final List<Map<String, Object>> responses = new ArrayList<>();

    private JsonNode step(String name, MockHttpServletRequestBuilder req, String body) throws Exception {
        MockHttpServletResponse res = raw(req, body);
        Thread.sleep(250); // the original's writes are fire-and-forget; the token log is appended on an executor here
        String text = res.getContentAsString(StandardCharsets.UTF_8);
        boolean json = text.startsWith("{") || text.startsWith("[");
        responses.add(Map.of("name", name, "status", res.getStatus(), "body", json ? text : text.isEmpty() ? "" : "SSE"));
        return text.startsWith("{") ? M.readTree(text) : null;
    }

    private static final String[] VOLATILE = {"requestReceivedAt", "preProviderLatencyMs", "conversationLoadMs", "userEventValidationMs",
        "memoryAssemblyMs", "requestAssemblyMs", "providerRequestStartedAt", "providerTtftMs", "providerGenerationDurationMs",
        "providerCompletedAt", "validationDurationMs", "validationCompletedAt", "totalLatencyMs", "latencyMs", "timestamp", "ttlMs"};

    /** Clock values and random ids (any JSON escaping depth), identically for both apps' output. */
    static String mask(String s) {
        s = s.replaceAll("\"(" + String.join("|", VOLATILE) + ")\": ?\\d+", "\"$1\":0")
            .replaceAll("(\\\\*\"(?:" + String.join("|", VOLATILE) + ")\\\\*\": ?)\\d+", "$10")
            .replaceAll("conv-demo-\\d{13}", "conv-demo-ID").replaceAll("conv-\\d{13}-[a-z0-9]+", "conv-ID")
            .replaceAll("user-\\d{13}(-[a-z0-9]+)?", "user-ID").replaceAll("msg-\\d{13}-[a-z0-9]*", "msg-ID")
            .replaceAll("req-\\d{13}-[a-z0-9]*", "req-ID").replaceAll("(\\\\*\"compactionEventId\\\\*\": ?\\\\*\")[^\"\\\\]*", "$1ID")
            .replaceAll("(\\\\*\"revision\\\\*\": ?\\\\*\")[0-9a-f]{16}", "$1REV")
            .replaceAll("\\b1[78]\\d{11}\\b", "EPOCH").replaceAll("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z", "ISO");
        return s;
    }

    private static Map<String, String> dataFiles() throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path root = Path.of("data");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put("data/" + root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "  at " + i + "\n  ts:   " + a.substring(Math.max(0, i - 200), Math.min(a.length(), i + 200))
            + "\n  java: " + b.substring(Math.max(0, i - 200), Math.min(b.length(), i + 200));
    }

    // ---- flows (mirrored from persistence.parity.mts) ----------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void dataFilesMatchTheOriginal() throws Exception {
        JsonNode login = M.readTree(raw(post("/api/auth/login"), "{\"username\":\"persist-user\",\"password\":\"persist-pass\"}").getContentAsString());
        token = login.get("token").asText();

        step("shared-settings", put("/api/shared-settings"), "{\"strategy\":\"BASELINE\",\"contextBudget\":200000}");
        JsonNode c1 = step("create", post("/api/conversations"), "{\"model\":\"gemini-3.5-flash\",\"mode\":\"AUTO\",\"responseMode\":\"standard\",\"thinkingLevel\":\"adaptive\"}");
        String id = c1.get("id").asText();
        step("update", put("/api/conversations/" + id), "{\"title\":\"Renamed\",\"useMultiTurn\":false,\"temperature\":0.4,\"careerContext\":{\"facts\":\"Parent\",\"goals\":\"Nurse\"}}");
        step("turn-bypass", post("/api/conversations/" + id + "/messages"), "{\"message\":\"hi\"}");
        String reply = M.readTree(getClass().getResourceAsStream("/parity/persistence/files.json")).get("reply").asText(); // the scripted envelope text
        List<JsonNode> chunks = new ArrayList<>();
        chunks.add(M.readTree("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + JsJson.quote(reply.substring(0, 40)) + "}]}}]}"));
        chunks.add(M.readTree("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + JsJson.quote(reply.substring(40)) + "}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":1000,\"candidatesTokenCount\":200,\"totalTokenCount\":1200}}"));
        OPENS.add(chunks);
        step("turn-gemini", post("/api/conversations/" + id + "/messages"), "{\"message\":\"thanks, what can I study?\"}");
        JsonNode afterTurn = step("get", get("/api/conversations/" + id), null);
        step("feedback", post("/api/conversations/" + id + "/feedback").param("messageId", afterTurn.get("messages").get(3).get("id").asText()),
            "{\"rating\":\"up\",\"comment\":\"Clear \u2013 thanks\"}");
        step("reset-memory", post("/api/conversations/" + id + "/reset-memory"), null);
        step("generate-title", post("/api/conversations/" + id + "/generate-title"), null);
        step("demo-1", post("/api/conversations/load-demo"), null);
        Thread.sleep(5);
        step("demo-2", post("/api/conversations/load-demo"), null);
        step("restore", post("/api/conversations/restore"), "{\"id\":\"restored-1\",\"title\":\"Restored plan\",\"createdAt\":1600000000000,\"updatedAt\":1600000001000,"
            + "\"model\":\"gemini-3.5-flash\",\"careerContext\":{\"facts\":\"Works nights\"},\"summary\":\"Earlier summary\",\"summaryVersion\":2,"
            + "\"messages\":[{\"id\":\"r-user-1\",\"role\":\"user\",\"content\":\"Which course?\",\"createdAt\":1600000000500,\"isStreaming\":false},"
            + "{\"id\":\"r-asst-1\",\"role\":\"assistant\",\"content\":" + JsJson.quote(reply) + ",\"structuredResponse\":" + reply + ","
            + "\"telemetry\":{\"model\":\"gemini-3.5-flash\",\"validation\":{\"protocolAccepted\":true}},\"createdAt\":1600000000900}]}");
        JsonNode c2 = step("create-2", post("/api/conversations"), "{}");
        step("delete", delete("/api/conversations/" + c2.get("id").asText()), null);

        // side-panel stores, store level (the same records through each service's own store)
        JsonNode sample = M.readTree(getClass().getResourceAsStream("/parity/persistence/store-sample.json"));
        List<Map<String, Object>> runs = M.convertValue(sample.get("miniPathways"), List.class);
        for (Map<String, Object> run : runs) invoke(miniPathways, "saveRun", new Class<?>[]{Map.class}, run);
        Map<String, Object> completed = new java.util.LinkedHashMap<>(runs.get(0));
        completed.put("status", "complete");
        invoke(miniPathways, "saveRun", new Class<?>[]{Map.class}, completed);
        miniPathways.remove("conv-gone");
        for (Map<String, Object> result : (List<Map<String, Object>>) M.convertValue(sample.get("details"), List.class)) {
            invoke(details, "save", new Class<?>[]{Map.class}, result);
        }
        details.remove("conv-gone");
        Map<String, Object> objective = M.convertValue(sample.get("objective"), Map.class);
        Path folder = (Path) invoke(objectives, "folder", new Class<?>[]{String.class}, objective.get("conversationId"));
        invoke(objectives, "write", new Class<?>[]{Path.class, Object.class}, folder.resolve(objective.get("id") + ".json"), objective);

        // ---- compare
        JsonNode golden = M.readTree(getClass().getResourceAsStream("/parity/persistence/files.json"));
        Map<String, String> want = new TreeMap<>();
        golden.get("files").fields().forEachRemaining(e -> want.put(e.getKey(), e.getValue().asText()));
        Map<String, String> got = dataFiles();
        List<String> failures = new ArrayList<>();
        if (!want.keySet().equals(got.keySet())) failures.add("file set\n  ts:   " + want.keySet() + "\n  java: " + got.keySet());
        for (String f : want.keySet()) {
            if (!got.containsKey(f)) continue;
            String w = mask(want.get(f)), g = mask(got.get(f));
            if (!w.equals(g)) failures.add(f + "\n" + firstDiff(w, g));
        }
        JsonNode wantResponses = golden.get("responses");
        for (int i = 0; i < wantResponses.size(); i++) {
            JsonNode w = wantResponses.get(i);
            Map<String, Object> g = i < responses.size() ? responses.get(i) : Map.of();
            String wb = w.get("body").asText(), gb = String.valueOf(g.get("body"));
            if (wb.startsWith("{") || wb.startsWith("[")) wb = JsJson.stringify(M.readTree(wb));
            if (gb.startsWith("{") || gb.startsWith("[")) gb = JsJson.stringify(M.readTree(gb));
            String ws = w.get("name").asText() + " " + w.get("status").asInt() + " " + mask(wb);
            String gs = g.get("name") + " " + g.get("status") + " " + mask(gb);
            if (!ws.equals(gs)) failures.add("response " + w.get("name").asText() + "\n" + firstDiff(ws, gs));
        }
        if (!failures.isEmpty()) Files.writeString(Path.of("persistence-parity.txt").toAbsolutePath(), String.join("\n\n", failures), StandardCharsets.UTF_8);
        assertTrue(failures.isEmpty(), failures.size() + " differences (see target/test-work/persistence-parity.txt):\n"
            + String.join("\n\n", failures.subList(0, Math.min(5, failures.size()))));
        assertEquals(want.keySet(), got.keySet());
    }
}
