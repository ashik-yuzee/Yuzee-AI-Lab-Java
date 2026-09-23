package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.model.DialogueTurn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the inputs recorded by src/test/parity/request-assembly.parity.mts (which ran them through the original
 * TypeScript) through the Java request assembly and requires identical outputs: thinking classifier, bypass
 * classifier, schema sanitiser, system instruction, user-event formatting, memory selection/eviction, multi-turn
 * contents, and the chat-path helpers of server.ts. Strings compare exactly; key order counts.
 */
class RequestAssemblyParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final TokenService tokens = new TokenService(new GeminiModelRegistry());
    private final MultiTurnRequestBuilder builder = new MultiTurnRequestBuilder();
    private final RequestAssemblerService assembler = new RequestAssemblerService(new SystemPromptService(), builder, tokens);
    private final ConversationMemoryService memory = new ConversationMemoryService();
    private final ChatTurnService chat = newChatTurnService();

    private static ChatTurnService newChatTurnService() {
        var ctor = ChatTurnService.class.getConstructors()[0];
        try {
            return (ChatTurnService) ctor.newInstance(new Object[ctor.getParameterCount()]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void javaAssemblyMatchesTypeScript() throws Exception {
        assembler.init();
        JsonNode golden = M.readTree(getClass().getResourceAsStream("/parity/request-assembly.json"));
        JsonNode cases = golden.get("cases"), results = golden.get("results");
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i), expected = results.get(i);
            String fn = c.get("fn").asText();
            String want = expected.has("ok") ? canonical(M.readTree(expected.get("ok").asText())) : expected.toString();
            String got;
            try {
                Object v = run(fn, c.get("args"));
                got = v == UNDEFINED ? "{\"undef\":true}" : canonical(M.valueToTree(v));
            } catch (Exception e) {
                got = expected.has("err") ? expected.toString() : "{\"err\":\"" + e + "\"}";
            }
            if (!want.equals(got)) failures.add("#" + i + " " + fn + " " + clip(c.get("args").toString()) + "\n   ts:   " + clip(want) + "\n   java: " + clip(got) + firstDiff(want, got));
        }
        if (!failures.isEmpty()) {
            Files.writeString(Path.of("request-assembly-parity.txt").toAbsolutePath(), String.join("\n", failures), StandardCharsets.UTF_8);
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + cases.size() + " cases differ (see target/test-work/request-assembly-parity.txt):\n"
            + String.join("\n", failures.subList(0, Math.min(15, failures.size()))));
    }

    private static final Object UNDEFINED = new Object();

    private static String clip(String s) { return s.length() > 400 ? s.substring(0, 400) + "..." : s; }

    private static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "\n   at " + i + ": ts=" + clip(a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 80)))
            + " | java=" + clip(b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 80)));
    }

    /** Same textual form for 1 and 1.0 (JS has one number type); everything else is kept, including key order. */
    private static String canonical(JsonNode n) throws Exception {
        return M.writeValueAsString(normalize(n));
    }

    private static JsonNode normalize(JsonNode n) {
        if (n == null) return M.nullNode();
        if (n.isNumber() && n.asDouble() == Math.rint(n.asDouble()) && Math.abs(n.asDouble()) < 1e15) return LongNode.valueOf(n.asLong());
        if (n.isObject()) {
            ObjectNode o = M.createObjectNode();
            n.fields().forEachRemaining(e -> o.set(e.getKey(), normalize(e.getValue())));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = M.createArrayNode();
            n.forEach(x -> a.add(normalize(x)));
            return a;
        }
        return n;
    }

    // ---------------------------------------------------------------- inputs

    private static boolean undef(JsonNode n) { return n == null || n.isMissingNode() || (n.isObject() && n.has("__undef")); }
    private static String str(JsonNode n) { return undef(n) || n.isNull() ? null : n.asText(); }
    private static JsonNode node(JsonNode n) { return undef(n) || n.isNull() ? null : n; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode n) { return undef(n) || n.isNull() ? null : M.convertValue(n, LinkedHashMap.class); }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> messages(JsonNode arr) {
        List<ChatMessage> out = new ArrayList<>();
        for (JsonNode m : arr) {
            ChatMessage c = new ChatMessage();
            c.setId(m.get("id").asText());
            c.setRole(m.get("role").asText());
            c.setContent(m.get("content").asText());
            c.setTimestamp(m.has("createdAt") ? Instant.ofEpochMilli(m.get("createdAt").asLong()) : null);
            if (m.has("telemetry")) c.setTelemetry(M.convertValue(m.get("telemetry"), Map.class));
            if (m.has("structuredResponse")) c.setParsedResponse(M.convertValue(m.get("structuredResponse"), Map.class));
            if (m.has("objectiveTransfer")) c.setObjectiveTransfer(M.convertValue(m.get("objectiveTransfer"), Map.class));
            out.add(c);
        }
        return out;
    }

    private MemoryResult memory(JsonNode msgs, int budget, int keep, String strategy, String summary, String query) {
        MemoryResult r = memory.assembleMemory(messages(msgs), budget, keep, strategy, summary, query);
        if (r.compactionMetrics != null) {
            r.compactionMetrics.timestamp = 0;
            r.compactionMetrics.compactionEventId = r.compactionMetrics.compactionEventId.replaceAll("[0-9]+$", "N");
        }
        return r;
    }

    private JsonNode fixtureMessages;

    private List<DialogueTurn> turnsFor(String key) {
        if (key.equals("none")) return List.of();
        if (key.equals("all")) return ConversationMemoryService.groupIntoTurns(messages(fixtureMessages));
        if (key.equals("mem")) return memory(fixtureMessages, 100000, 100, "ADAPTIVE_HYBRID", "Prior summary", "python").keptTurns;
        String[] p = key.split(":");
        return memory(fixtureMessages, Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[1], "Prior summary text here", "sydney data analyst python salaries").keptTurns;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != UNDEFINED) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private Object invoke(String name, Object... args) throws Exception {
        for (Method m : ChatTurnService.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : chat, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    private Object run(String fn, JsonNode a) throws Exception {
        switch (fn) {
            case "resolveThinkingConfig": {
                var r = assembler.resolveThinkingConfig(str(a.get(0)), str(a.get(1)), str(a.get(2)));
                return obj("thinkingConfig", r.thinkingConfig == null ? UNDEFINED : r.thinkingConfig, "appliedThinkingLevel", r.appliedThinkingLevel, "numericBudget", r.numericBudget);
            }
            case "classifyUserMessage":
                return assembler.classifyUserMessage(str(a.get(0)), a.get(1).path("hasConversation").asBoolean(), a.get(1).path("hasActiveQuestion").asBoolean());
            case "bypassCopy": return RequestAssemblerService.bypassCopy(str(a.get(0)));
            case "formatUserEvent": return assembler.formatUserEvent(str(a.get(0)), node(a.get(1)), str(a.get(2)));
            case "formatCareerContext": return assembler.formatCareerContext(map(a.get(0)));
            case "normalizeModeCapitalization": return assembler.normalizeModeCapitalization(str(a.get(0)));
            case "sanitizeSchema": return assembler.sanitizeSchemaForGemini(a.get(0));
            case "geminiResponseSchema": return assembler.getSanitizedResponseSchema();
            case "warehouseInstruction": {
                var f = ChatTurnService.class.getDeclaredField("warehouseInstruction");
                f.setAccessible(true);
                return f.get(chat);
            }
            case "promptHash": return assembler.getPromptHash();
            case "schemaHash": return assembler.getSchemaHash();
            case "estimateTokens": {
                List<Integer> out = new ArrayList<>();
                for (JsonNode x : a.get(0)) out.add(tokens.estimate(str(x)));
                return out;
            }
            case "groupIntoTurns": fixtureMessages = a.get(0); return ConversationMemoryService.groupIntoTurns(messages(a.get(0)));
            case "formatAssistantMessageRich": return builder.formatAssistantMessageRich(str(a.get(0)));
            case "formatAssistantMessageForContext": return ConversationMemoryService.formatAssistantMessageForContext(str(a.get(0)));
            case "assembleMemory":
                return memory(a.get(0), a.get(1).asInt(), a.get(2).asInt(), str(a.get(3)), str(a.get(4)), str(a.get(5)));
            case "buildMultiTurnContents": {
                ArrayNode c = builder.buildMultiTurnContents(str(a.get(0)), str(a.get(1)), turnsFor(str(a.get(2))), str(a.get(3)), a.get(4).asBoolean());
                return obj("contents", c, "tokens", builder.estimateContentsTokens(c));
            }
            case "shortReplyGuidance": {
                List<Map.Entry<String, String>> h = new ArrayList<>();
                for (JsonNode m : a.get(1)) h.add(Map.entry(m.get("role").asText(), m.get("content").asText()));
                return assembler.shortReplyGuidance(str(a.get(0)), h, a.get(2).asBoolean());
            }
            case "assembleRequest": return assemble(a.get(0));
            case "sharedConversationContext": {
                Conversation conv = new Conversation();
                conv.setId(a.get(0).get("id").asText());
                conv.setMessages(messages(a.get(0).get("messages")));
                List<Map<String, Object>> sessions = M.convertValue(a.get(1), List.class);
                return invoke("sharedConversationContext", conv, sessions, str(a.get(2)));
            }
            case "workspaceQuestionOwner": return invoke("workspaceQuestionOwner", M.convertValue(a.get(0), List.class), str(a.get(1)));
            case "readActivityContext": {
                JsonNode c = a.get(0).path("state").get("activity_context");
                return invoke("readActivityContext", node(c), M.convertValue(a.get(1), List.class));
            }
            case "splitGeminiStreamChunk": {
                var p = ChatTurnService.splitGeminiStreamChunk(node(a.get(0)));
                return obj("hasThoughtSummary", p.hasThoughtSummary(), "text", p.text(), "finishReason", p.finishReason() == null ? UNDEFINED : p.finishReason());
            }
            case "formatUserEventRaw": return assembler.formatUserEvent(str(a.get(0)), M.readTree(a.get(1).asText()), str(a.get(2)));
            case "needsWarehouse": return new com.yuzee.tokenlab.service.warehouse.WarehouseService(null, null, null).needsWarehouse(str(a.get(0)), str(a.get(1)));
            case "objectiveWire": return ObjectiveSchema.WIRE_INSTRUCTION;
            case "workspaceInstructions": {
                Method m = ObjectiveService.class.getDeclaredMethod("workspaceInstructions", JsonNode.class);
                m.setAccessible(true);
                return m.invoke(objectives(), catalogue().findWorkbookEntry(a.get(0).asText()));
            }
            case "contractWarnings": {
                Map<String, Object> out = new LinkedHashMap<>();
                for (JsonNode o : catalogue().workbookById().values()) {
                    var r = ObjectiveCatalogueService.compileResult(o.path("qa_output_contract_v2").asText(""));
                    out.put(o.path("tool_id").asText(), obj("keys", r.completionKeys, "warnings", r.warnings));
                }
                return out;
            }
            case "compileResultDsl": {
                var r = ObjectiveSchema.compileResult(a.get(0).asText());
                return obj("schema", r.schema, "completionKeys", r.completionKeys, "warnings", r.warnings);
            }
            case "plannerSchema": return ObjectiveSchema.plannerSchema(catalogue().findWorkbookEntry(a.get(0).asText()), a.get(1).get("confirmed_facts"));
            case "objectiveProviderSchema":
                return ObjectiveSchema.objectiveProviderSchema(ObjectiveSchema.plannerSchema(catalogue().findWorkbookEntry(a.get(0).asText()), a.get(1).get("confirmed_facts")));
            case "validateOutput": return ObjectiveSchema.validateOutput(a.get(1).asText(), catalogue().findWorkbookEntry(a.get(0).asText()), a.get(2)).toMap();
            case "objectiveStart": return objectiveStart(a.get(0), a.get(1), a.get(2));
            default: throw new IllegalArgumentException("unknown fn " + fn);
        }
    }

    private ObjectiveCatalogueService catalogue;

    private ObjectiveCatalogueService catalogue() {
        if (catalogue == null) {
            catalogue = new ObjectiveCatalogueService();
            catalogue.init();
        }
        return catalogue;
    }

    private ObjectiveService objectives() { return objectives(Path.of("objective-parity")); }

    private ObjectiveService objectives(Path root) {
        var warehouse = new com.yuzee.tokenlab.service.warehouse.WarehouseService(null, null, null) {
            @Override public com.yuzee.tokenlab.model.warehouse.WarehousePack retrieve(com.yuzee.tokenlab.model.warehouse.WarehouseInput input) {
                var pack = com.yuzee.tokenlab.model.warehouse.WarehousePack.of("NOT_NEEDED");
                pack.setRetrievedAt("2026-01-01T00:00:00.000Z");
                return pack;
            }
        };
        return new ObjectiveService(null, catalogue(), new ObjectiveWorkspacePolicyService(), warehouse, root.toString());
    }

    /** service.ts ObjectiveService.start() with a stubbed model: the requests it sends and the session it saves. */
    @SuppressWarnings("unchecked")
    private Object objectiveStart(JsonNode convNode, JsonNode body, JsonNode outputs) throws Exception {
        Path root = Files.createTempDirectory("objparity-");
        ObjectiveService svc = objectives(root);
        List<JsonNode> payloads = new ArrayList<>();
        Deque<JsonNode> queue = new ArrayDeque<>();
        outputs.forEach(queue::add);
        svc.call = (request, timeout) -> {
            payloads.add(request.deepCopy());
            if (queue.isEmpty()) throw new IllegalStateException("No stub output");
            return queue.poll();
        };
        Conversation conv = new Conversation();
        conv.setId(convNode.get("id").asText());
        conv.setMessages(messages(convNode.get("messages")));
        try {
            JsonNode s = M.valueToTree(svc.start(conv, M.convertValue(body, LinkedHashMap.class)));
            Map<String, Object> session = new LinkedHashMap<>();
            for (String k : List.of("objectiveId", "label", "sourceMessageId", "revision", "interactionCount", "state", "plan", "context", "answers", "activation", "routing", "autoHandoff")) {
                if (s.has(k)) session.put(k, s.get(k));
            }
            return obj("payloads", payloads, "session", session);
        } catch (RuntimeException e) {
            return obj("payloads", payloads, "err", e.getMessage());
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private Object assemble(JsonNode p) throws Exception {
        RequestAssemblerService.Params q = new RequestAssemblerService.Params();
        q.model = str(p.get("model"));
        q.messageText = str(p.get("messageText"));
        q.microToolInstruction = str(p.get("microToolInstruction"));
        q.oalaInstruction = str(p.get("oalaInstruction"));
        q.userEvent = node(p.get("userEvent"));
        q.careerContext = map(p.get("careerContext"));
        q.summaryText = str(p.get("summaryText"));
        q.recentHistoryText = str(p.get("recentHistoryText"));
        q.responseMode = str(p.get("responseMode"));
        q.thinkingLevel = str(p.get("thinkingLevel"));
        q.customSystemPrompt = str(p.get("customSystemPrompt"));
        q.systemPromptMode = str(p.get("systemPromptMode"));
        q.temperature = node(p.get("temperature")) == null ? null : p.get("temperature").asDouble();
        q.topP = node(p.get("topP")) == null ? null : p.get("topP").asDouble();
        q.maxOutputTokens = node(p.get("maxOutputTokens")) == null ? null : p.get("maxOutputTokens").asInt();
        q.useMultiTurn = node(p.get("useMultiTurn")) == null ? null : p.get("useMultiTurn").asBoolean();
        q.useStructuredOutput = node(p.get("useStructuredOutput")) == null ? null : p.get("useStructuredOutput").asBoolean();
        if (p.has("keptTurns")) q.keptTurns = turnsFor(p.get("keptTurns").asText());
        AssembledRequest r = assembler.assembleRequest(q);
        String si = sha(r.systemInstruction) + ":" + r.systemInstruction.length() + ":" + r.systemInstruction.substring(Math.max(0, r.systemInstruction.length() - 40));
        ObjectNode config = M.createObjectNode();
        config.put("systemInstruction", si);
        config.setAll(r.geminiConfig.deepCopy());
        if (config.has("responseSchema")) config.put("responseSchema", sha(M.writeValueAsString(config.get("responseSchema"))));
        return obj("model", r.model, "systemInstruction", si, "contents", r.contents, "geminiConfig", config,
            "appliedThinkingLevel", r.appliedThinkingLevel, "numericThinkingBudget", r.numericThinkingBudget, "maxOutputTokens", r.maxOutputTokens,
            "dynamicContextTokenCount", r.dynamicContextTokenCount, "currentMessageTokenCount", r.currentMessageTokenCount,
            "careerContext", r.careerContext == null ? UNDEFINED : r.careerContext,
            "contentsTokens", builder.estimateContentsTokens(r.contents), "systemTokens", tokens.estimate(r.systemInstruction));
    }
}
