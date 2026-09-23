package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.service.GeminiService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Contract test: every server.ts route except POST /messages, called with the request bodies the
 * original client sends (src/services/api.ts, research/client.ts, miniPathway/client.ts,
 * objectives/ObjectiveContext.tsx, ProtocolV13Renderer.tsx, App.tsx), asserting the status and
 * JSON key sets/types server.ts returns for the same case. Gemini is mocked; the credentials are
 * test-only values. Runs only in surefire's target/test-work sandbox, so repo data is untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "auth.admin-username=contract-user", "auth.admin-password=contract-pass", "auth.secret=contract-secret",
    "gemini.api-key=test-key", "spring.datasource.url=", "cloudflare.account-id=", "cloudflare.api-token=",
    "warehouse.db-path="
})
class ApiContractTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private String token;

    @BeforeAll
    static void sandboxOnly() {
        assumeTrue(Path.of("").toAbsolutePath().endsWith("test-work"), "runs only in the surefire sandbox");
        FileSystemUtils.deleteRecursively(Path.of("data").toFile());
    }

    /** Mocked Gemini (a subclass: Mockito's inline mocking cannot instrument classes on this JDK). */
    static class FakeGemini extends GeminiService {
        @Override public boolean isConfigured() { return true; }
        @Override public Integer countTokens(String model, String text) { return 7; }
        @Override public TextResult generateText(String model, String prompt, Integer maxOutputTokens) {
            TextResult r = new TextResult();
            r.promptTokens = 11;
            r.candidatesTokens = 5;
            r.text = prompt.startsWith("Write a short title") ? "Career Change Plan."
                : prompt.startsWith("Extract 0-4") ? "[{\"text\":\"Works in IT\",\"category\":\"general\"},\"Lives in Sydney\"]"
                : prompt.startsWith("Check if the user") ? "[{\"fact\":\"a\",\"contradiction\":\"b\"}]"
                : prompt.startsWith("You are a pre-response") ? "{\"needsClarification\":true,\"bridgeMessage\":\"Why\",\"questions\":[{\"dimension\":\"d\",\"text\":\"Q?\",\"ui_type\":\"single_select\",\"required\":true,\"options\":[]}]}"
                : prompt.startsWith("You are a senior career coach") ? "{\"nodes\":[{\"id\":\"n0\",\"label\":\"Goal\",\"subtitle\":\"s\",\"description\":\"d\",\"node_type\":\"goal\"}]}"
                : prompt.startsWith("Career pathway builder") ? "{\"suggestions\":[{\"type\":\"skill\",\"label\":\"SQL\",\"subtitle\":\"x\",\"reason\":\"y\"}]}"
                : "Plain answer.";
            return r;
        }
    }

    @TestConfiguration
    static class FakeGeminiConfig {
        @Bean @Primary GeminiService fakeGemini() { return new FakeGemini(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        JsonNode login = call(post("/api/auth/login").content("{\"username\":\"contract-user\",\"password\":\"contract-pass\"}"), 200, false);
        assertKeys(login, "token");
        token = login.get("token").asText();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private MockHttpServletResponse raw(MockHttpServletRequestBuilder req, boolean auth) throws Exception {
        req.contentType(MediaType.APPLICATION_JSON);
        if (auth) req.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(req).andReturn();
        if (r.getRequest().isAsyncStarted()) r.getAsyncResult(20_000);
        return r.getResponse();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, int status, boolean auth) throws Exception {
        MockHttpServletResponse res = raw(req, auth);
        assertEquals(status, res.getStatus(), () -> safeBody(res));
        String body = res.getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, int status) throws Exception { return call(req, status, true); }

    private static String safeBody(MockHttpServletResponse res) {
        try { return res.getContentAsString(); } catch (Exception e) { return "?"; }
    }

    /** server.ts SSE frames: `data: {json}\n\n`. */
    private List<JsonNode> sse(MockHttpServletRequestBuilder req) throws Exception {
        MockHttpServletResponse res = raw(req, true);
        assertEquals(200, res.getStatus());
        assertTrue(String.valueOf(res.getContentType()).startsWith("text/event-stream"), res.getContentType());
        List<JsonNode> out = new ArrayList<>();
        for (String frame : res.getContentAsString().split("\n\n")) {
            if (frame.startsWith("data: ")) out.add(json.readTree(frame.substring(6)));
        }
        return out;
    }

    private static void assertKeys(JsonNode node, String... keys) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(new TreeSet<>(List.of(keys)), actual, node::toString);
    }

    private static final String[] CONVERSATION_KEYS = {"id", "title", "createdAt", "updatedAt", "model", "mode", "strategy",
        "preset", "responseMode", "thinkingLevel", "contextBudget", "recentTurnsToKeep", "careerContext", "summary",
        "summaryVersion", "systemPromptMode", "customSystemPrompt", "useInteractionsApi", "useFlashLiteUtility",
        "activeInteraction", "securityBreachCount", "activeSecurityPenalty", "messages", "compactionHistory"};
    /** The create literal has no activeInteraction key (a turn, DB load, demo or restore adds it). */
    private static final String[] CREATED_KEYS = java.util.Arrays.stream(CONVERSATION_KEYS).filter(k -> !k.equals("activeInteraction")).toArray(String[]::new);

    private String createConversation() throws Exception {
        // TokenLabContext.startNewConversation -> api.createConversation(title, model, strategy, extra)
        JsonNode conv = call(post("/api/conversations").content("{\"title\":\"New Career Exploration\",\"model\":\"gemini-3.5-flash\","
            + "\"strategy\":\"BASELINE\",\"mode\":\"VANILLA\",\"thinkingLevel\":\"medium\",\"responseMode\":\"vanilla\","
            + "\"contextBudget\":270000,\"recentTurnsToKeep\":100}"), 200);
        assertKeys(conv, CREATED_KEYS);
        return conv.get("id").asText();
    }

    // ---- auth ---------------------------------------------------------------------------------

    @Test
    void auth() throws Exception {
        assertKeys(call(post("/api/auth/login").content("{\"username\":\"contract-user\",\"password\":\"nope\"}"), 401, false), "error");
        assertEquals("{\"ok\":true}", call(post("/api/auth/logout"), 200, false).toString());
        assertTrue(call(get("/api/auth/check"), 200, true).get("authenticated").asBoolean());
        assertFalse(call(get("/api/auth/check"), 200, false).get("authenticated").asBoolean());
        assertEquals("{\"error\":\"Unauthorized\"}", call(get("/api/conversations"), 401, false).toString());
        JsonNode db = call(get("/api/db-status"), 200, false);
        assertKeys(db, "enabled", "ok", "conversations", "logs", "error");
        assertEquals("no pool", db.get("error").asText());
    }

    // ---- system / config ------------------------------------------------------------------------

    @Test
    void systemRoutes() throws Exception {
        assertKeys(call(get("/api/warehouse/status"), 200), "status", "scope", "sourcePolicy");
        JsonNode cat = call(get("/api/objectives/catalogue"), 200);
        assertKeys(cat, "version", "experimental", "objectives");
        assertTrue(cat.get("objectives").get(0).get("available").isBoolean());
        assertKeys(call(get("/api/protocol/info"), 200), "promptVersion", "protocolVersion", "schemaVersion", "promptHash",
            "schemaHash", "promptBytes", "targetRuntime", "configured", "trustedServicesCount");
        JsonNode caps = call(get("/api/config/capabilities"), 200);
        assertKeys(caps, "configured", "availableModels", "modelsList", "defaultModel", "supportsThinking", "supportsCachedTokens",
            "supportsInteractionsApi", "supportsExplicitCache", "geminiApiKeyPresent", "runtime");
        assertTrue(caps.get("availableModels").get(0).isTextual());
        assertKeys(call(get("/api/system-prompt"), 200), "content", "hash", "bytes", "filename", "version", "filepath");
        assertKeys(call(post("/api/system-prompt/reload"), 200), "ok", "hash", "bytes");
        String[] settings = {"systemPromptMode", "customSystemPrompt", "contextBudget", "recentTurnsToKeep", "strategy", "updatedAt"};
        JsonNode s = call(get("/api/shared-settings"), 200);
        List<String> withIdentity = new ArrayList<>(List.of(settings));
        withIdentity.addAll(List.of("defaultPromptHash", "defaultPromptBytes"));
        assertKeys(s, withIdentity.toArray(String[]::new));
        JsonNode put = call(put("/api/shared-settings").content("{\"systemPromptMode\":\"default\",\"contextBudget\":270000,\"recentTurnsToKeep\":100,\"strategy\":\"ADAPTIVE_HYBRID\"}"), 200);
        assertKeys(put, settings);
        assertTrue(put.get("updatedAt").isNumber());
        assertKeys(call(post("/api/shared-settings/reset-prompt"), 200), settings);
        // MicroToolRouter.ts: {task, text}
        assertEquals("{\"error\":\"Cloudflare credentials not configured\"}",
            call(post("/api/routing/llm").content("{\"task\":\"route\",\"text\":\"help with my CV\"}"), 503).toString());
    }

    // ---- utility model endpoints ----------------------------------------------------------------

    @Test
    void utilityRoutes() throws Exception {
        JsonNode facts = call(post("/api/extract-profile-facts").content("{\"userMessage\":\"I work in IT\",\"assistantMessage\":\"ok\",\"existingFacts\":[]}"), 200);
        assertKeys(facts, "facts");
        assertEquals("[{\"text\":\"Works in IT\",\"category\":\"general\"},{\"text\":\"Lives in Sydney\",\"category\":\"general\"}]", facts.get("facts").toString());
        assertEquals("{\"contradictions\":[{\"fact\":\"a\",\"contradiction\":\"b\"}]}",
            call(post("/api/detect-contradictions").content("{\"userMessage\":\"I live in Perth\",\"profileFacts\":[\"Lives in Sydney\"]}"), 200).toString());
        JsonNode pre = call(post("/api/pre-check").content("{\"userMessage\":\"move\",\"unresolvedContradictions\":[{\"fact\":\"a\",\"contradiction\":\"b\"}]}"), 200);
        assertKeys(pre, "needsClarification", "questions", "bridgeMessage");
        assertEquals("{\"needsClarification\":false}", call(post("/api/pre-check").content("{\"userMessage\":\"hi\",\"unresolvedContradictions\":[]}"), 200).toString());

        JsonNode board = call(post("/api/pathway/generate").content("{\"messages\":[{\"role\":\"user\",\"content\":\"I want to be a data analyst\"}],\"style\":\"structured\",\"answers\":{}}"), 200);
        assertKeys(board, "nodes", "edges");
        assertEquals("[]", board.get("edges").toString());
        assertEquals("{\"error\":\"No messages\"}", call(post("/api/pathway/generate").content("{\"messages\":[],\"style\":\"structured\"}"), 400).toString());
        JsonNode rec = call(post("/api/pathway/recommend").content("{\"nodes\":[{\"id\":\"n0\",\"label\":\"Goal\",\"type\":\"goal\"}],\"goalContext\":\"Data analyst\"}"), 200);
        assertKeys(rec, "suggestions");
        assertEquals("{\"suggestions\":[]}", call(post("/api/pathway/recommend").content("{\"nodes\":[]}"), 200).toString());
        assertEquals("{\"answer\":\"Plain answer.\"}", call(post("/api/pathway/explain")
            .content("{\"nodeLabel\":\"SQL\",\"nodeSubtitle\":\"x\",\"question\":\"Why?\",\"goalContext\":\"Data analyst\"}"), 200).toString());
        JsonNode stats = call(get("/api/pathway/stats"), 200);
        assertKeys(stats, "calls", "inputTokens", "outputTokens");
        assertKeys(call(get("/api/tokens/utility-stats"), 200), "whiteboard", "utility");
        JsonNode log = call(get("/api/tokens/log"), 200);
        assertKeys(log, "entries", "total");
        assertTrue(log.get("entries").isArray());
        JsonNode lifetime = call(get("/api/tokens/lifetime-stats"), 200);
        assertKeys(lifetime, "calls", "inputTokens", "outputTokens", "cachedTokens", "thinkingTokens", "costUsd", "whiteboard");
        assertKeys(lifetime.get("whiteboard"), "calls", "inputTokens", "outputTokens", "costUsd");
        JsonNode daily = call(get("/api/tokens/daily-cost"), 200);
        assertKeys(daily, "totalCostUsd", "source");
        assertTrue(daily.get("totalCostUsd").isNumber());
    }

    // ---- token counting / stats / benchmark -----------------------------------------------------

    @Test
    void tokenRoutes() throws Exception {
        String id = createConversation();
        JsonNode empty = call(post("/api/tokens/count").content("{\"message\":\"  \",\"conversationId\":\"" + id + "\"}"), 200);
        assertKeys(empty, "userMessageTokens", "estimatedTotalInputTokens", "exactCount", "breakdown", "sources");
        assertTrue(empty.get("exactCount").asBoolean());
        for (String fast : List.of("", ",\"fastEstimate\":true")) {
            JsonNode count = call(post("/api/tokens/count").content("{\"conversationId\":\"" + id + "\",\"message\":\"hello there\",\"model\":\"gemini-3.5-flash\"" + fast + "}"), 200);
            assertKeys(count, "userMessageTokens", "estimatedTotalInputTokens", "exactCount", "sources", "breakdown");
            assertKeys(count.get("sources"), "system", "career", "summary", "history", "user");
            assertKeys(count.get("breakdown"), "systemInstructionTokens", "careerContextTokens", "summaryTokens", "recentTurnsTokens",
                "currentMessageTokens", "totalAssembledTokens", "removedTokens", "includedSections", "excludedSections");
            assertKeys(count.get("breakdown").get("includedSections").get(0), "name", "description", "tokens", "preview");
            assertEquals(fast.isEmpty(), count.get("exactCount").asBoolean());
        }
        JsonNode stats = call(get("/api/tokens/session-stats"), 200);
        assertKeys(stats, "userFacingChatCalls", "totalUserInputTokens", "totalModelInputTokens", "totalUncachedInputTokens",
            "totalModelOutputTokens", "totalThinkingTokens", "totalCachedTokens", "totalUserFacingTokens", "compactionCalls",
            "compactionInputTokens", "compactionOutputTokens", "compactionTotalTokens", "baselineEstimatedTokens",
            "trueTotalConsumption", "tokensSaved", "netSavingsPercentage", "cacheHitRatio", "averageTokensPerTurn",
            "averageOutputPerTurn", "averageThinkingPerTurn");
        assertEquals("{\"status\":\"ok\"}", call(post("/api/tokens/session-reset"), 200).toString());
        JsonNode bench = call(post("/api/benchmark").content("{\"conversationId\":\"" + id + "\",\"prompt\":\"Help me\",\"model\":\"gemini-3.5-flash-lite\",\"strategies\":[\"BASELINE\",\"ADAPTIVE_HYBRID\"],\"isLive\":false}"), 200);
        assertKeys(bench, "results");
        JsonNode row = bench.get("results").get(1);
        assertKeys(row, "strategy", "label", "model", "mode", "inputTokens", "outputTokens", "thinkingTokens", "cachedTokens",
            "totalTokens", "latencyMs", "ttftMs", "generationMs", "compactionCost", "responsePreview", "retainedContextTokens",
            "schemaValid", "sources", "notes");
        assertTrue(row.get("thinkingTokens").isNull() && row.get("schemaValid").isNull());
        assertEquals("ADAPTIVE_HYBRID (Modelled Estimate)", row.get("label").asText());
    }

    // ---- conversations --------------------------------------------------------------------------

    @Test
    void conversationCrud() throws Exception {
        String id = createConversation();
        JsonNode fetched = call(get("/api/conversations/" + id), 200);
        assertKeys(fetched, CREATED_KEYS);
        assertTrue(fetched.get("createdAt").isIntegralNumber() && fetched.get("updatedAt").isIntegralNumber());
        assertEquals("gemini-3.5-flash", fetched.get("model").asText());
        assertEquals("VANILLA", fetched.get("mode").asText());
        assertEquals("", fetched.get("summary").asText());
        assertFalse(fetched.has("activeInteraction"));
        assertKeys(fetched.get("careerContext"), "facts", "goals", "constraints", "decisions", "openThreads");

        JsonNode list = call(get("/api/conversations"), 200);
        JsonNode listed = null;
        for (JsonNode c : list) if (c.get("id").asText().equals(id)) listed = c;
        assertNotNull(listed);
        assertKeys(listed, CREATED_KEYS);

        // updateConversation(id, payload): the Advanced Lab sends the in-memory-only generation settings.
        JsonNode updated = call(put("/api/conversations/" + id).content("{\"title\":\"Renamed\",\"useMultiTurn\":true,\"temperature\":0.7,\"topP\":0.9,\"maxOutputTokens\":2048}"), 200);
        List<String> withSettings = new ArrayList<>(List.of(CREATED_KEYS));
        withSettings.addAll(List.of("useMultiTurn", "temperature", "topP", "maxOutputTokens"));
        assertKeys(updated, withSettings.toArray(String[]::new));
        assertEquals("Renamed", updated.get("title").asText());
        assertEquals(0.7, updated.get("temperature").asDouble());
        assertEquals(2048, updated.get("maxOutputTokens").asInt());

        assertKeys(call(post("/api/conversations/" + id + "/reset-memory"), 200), withSettings.toArray(String[]::new));
        assertEquals("{\"error\":\"No messages to title\"}", call(post("/api/conversations/" + id + "/generate-title"), 400).toString());
        assertEquals("{\"status\":\"ok\"}", call(post("/api/conversations/" + id + "/feedback?messageId=missing").content("{\"type\":\"positive\",\"timestamp\":1}"), 200).toString());

        assertEquals("{\"error\":\"Conversation not found\"}", call(get("/api/conversations/nope"), 404).toString());
        assertEquals("{\"error\":\"Conversation not found\"}", call(put("/api/conversations/nope").content("{}"), 404).toString());
        assertNull(call(delete("/api/conversations/" + id), 204));
        assertEquals("{\"error\":\"Conversation not found\"}", call(delete("/api/conversations/" + id), 404).toString());
    }

    @Test
    void demoTitleFeedbackAndRestore() throws Exception {
        JsonNode demo = call(post("/api/conversations/load-demo"), 200);
        assertKeys(demo, CONVERSATION_KEYS);
        assertEquals("AUTO", demo.get("mode").asText());
        assertEquals("question", demo.get("activeInteraction").get("kind").asText());
        JsonNode user = demo.get("messages").get(0), assistant = demo.get("messages").get(1);
        assertKeys(user, "id", "role", "content", "createdAt");
        assertKeys(assistant, "id", "role", "content", "createdAt"); // the envelope is only in content
        assertEquals("1.3", json.readTree(assistant.get("content").asText()).get("schema_version").asText());
        String id = demo.get("id").asText();

        assertEquals("{\"title\":\"Career Change Plan\"}", call(post("/api/conversations/" + id + "/generate-title"), 200).toString());
        String feedback = "{\"type\":\"positive\",\"comment\":\"Great\",\"timestamp\":1700000000000}";
        call(post("/api/conversations/" + id + "/feedback?messageId=" + assistant.get("id").asText()).content(feedback), 200);
        JsonNode after = call(get("/api/conversations/" + id), 200);
        assertEquals("Career Change Plan", after.get("title").asText());
        assertEquals(json.readTree(feedback), after.get("messages").get(1).get("feedback"));

        // restoreConversation(conv): the client's localStorage copy (original field names, epoch ms).
        String restoreId = "conv-restore-" + System.nanoTime();
        JsonNode restored = call(post("/api/conversations/restore").content("{\"id\":\"" + restoreId + "\",\"title\":\"Old chat\","
            + "\"createdAt\":1700000000000,\"updatedAt\":1700000001000,\"model\":\"gemini-3.5-flash\",\"mode\":\"AUTO\","
            + "\"summary\":\"s\",\"summaryVersion\":2,\"messages\":[{\"id\":\"u1\",\"role\":\"user\",\"content\":\"hi\",\"createdAt\":1700000000500}]}"), 200);
        assertKeys(restored, CONVERSATION_KEYS);
        assertEquals(1700000000000L, restored.get("createdAt").asLong());
        assertEquals("s", restored.get("summary").asText());
        assertEquals("{\"id\":\"u1\",\"role\":\"user\",\"content\":\"hi\",\"createdAt\":1700000000500}", restored.get("messages").get(0).toString());
        assertEquals("{\"error\":\"Missing conversation id\"}", call(post("/api/conversations/restore").content("{}"), 400).toString());

        // ProtocolV13Renderer executeServiceAction: body is the action itself.
        JsonNode unknown = call(post("/api/conversations/" + id + "/actions/not_real/execute").content("{\"action_id\":\"not_real\"}"), 400);
        assertKeys(unknown, "success", "executed", "error");
        JsonNode known = call(post("/api/conversations/" + id + "/actions/rmo_explore_courses/execute").content("{\"action_id\":\"rmo_explore_courses\"}"), 200);
        assertKeys(known, "success", "executed", "connected", "actionId", "title", "message");
        assertEquals("{\"error\":\"Conversation not found\"}", call(post("/api/conversations/nope/actions/x/execute").content("{}"), 404).toString());
    }

    // ---- workspaces / mini pathway / details ----------------------------------------------------

    @Test
    void workspaceRoutes() throws Exception {
        String id = createConversation();
        assertEquals("[]", call(get("/api/conversations/" + id + "/objectives"), 200).toString());
        assertEquals("{\"error\":\"Conversation not found.\"}", call(get("/api/conversations/nope/objectives"), 404).toString());
        assertEquals("{\"error\":\"Unknown workspace action.\"}", call(post("/api/conversations/" + id + "/objectives/fly").content("{}"), 404).toString());
        assertKeys(call(post("/api/conversations/" + id + "/objectives/dismiss").content("{\"sourceMessageId\":\"m1\"}"), 422), "error");

        assertEquals("[]", call(get("/api/conversations/" + id + "/mini-pathway"), 200).toString());
        assertEquals("{\"error\":\"Conversation not found.\"}", call(post("/api/conversations/nope/mini-pathway").content("{}"), 404).toString());
        // miniPathway/client.ts generateMiniPathway: {sourceMessageId, mode, hint, location}
        List<JsonNode> frames = sse(post("/api/conversations/" + id + "/mini-pathway")
            .content("{\"sourceMessageId\":\"m1\",\"mode\":\"manual\",\"hint\":{\"status\":\"abstained\",\"reason\":\"not-pathway\"},\"location\":\"\"}"));
        assertEquals("[{\"type\":\"error\",\"error\":\"This answer has changed. Please use the latest response.\",\"status\":409}]", frames.toString());

        assertEquals("[]", call(get("/api/conversations/" + id + "/details"), 200).toString());
        assertEquals("{\"error\":\"Enter the course or option and your question.\"}", call(post("/api/conversations/" + id + "/details").content("{}"), 400).toString());
        // research/client.ts: the DetailRequest
        assertEquals("{\"error\":\"Choose a completed, valid answer to explore.\"}", call(post("/api/conversations/" + id + "/details")
            .content("{\"parentMessageId\":\"m1\",\"target\":\"Cert IV\",\"question\":\"How long?\",\"studyYear\":\"\",\"location\":\"\"}"), 400).toString());
    }
}
