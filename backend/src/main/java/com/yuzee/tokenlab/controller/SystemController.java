package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.model.ModelInfo;
import com.yuzee.tokenlab.service.GeminiModelRegistry;
import com.yuzee.tokenlab.service.SystemPromptService;
import com.yuzee.tokenlab.service.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {

    private final SystemPromptService systemPromptService;
    private final TokenService tokenService;
    private final GeminiModelRegistry modelRegistry;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    public SystemController(SystemPromptService systemPromptService, TokenService tokenService,
                             GeminiModelRegistry modelRegistry) {
        this.systemPromptService = systemPromptService;
        this.tokenService = tokenService;
        this.modelRegistry = modelRegistry;
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
            // pathway/objectives endpoints exist but still return placeholder data (Phase 3/4 of
            // the migration); warehouse has no source data available at all yet (Phase 9).
            "features", Map.of(
                "pathway", false,
                "warehouse", false,
                "objectives", false
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
        return tokenService.getSessionStats();
    }

    @GetMapping("/api/tokens/daily-cost")
    public Map<String, Object> dailyCost() {
        Map<String, Object> stats = tokenService.getSessionStats();
        return Map.of("estimatedCostUsd", stats.get("estimatedCostUsd"), "date", java.time.LocalDate.now().toString());
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
        return Map.of("state", "UNAVAILABLE", "message", "Warehouse database not configured");
    }

    @GetMapping("/api/objectives/catalogue")
    public Map<String, Object> objectivesCatalogue() {
        return Map.of("version", "1.0", "experimental", true, "objectives", List.of());
    }

    @GetMapping("/api/pathway/stats")
    public Map<String, Object> pathwayStats() {
        return Map.of("calls", 0, "tokens", 0);
    }

    @PostMapping("/api/benchmark")
    public Map<String, Object> benchmark(@RequestBody Map<String, Object> body) {
        return Map.of("results", List.of(), "message", "Benchmark not available in Java port");
    }

    @PostMapping("/api/extract-profile-facts")
    public Map<String, Object> extractProfileFacts(@RequestBody Map<String, Object> body) {
        return Map.of("facts", List.of());
    }

    @PostMapping("/api/detect-contradictions")
    public Map<String, Object> detectContradictions(@RequestBody Map<String, Object> body) {
        return Map.of("contradictions", List.of());
    }

    @PostMapping("/api/pre-check")
    public Map<String, Object> preCheck(@RequestBody Map<String, Object> body) {
        return Map.of("questions", List.of());
    }

    @PostMapping("/api/pathway/generate")
    public ResponseEntity<?> generatePathway(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of(), "generated", true));
    }

    @PostMapping("/api/pathway/recommend")
    public ResponseEntity<?> recommendPathway(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("recommendations", List.of()));
    }

    @PostMapping("/api/pathway/explain")
    public ResponseEntity<?> explainPathway(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("explanation", ""));
    }

    @PostMapping("/api/conversations/load-demo")
    public ResponseEntity<?> loadDemo() {
        return ResponseEntity.ok(Map.of("message", "Demo not available"));
    }
}
