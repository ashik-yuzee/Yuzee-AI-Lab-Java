package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatRequest;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import com.yuzee.tokenlab.protocol.SecurityStateService;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.EmitterCapture;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the scenarios recorded by src/test/parity/sse.parity.mts (the original server.ts handler with a scripted
 * Gemini) through ChatTurnService with the same scripted Gemini, and requires the same HTTP status, JSON error
 * bodies, SSE event sequence and payloads (byte for byte after masking clock values and random ids), and the same
 * provider requests.
 */
class ChatSseParityTest {

    private static final ObjectMapper M = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern VOLATILE = Pattern.compile("\"(requestReceivedAt|preProviderLatencyMs|conversationLoadMs|userEventValidationMs|memoryAssemblyMs|requestAssemblyMs|providerRequestStartedAt|providerTtftMs|providerGenerationDurationMs|providerCompletedAt|validationDurationMs|validationCompletedAt|totalLatencyMs|latencyMs|timestamp|ttlMs)\":\\d+");
    private static final Pattern FRAME = Pattern.compile("^event: (.*)\\ndata: ([\\s\\S]*)$");

    private JsonNode step;
    private final List<Object> provider = Collections.synchronizedList(new ArrayList<>());

    private static String normalize(String s) {
        return VOLATILE.matcher(s).replaceAll("\"$1\":0").replaceAll("msg-\\d+-[a-z0-9]*", "msg-ID").replaceAll("req-\\d+-[a-z0-9]*", "req-ID")
            .replaceAll("\"compactionEventId\":\"[^\"]*\"", "\"compactionEventId\":\"ID\"");
    }

    /** Clock-derived stored-message ids, their timestamps and the context revision hash over them (any escaping depth). */
    private static String mask(String s) {
        return s.replaceAll("user-\\d{13}(-[a-z0-9]+)?", "user-ID").replaceAll("(\\\\*\"at\\\\*\":)\\d{13}", "$10")
            .replaceAll("(\\\\*\"revision\\\\*\":\\\\*\")[0-9a-f]{16}", "$1REV");
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode canonical(JsonNode n) {
        if (n == null) return M.nullNode();
        if (n.isNumber() && n.asDouble() == Math.rint(n.asDouble()) && Math.abs(n.asDouble()) < 1e15) return LongNode.valueOf(n.asLong());
        if (n.isObject()) {
            ObjectNode o = M.createObjectNode();
            n.fields().forEachRemaining(e -> o.set(e.getKey(), canonical(e.getValue())));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = M.createArrayNode();
            n.forEach(x -> a.add(canonical(x)));
            return a;
        }
        return n;
    }

    private ObjectNode config(JsonNode generationConfig) {
        ObjectNode c = generationConfig.deepCopy();
        if (c.has("responseSchema")) c.put("responseSchema", sha(JsJson.stringify(c.get("responseSchema"))));
        return c;
    }

    private Map<String, Object> streamRecord(String model, ObjectNode body) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("kind", "stream");
        rec.put("model", model);
        rec.put("contents", body.get("contents"));
        rec.put("system", sha(body.path("systemInstruction").path("parts").path(0).path("text").asText("")));
        rec.put("config", config(body.get("generationConfig")));
        return rec;
    }

    private static GeminiService.GeminiHttpException error(JsonNode o) {
        return new GeminiService.GeminiHttpException(o.get("error").get("status").asInt(), o.get("error").get("message").asText());
    }

    /** Scripted Gemini: stream opens and reviews come from the scenario step; summaries get a fixed reply. */
    private GeminiService gemini() {
        return new GeminiService() {
            @Override public boolean isConfigured() { return true; }

            @Override public OpenStream openStream(String model, ObjectNode body) throws java.io.IOException {
                provider.add(streamRecord(model, body));
                ArrayNode opens = (ArrayNode) step.get("opens");
                if (opens == null || opens.isEmpty()) throw new GeminiHttpException(400, "unscripted stream");
                JsonNode o = opens.remove(0);
                if (o.has("error")) throw error(o);
                return new OpenStream(null, null) {
                    @Override public void forEachChunk(java.util.function.Consumer<JsonNode> onChunk, java.util.function.BooleanSupplier stop) throws java.io.IOException {
                        for (JsonNode c : o.get("chunks")) {
                            if (stop.getAsBoolean()) break;
                            onChunk.accept(M.readTree(c.toString()));
                        }
                    }
                    @Override public void cancel() { }
                    @Override public void close() { }
                };
            }

            @Override public JsonNode generateContentRaw(String model, ObjectNode body, long timeoutMs) throws java.io.IOException {
                if (!body.path("generationConfig").has("responseSchema")) {
                    return M.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"- Goal: nursing\"}]}}],\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":10,\"totalTokenCount\":60}}");
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("kind", "review");
                rec.put("model", model);
                rec.put("contents", sha(body.path("contents").path(0).path("parts").path(0).path("text").asText()));
                rec.put("system", sha(body.path("systemInstruction").path("parts").path(0).path("text").asText()));
                rec.put("config", config(body.get("generationConfig")));
                provider.add(rec);
                ArrayNode reviews = (ArrayNode) step.get("reviews");
                if (reviews == null || reviews.isEmpty()) throw new GeminiHttpException(400, "unscripted review");
                JsonNode r = reviews.remove(0);
                if (r.has("error")) throw error(r);
                ObjectNode resp = M.createObjectNode();
                ObjectNode cand = resp.putArray("candidates").addObject();
                cand.putObject("content").putArray("parts").addObject().put("text", r.get("text").asText());
                cand.put("finishReason", r.path("finishReason").asText("STOP"));
                if (r.has("usageMetadata")) resp.set("usageMetadata", r.get("usageMetadata"));
                return resp;
            }
        };
    }

