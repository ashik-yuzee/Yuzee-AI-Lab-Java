package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.service.ConversationService;
import com.yuzee.tokenlab.service.DetailResearchService;
import com.yuzee.tokenlab.service.MiniPathwayService;
import com.yuzee.tokenlab.service.ObjectiveService;
import com.yuzee.tokenlab.service.GeminiService;
import com.yuzee.tokenlab.service.PathwayWhiteboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversation CRUD, as the original server.ts routes of the same paths. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final String TITLE_MODEL = "gemini-3.5-flash-lite";

    private final ConversationService conversationService;
    private final GeminiService geminiService;
    private final PathwayWhiteboardService utilityStats;
    private final DetailResearchService detailResearch;
    private final MiniPathwayService miniPathwayService;
    private final ObjectiveService objectiveService;
    private final com.yuzee.tokenlab.protocol.ProtocolValidator protocolValidator;

    public ConversationController(ConversationService conversationService,
                                  GeminiService geminiService, PathwayWhiteboardService utilityStats,
                                  DetailResearchService detailResearch, MiniPathwayService miniPathwayService,
                                  ObjectiveService objectiveService, com.yuzee.tokenlab.protocol.ProtocolValidator protocolValidator) {
        this.conversationService = conversationService;
        this.geminiService = geminiService;
        this.utilityStats = utilityStats;
        this.detailResearch = detailResearch;
        this.miniPathwayService = miniPathwayService;
        this.objectiveService = objectiveService;
        this.protocolValidator = protocolValidator;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return conversationService.listAll().stream().map(Conversation::toClientJson).toList();
    }

    /** Create Clean Conversation: every field `body.x || default`, as the original. */
    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Map.of();
        Conversation conv = new Conversation();
        conv.setId("conv-" + System.currentTimeMillis() + "-" + randomBase36(6));
        conv.setTitle(or(b.get("title"), "New Career Exploration"));
        conv.setModelId(or(b.get("model"), "gemini-3.5-flash"));
        conv.setOptimizationMode(or(b.get("mode"), "VANILLA"));
        conv.setStrategy(or(b.get("strategy"), "ADAPTIVE_HYBRID"));
        conv.setPreset(or(b.get("preset"), "BALANCED"));
        conv.setResponseMode(or(b.get("responseMode"), "vanilla"));
        conv.setThinkingLevel(or(b.get("thinkingLevel"), "medium"));
        conv.setContextBudget(intOr(b.get("contextBudget"), 270000));
        conv.setRecentTurnsToKeep(intOr(b.get("recentTurnsToKeep"), 100));
        Map<String, Object> career = new LinkedHashMap<>();
        for (String k : List.of("facts", "goals", "constraints", "decisions", "openThreads")) career.put(k, "");
        conv.setCareerContext(truthy(b.get("careerContext")) && b.get("careerContext") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : career);
        conv.setSummaryText("");
        conv.setSummaryVersion(0);
        conv.setSystemPromptMode(or(b.get("systemPromptMode"), "default"));
        conv.setCustomSystemPrompt(or(b.get("customSystemPrompt"), ""));
        conv.setUseInteractionsApi(Boolean.TRUE.equals(b.get("useInteractionsApi")));
        conv.setUseFlashLiteUtility(b.get("useFlashLiteUtility") instanceof Boolean f ? f : Boolean.TRUE);
        conv.setSecurityBreachCount(0);
        conv.setActiveSecurityPenalty("");
        // No activeInteraction key, as the original's create literal.
        return conversationService.create(conv).toClientJson();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(conv -> ResponseEntity.ok(conv.toClientJson()))
            .orElseGet(ConversationController::notFound);
    }

    /** Allow-listed update (a field left out of the body is unchanged); 404 {error} when missing. */
    private static final List<String> PUT_KEYS = List.of("title", "model", "mode", "strategy", "preset", "responseMode", "thinkingLevel",
        "contextBudget", "recentTurnsToKeep", "careerContext", "systemPromptMode", "customSystemPrompt", "useMultiTurn", "useStructuredOutput",
        "temperature", "topP", "maxOutputTokens", "useInteractionsApi", "useFlashLiteUtility");

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Map.of();
        return conversationService.findById(id).<ResponseEntity<?>>map(conv -> {
            if (b.containsKey("title")) conv.setTitle(str(b.get("title")));
            if (b.containsKey("model")) conv.setModelId(str(b.get("model")));
            if (b.containsKey("mode")) conv.setOptimizationMode(str(b.get("mode")));
            if (b.containsKey("strategy")) conv.setStrategy(str(b.get("strategy")));
            if (b.containsKey("preset")) conv.setPreset(str(b.get("preset")));
            if (b.containsKey("responseMode")) conv.setResponseMode(str(b.get("responseMode")));
            if (b.containsKey("thinkingLevel")) conv.setThinkingLevel(str(b.get("thinkingLevel")));
            if (b.containsKey("contextBudget")) conv.setContextBudget(b.get("contextBudget") instanceof Number n ? n.intValue() : null);
            if (b.containsKey("recentTurnsToKeep")) conv.setRecentTurnsToKeep(b.get("recentTurnsToKeep") instanceof Number n ? n.intValue() : null);
            if (b.containsKey("careerContext")) conv.setCareerContext(b.get("careerContext") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
            if (b.containsKey("systemPromptMode")) conv.setSystemPromptMode(str(b.get("systemPromptMode")));
            if (b.containsKey("customSystemPrompt")) conv.setCustomSystemPrompt(str(b.get("customSystemPrompt")));
            if (b.containsKey("useMultiTurn")) conv.setUseMultiTurn(b.get("useMultiTurn") instanceof Boolean v ? v : null);
            if (b.containsKey("useStructuredOutput")) conv.setUseStructuredOutput(b.get("useStructuredOutput") instanceof Boolean v ? v : null);
            if (b.containsKey("temperature")) conv.setTemperature(b.get("temperature"));
            if (b.containsKey("topP")) conv.setTopP(b.get("topP"));
            if (b.containsKey("maxOutputTokens")) conv.setMaxOutputTokens(b.get("maxOutputTokens"));
            if (b.containsKey("useInteractionsApi")) conv.setUseInteractionsApi(b.get("useInteractionsApi") instanceof Boolean v ? v : null);
            if (b.containsKey("useFlashLiteUtility")) conv.setUseFlashLiteUtility(b.get("useFlashLiteUtility") instanceof Boolean v ? v : null);
            // `v !== undefined` keeps a sent null: the key is assigned and serialised as null.
            for (String k : PUT_KEYS) if (b.containsKey(k)) conv.setExplicitNull(k, b.get(k) == null);
            return ResponseEntity.ok(conversationService.save(conv).toClientJson());
        }).orElseGet(ConversationController::notFound);
    }

    /** 409 while a detail search runs, 404 {error} when missing, else 204 with no body. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (detailResearch.isActive(id)) return ResponseEntity.status(409).body(Map.of("error", "Stop the research before deleting this conversation."));
        if (!conversationService.delete(id)) return notFound();
        // Side effects are fire-and-forget in the original (.catch(() => {})).
        for (Runnable r : List.<Runnable>of(() -> detailResearch.remove(id), () -> miniPathwayService.remove(id), () -> objectiveService.remove(id))) {
            try { r.run(); } catch (Exception ignored) { /* never fails the delete */ }
        }
        return ResponseEntity.noContent().build();
    }

    /** Title from the first exchange via gemini-3.5-flash-lite, as the original. */
    @PostMapping("/{id}/generate-title")
    public ResponseEntity<?> generateTitle(@PathVariable String id) {
        Conversation conv = conversationService.findById(id).orElse(null);
        if (conv == null) return notFound();
        if (!geminiService.isConfigured()) return ResponseEntity.status(503).body(Map.of("error", "AI not configured"));
        ChatMessage userMsg = conv.getMessages().stream().filter(m -> "user".equals(m.getRole())).findFirst().orElse(null);
        ChatMessage assistantMsg = conv.getMessages().stream().filter(m -> "assistant".equals(m.getRole())).findFirst().orElse(null);
        if (userMsg == null) return ResponseEntity.badRequest().body(Map.of("error", "No messages to title"));

        List<String> excerpt = new ArrayList<>();
        excerpt.add("User: " + slice(text(userMsg.getContent()), 400));
        if (assistantMsg != null) excerpt.add("Assistant: " + slice(text(assistantMsg.getContent()), 300));
        try {
            GeminiService.TextResult resp = geminiService.generateText(TITLE_MODEL,
                "Write a short title (4-6 words, no quotes, no punctuation at end) summarising this conversation:\n\n"
                    + String.join("\n", excerpt), 20);
            utilityStats.recordUtility("/api/generate-title", TITLE_MODEL, resp);
            String title = slice(resp.text.trim().replaceAll("^[\"'`]|[\"'`]$", "").replaceFirst("[.!?]$", ""), 60);
            if (title.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "Empty title generated"));
            conv.setTitle(title);
            conversationService.save(conv);
            return ResponseEntity.ok(Map.of("title", title));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Title generation failed"));
        }
    }

    /** Stores the body as the message's feedback (conversation updatedAt untouched); {status:"ok"} even when the message is unknown. */
    @PostMapping("/{id}/feedback")
    public ResponseEntity<?> feedback(@PathVariable String id, @RequestParam(required = false) String messageId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Conversation conv = conversationService.findById(id).orElse(null);
        if (conv == null) return notFound();
        ChatMessage msg = conv.getMessages().stream().filter(m -> m.getId().equals(messageId)).findFirst().orElse(null);
        if (msg != null) {
            msg.setFeedback(body); // in memory only: the original saves nothing here
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Clears summary, summaryVersion and compactionHistory; returns the conversation. */
    @PostMapping("/{id}/reset-memory")
    public ResponseEntity<?> resetMemory(@PathVariable String id) {
        return conversationService.findById(id).<ResponseEntity<?>>map(conv -> {
            conv.setSummaryText("");
            conv.setSummaryVersion(0);
            conv.setCompactionHistory(new ArrayList<>());
            return ResponseEntity.ok(conversationService.save(conv).toClientJson());
        }).orElseGet(ConversationController::notFound);
    }

    /** Restore from the client's local backup: existing conversations are returned untouched. */
    @PostMapping("/restore")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> restore(@RequestBody(required = false) Map<String, Object> data) {
        if (data == null || !truthy(data.get("id"))) return ResponseEntity.badRequest().body(Map.of("error", "Missing conversation id"));
        String id = String.valueOf(data.get("id"));
        Conversation existing = conversationService.findById(id).orElse(null);
        if (existing != null) return ResponseEntity.ok(existing.toClientJson());

        Conversation conv = new Conversation();
        conv.useKeyOrder(Conversation.RESTORE_ORDER);
        conv.setId(id);
        conv.setTitle(or(data.get("title"), "Restored Conversation"));
        conv.setCreatedAt(instantOr(data.get("createdAt")));
        conv.setUpdatedAt(instantOr(data.get("updatedAt")));
        conv.setModelId(or(data.get("model"), "gemini-3.5-flash-lite"));
        conv.setOptimizationMode(or(data.get("mode"), "AUTO"));
        conv.setStrategy(or(data.get("strategy"), "ADAPTIVE_HYBRID"));
        conv.setPreset(or(data.get("preset"), "BALANCED"));
        conv.setResponseMode(or(data.get("responseMode"), "standard"));
        conv.setThinkingLevel(or(data.get("thinkingLevel"), "adaptive"));
        conv.setContextBudget(intOr(data.get("contextBudget"), 270000));
        conv.setRecentTurnsToKeep(intOr(data.get("recentTurnsToKeep"), 100));
        conv.setCareerContext(data.get("careerContext") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
        conv.setSummaryText(or(data.get("summary"), ""));
        conv.setSummaryVersion(intOr(data.get("summaryVersion"), 0));
        conv.setSystemPromptMode(or(data.get("systemPromptMode"), "default"));
        conv.setCustomSystemPrompt(or(data.get("customSystemPrompt"), ""));
        conv.setUseInteractionsApi(Boolean.TRUE.equals(data.get("useInteractionsApi")));
        conv.setUseFlashLiteUtility(data.get("useFlashLiteUtility") instanceof Boolean f ? f : Boolean.TRUE);
        conv.setSecurityBreachCount(data.get("securityBreachCount") instanceof Number n ? n.intValue() : 0);
        conv.setActiveSecurityPenalty(data.get("activeSecurityPenalty") instanceof String p ? p : "");
        conv.setCompactionHistory(data.get("compactionHistory") instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>());
        // The client's message objects are kept as sent (every key, in its order).
        List<ChatMessage> messages = new ArrayList<>();
        if (data.get("messages") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> raw) messages.add(ChatMessage.fromClientJson((Map<String, Object>) raw));
        }
        conv.setMessages(messages);
        conv.setActiveInteraction(recoverActiveInteraction(messages));
        // saveConversation(restored) and saveMessage() for every message; createdAt/updatedAt kept as sent.
        return ResponseEntity.ok(conversationService.create(conv).toClientJson());
    }

    /**
     * recoverActiveInteraction(): walking back from the newest message, the first assistant answer that is not an
     * error, not marked protocolAccepted=false and whose structuredResponse (else JSON-parsed content) passes
     * validateProtocol gives the interaction (null for kind "none"); none found gives null.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recoverActiveInteraction(List<ChatMessage> messages) {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i).toClientJson();
            if (!"assistant".equals(m.get("role")) || truthy(m.get("error"))) continue;
            if (m.get("telemetry") instanceof Map<?, ?> t && t.get("validation") instanceof Map<?, ?> v && Boolean.FALSE.equals(v.get("protocolAccepted"))) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode response = truthy(m.get("structuredResponse"))
                    ? json.valueToTree(m.get("structuredResponse")) : json.readTree(String.valueOf(m.get("content")));
                if (!protocolValidator.validateProtocol(response).protocolAccepted) continue;
                com.fasterxml.jackson.databind.JsonNode interaction = response.get("interaction");
                return "none".equals(interaction.path("kind").asText(null)) ? null : json.convertValue(interaction, Map.class);
            } catch (Exception ignored) { /* Earlier accepted answer remains authoritative after a failed turn. */ }
        }
        return null;
    }

    private static String randomBase36(int n) {
        StringBuilder sb = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) sb.append(Character.forDigit(r.nextInt(36), 36));
        return sb.toString();
    }

    private static String text(Object content) {
        return content instanceof String s ? s : content == null ? "" : String.valueOf(content);
    }

    private static String slice(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    /** JS `value || fallback` for string fields. */
    private static String or(Object v, String fallback) {
        return truthy(v) ? String.valueOf(v) : fallback;
    }

    private static Integer intOr(Object v, int fallback) {
        return truthy(v) && v instanceof Number n ? n.intValue() : fallback;
    }

    /** Epoch ms (the original's shape) or an ISO string; now when absent/falsy. */
    private static Instant instantOr(Object v) {
        if (v instanceof Number n && n.longValue() != 0) return Instant.ofEpochMilli(n.longValue());
        if (v instanceof String s && !s.isEmpty()) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) { /* fall through */ }
        }
        return Instant.now();
    }
}
