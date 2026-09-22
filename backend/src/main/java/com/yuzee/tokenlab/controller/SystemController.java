package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.model.ModelInfo;
import com.yuzee.tokenlab.service.BenchmarkService;
import com.yuzee.tokenlab.service.ClarificationPreCheckService;
import com.yuzee.tokenlab.service.ConversationLogService;
import com.yuzee.tokenlab.service.ConversationService;
import com.yuzee.tokenlab.service.GeminiModelRegistry;
import com.yuzee.tokenlab.service.ObjectiveCatalogueService;
import com.yuzee.tokenlab.service.PathwayWhiteboardService;
import com.yuzee.tokenlab.service.ProfileFactService;
import com.yuzee.tokenlab.service.SystemPromptService;
import com.yuzee.tokenlab.service.TokenService;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class SystemController {

    private final SystemPromptService systemPromptService;
    private final TokenService tokenService;
    private final GeminiModelRegistry modelRegistry;
    private final ProfileFactService profileFactService;
    private final ClarificationPreCheckService clarificationPreCheckService;
    private final ConversationService conversationService;
    private final BenchmarkService benchmarkService;
    private final ConversationLogService conversationLogService;
    private final ObjectiveCatalogueService objectiveCatalogueService;
    private final WarehouseService warehouseService;
    private final PathwayWhiteboardService pathwayWhiteboardService;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public SystemController(SystemPromptService systemPromptService, TokenService tokenService,
                             GeminiModelRegistry modelRegistry, ProfileFactService profileFactService,
                             ClarificationPreCheckService clarificationPreCheckService,
                             ConversationService conversationService, BenchmarkService benchmarkService,
                             ConversationLogService conversationLogService,
                             ObjectiveCatalogueService objectiveCatalogueService,
                             WarehouseService warehouseService,
                             PathwayWhiteboardService pathwayWhiteboardService) {
        this.systemPromptService = systemPromptService;
        this.tokenService = tokenService;
        this.modelRegistry = modelRegistry;
        this.profileFactService = profileFactService;
        this.clarificationPreCheckService = clarificationPreCheckService;
        this.conversationService = conversationService;
        this.benchmarkService = benchmarkService;
        this.conversationLogService = conversationLogService;
        this.objectiveCatalogueService = objectiveCatalogueService;
        this.warehouseService = warehouseService;
        this.pathwayWhiteboardService = pathwayWhiteboardService;
    }

    @GetMapping("/api/db-status")
    public Map<String, Object> dbStatus() {
        boolean dbEnabled = datasourceUrl != null && !datasourceUrl.isBlank();
        return Map.of("status", "ok", "db", dbEnabled,
            "message", dbEnabled ? "Postgres store active" : "Local file store active");
    }

    @GetMapping("/api/protocol/info")
    public Map<String, Object> protocolInfo() {
        return Map.of(
            "protocol", "Yuzee Response Protocol",
            "version", "1.3",
            "schemaVersion", "1.3.0"
        );
    }

    @GetMapping("/api/config/capabilities")
    public Map<String, Object> capabilities() {
        List<Map<String, Object>> models = new ArrayList<>();
        for (ModelInfo m : modelRegistry.listModels()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", m.getId());
            entry.put("label", m.getName());
            if (Boolean.TRUE.equals(m.getIsDefault())) entry.put("default", true);
            if (Boolean.TRUE.equals(m.getIsRecommended())) entry.put("recommended", true);
            if (m.getBadge() != null) entry.put("badge", m.getBadge());
            models.add(entry);
        }
        return Map.of(
            "models", models,
            // "pathway" here means mini-pathway (real). The pathway *whiteboard* is a distinct
            // visual node-graph feature (/api/pathway/*) not built yet and isn't reflected by this
            // flag in the old app either. Warehouse has no source data available at all yet.
            "features", Map.of(
                "pathway", true,
                "warehouse", warehouseService.isAvailable(),
                "objectives", true
            )
        );
    }

    @GetMapping("/api/system-prompt")
    public Map<String, Object> getSystemPrompt() {
        return systemPromptService.getInfo();
    }

    @PostMapping("/api/system-prompt/reload")
    public Map<String, Object> reloadPrompt() {
        systemPromptService.reload();
        return Map.of("ok", true, "reloaded", true);
    }

    @GetMapping("/api/shared-settings")
    public Map<String, Object> getSharedSettings() {
        return Map.of(
            "mode", "AUTO",
            "strategy", "ADAPTIVE_HYBRID",
            "contextBudget", 100000
        );
    }

    @PutMapping("/api/shared-settings")
    public Map<String, Object> updateSharedSettings(@RequestBody Map<String, Object> body) {
        return Map.of("ok", true);
    }

    @PostMapping("/api/shared-settings/reset-prompt")
    public Map<String, Object> resetPrompt() {
        systemPromptService.reload();
        return Map.of("ok", true);
    }

    @GetMapping("/api/tokens/session-stats")
    public Map<String, Object> sessionStats() {
        return tokenService.getSessionStats();
    }

    @PostMapping("/api/tokens/session-reset")
    public Map<String, Object> sessionReset() {
        tokenService.resetSession();
        return Map.of("ok", true);
    }

    @GetMapping("/api/tokens/log")
    public Map<String, Object> tokenLog() {
        return Map.of("entries", List.of());
    }

    @GetMapping("/api/tokens/lifetime-stats")
    public Map<String, Object> lifetimeStats() {
        return conversationLogService.loadLifetimeStats();
    }

    @GetMapping("/api/tokens/daily-cost")
    public Map<String, Object> dailyCost() {
        boolean dbEnabled = datasourceUrl != null && !datasourceUrl.isBlank();
        return Map.of("totalCostUsd", conversationLogService.loadDailyCost(), "source", dbEnabled ? "db" : "file");
    }

    @GetMapping("/api/tokens/utility-stats")
    public Map<String, Object> utilityStats() {
        return Map.of("whiteboardCalls", 0, "utilityModelCalls", 0);
    }

    @PostMapping("/api/tokens/count")
    public Map<String, Object> countTokens(@RequestBody Map<String, Object> body) {
        String text = body.getOrDefault("text", "").toString();
        int estimated = tokenService.estimate(text);
        return Map.of("total", estimated, "breakdown", Map.of("message", estimated));
    }

    @GetMapping("/api/warehouse/status")
    public Map<String, Object> warehouseStatus() {
        return warehouseService.status();
    }

    @GetMapping("/api/objectives/catalogue")
    public Map<String, Object> objectivesCatalogue() {
        List<JsonNode> available = objectiveCatalogueService.availableCatalogueObjectives();
        return Map.of("version", objectiveCatalogueService.catalogueVersion(), "experimental", true, "objectives", available);
    }

    @GetMapping("/api/pathway/stats")
    public Map<String, Object> pathwayStats() {
        return pathwayWhiteboardService.stats();
    }

    /**
     * Modelled-estimate (default) or live Gemini benchmark comparing the three retention
     * strategies. Body: {conversationId?, live?: boolean, modelId?, tokenBudget?}. Java port of
     * the old app's POST /api/benchmark (server.ts).
     */
    @PostMapping("/api/benchmark")
    public Map<String, Object> benchmark(@RequestBody Map<String, Object> body) {
        String conversationId = str(body.get("conversationId"));
        Conversation conv = (conversationId != null && !conversationId.isBlank())
            ? conversationService.findById(conversationId).orElse(null) : null;
        boolean live = Boolean.TRUE.equals(body.get("live"));
        int tokenBudget = body.get("tokenBudget") instanceof Number n ? n.intValue() : 8000;

        List<Map<String, Object>> results = live
            ? benchmarkService.runLiveBenchmark(conv, str(body.get("modelId")), tokenBudget)
            : benchmarkService.runModelledBenchmark(conv, tokenBudget);

        return Map.of("results", results);
    }

    @PostMapping("/api/extract-profile-facts")
    public Map<String, Object> extractProfileFacts(@RequestBody Map<String, Object> body) {
        String userMessage = str(body.get("userMessage"));
        String modelId = str(body.get("modelId"));
        List<Map<String, Object>> facts = profileFactService.extractFacts(userMessage, modelId);
        return Map.of("facts", facts);
    }

    @PostMapping("/api/detect-contradictions")
    public Map<String, Object> detectContradictions(@RequestBody Map<String, Object> body) {
        String userMessage = str(body.get("userMessage"));
        String modelId = str(body.get("modelId"));
        List<Map<String, Object>> profileFacts = asListOfMaps(body.get("profileFacts"));
        if (profileFacts.isEmpty()) {
            profileFacts = storedProfileFacts(str(body.get("conversationId")));
        }
        List<Map<String, Object>> contradictions =
            profileFactService.detectContradictions(userMessage, profileFacts, modelId);
        return Map.of("contradictions", contradictions);
    }

    @PostMapping("/api/pre-check")
    public Map<String, Object> preCheck(@RequestBody Map<String, Object> body) {
        String userMessage = str(body.get("userMessage"));
        String modelId = str(body.get("modelId"));
        List<Map<String, Object>> unresolvedContradictions = asListOfMaps(body.get("unresolvedContradictions"));
        List<Map<String, Object>> questions =
            clarificationPreCheckService.preCheck(userMessage, unresolvedContradictions, modelId);
        return Map.of("needsClarification", !questions.isEmpty(), "questions", questions);
    }

    private String str(Object value) {
        return value != null ? value.toString() : null;
    }

    /** Best-effort: each element is either an already-shaped fact/contradiction map, or a bare string. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            } else if (item != null) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("id", UUID.randomUUID().toString());
                wrapped.put("text", item.toString());
                wrapped.put("category", "general");
                out.add(wrapped);
            }
        }
        return out;
    }

    private List<Map<String, Object>> storedProfileFacts(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        return conversationService.findById(conversationId)
            .map(Conversation::getProfileFacts)
            .orElse(List.of());
    }

    /**
     * Pathway Whiteboard generation -- Java port of the old app's POST /api/pathway/generate. Body:
     * {@code {goal, style?, modelId?}}. Response: {@code {nodes: PathwayNode[], edges: PathwayEdge[]}}.
     */
    @PostMapping("/api/pathway/generate")
    public ResponseEntity<?> generatePathway(@RequestBody Map<String, Object> body) {
        String goal = str(body.get("goal"));
        String style = str(body.get("style"));
        String modelId = str(body.get("modelId"));
        try {
            return ResponseEntity.ok(pathwayWhiteboardService.generate(goal, style, modelId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Pathway Whiteboard next-step suggestions -- Java port of POST /api/pathway/recommend. Body:
     * {@code {nodes: [{id,label,type}], context?, modelId?}}. Response:
     * {@code {suggestions: [{type,label,subtitle,reason}]}} (exactly 3 on success, [] on failure).
     */
    @PostMapping("/api/pathway/recommend")
    public ResponseEntity<?> recommendPathway(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> nodes = asListOfMaps(body.get("nodes"));
        String context = str(body.get("context"));
        String modelId = str(body.get("modelId"));
        return ResponseEntity.ok(Map.of("suggestions", pathwayWhiteboardService.recommend(nodes, context, modelId)));
    }

    /**
     * Pathway Whiteboard node Q&A -- Java port of POST /api/pathway/explain. Body:
     * {@code {node: {label, subtitle?, goalContext?}, question, modelId?}}. Response:
     * {@code {answer: string}} (a fail-soft apology string on any error, never an HTTP error).
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/api/pathway/explain")
    public ResponseEntity<?> explainPathway(@RequestBody Map<String, Object> body) {
        Object rawNode = body.get("node");
        Map<String, Object> node = rawNode instanceof Map ? (Map<String, Object>) rawNode : Map.of();
        String question = str(body.get("question"));
        String modelId = str(body.get("modelId"));
        return ResponseEntity.ok(Map.of("answer", pathwayWhiteboardService.explain(node, question, modelId)));
    }

    @PostMapping("/api/conversations/load-demo")
    public ResponseEntity<?> loadDemo() {
        return ResponseEntity.ok(Map.of("message", "Demo not available"));
    }
}