    private ChatTurnService chatTurnService() throws Exception {
        Map<String, Conversation> store = new ConcurrentHashMap<>();
        ConversationService conversations = new ConversationService(null) {
            @Override public Optional<Conversation> findById(String id) { return Optional.ofNullable(store.get(id)); }
            @Override public Conversation save(Conversation c) { store.put(c.getId(), c); return c; }
            @Override public Conversation put(Conversation c) { store.put(c.getId(), c); return c; }
            @Override public Conversation persist(Conversation c) { return c; }
        };
        SystemPromptCacheManager cache = new SystemPromptCacheManager() {
            @Override public String getCacheForModel(String modelId, String systemInstruction, String promptHash) { return null; }
            @Override public Map<String, Object> getStatus(String modelId) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("active", false);
                m.put("creating", false);
                return m;
            }
        };
        ObjectiveService objectives = new ObjectiveService(null, null, null, null, Files.createTempDirectory("objectives").toString());
        GeminiModelRegistry registry = new GeminiModelRegistry();
        TokenService tokens = new TokenService(registry);
        MultiTurnRequestBuilder builder = new MultiTurnRequestBuilder();
        SystemPromptService prompts = new SystemPromptService();
        RequestAssemblerService assembler = new RequestAssemblerService(prompts, builder, tokens);
        assembler.init();
        SecurityStateService security = new SecurityStateService();
        ProtocolValidator validator = new ProtocolValidator(security);
        BgeGateService bge = new BgeGateService();
        RoutingPolicyService routing = new RoutingPolicyService(bge);
        // WarehouseService/MiniPathwayService/DetailResearchService are not reached by these scenarios (a null
        // warehouse lookup fails inside the handler's .catch(() => null) equivalent).
        return new ChatTurnService(conversations, new ConversationMemoryService(), builder, assembler, cache, new ProviderRecoveryService(),
            new ReviewRetryService(), new TeachingAnswerReviewService(), validator, security, gemini(), registry, tokens,
            new FileConversationLogService(null), new OalaService(prompts), routing, new SkillSuggestionService(routing, bge, validator),
            new TurnNeedsService(bge), new HelpEvidenceService(), objectives, new ObjectiveWorkspacePolicyService(), (WarehouseService) null,
            new PathwayContextService(), new SharedSettingsService(), (MiniPathwayService) null, (DetailResearchService) null);
    }

    private static List<Map<String, String>> frames(String text) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String f : text.split("\n\n")) {
            if (f.isEmpty()) continue;
            Matcher m = FRAME.matcher(f);
            Map<String, String> e = new LinkedHashMap<>();
            if (m.matches()) { e.put("event", m.group(1)); e.put("data", normalize(m.group(2))); } else e.put("raw", f);
            out.add(e);
        }
        return out;
    }

    @Test
    void chatTurnSseMatchesServerTs() throws Exception {
        JsonNode scenarios = M.readTree(getClass().getResourceAsStream("/parity/chat-sse.json"));
        ChatTurnService chat = chatTurnService();
        List<String> failures = new ArrayList<>();
        for (JsonNode sc : scenarios) {
            for (int i = 0; i < sc.get("steps").size(); i++) {
                step = sc.get("steps").get(i).deepCopy();
                provider.clear();
                JsonNode want = sc.get("results").get(i);
                ChatRequest body = M.treeToValue(step.get("body"), ChatRequest.class);
                MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/conversations/" + sc.get("conv").asText() + "/messages");
                MockHttpServletResponse res = new MockHttpServletResponse();
                ResponseBodyEmitter emitter = chat.handle(sc.get("conv").asText(), body, req, res);
                Map<String, Object> got = new LinkedHashMap<>();
                if (emitter != null) {
                    StringBuffer out = new StringBuffer();
                    assertTrue(EmitterCapture.attach(emitter, out).await(60, TimeUnit.SECONDS), "stream did not end");
                    got.put("status", res.getStatus());
                    got.put("sse", true);
                    got.put("events", frames(out.toString()));
                } else {
                    got.put("status", res.getStatus());
                    got.put("sse", false);
                    got.put("body", res.getContentAsString(StandardCharsets.UTF_8));
                }
                Thread.sleep(300); // post-response summarisation
                got.put("provider", new ArrayList<>(provider));
                ObjectNode expected = (ObjectNode) want.deepCopy();
                if (expected.path("events").isMissingNode()) expected.remove("events");
                String w = mask(M.writeValueAsString(canonical(expected))), g = mask(M.writeValueAsString(canonical(M.valueToTree(got))));
                if (!w.equals(g)) failures.add(sc.get("name").asText() + " step " + i + "\n" + firstDiff(w, g));
            }
        }
        if (!failures.isEmpty()) Files.writeString(Path.of("chat-sse-parity.txt").toAbsolutePath(), String.join("\n\n", failures), StandardCharsets.UTF_8);
        assertTrue(failures.isEmpty(), failures.size() + " steps differ (see target/test-work/chat-sse-parity.txt):\n" + String.join("\n\n", failures.subList(0, Math.min(6, failures.size()))));
    }

    private static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "  at " + i + "\n  ts:   " + a.substring(Math.max(0, i - 150), Math.min(a.length(), i + 250))
            + "\n  java: " + b.substring(Math.max(0, i - 150), Math.min(b.length(), i + 250));
    }
}
