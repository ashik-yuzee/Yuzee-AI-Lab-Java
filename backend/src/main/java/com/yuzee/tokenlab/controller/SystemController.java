package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.model.ModelInfo;
import com.yuzee.tokenlab.service.BenchmarkService;
import com.yuzee.tokenlab.service.ConversationLogService;
import com.yuzee.tokenlab.service.ConversationService;
import com.yuzee.tokenlab.service.GeminiModelRegistry;
import com.yuzee.tokenlab.service.GeminiService;
import com.yuzee.tokenlab.service.JdbcConversationLogService;
import com.yuzee.tokenlab.service.MemoryResult;
import com.yuzee.tokenlab.service.ConversationMemoryService;
import com.yuzee.tokenlab.service.ObjectiveCatalogueService;
import com.yuzee.tokenlab.service.PathwayWhiteboardService;
import com.yuzee.tokenlab.service.ProfileFactService;
import com.yuzee.tokenlab.service.SharedSettingsService;
import com.yuzee.tokenlab.service.SystemPromptService;
import com.yuzee.tokenlab.service.TokenService;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@RestController
public class SystemController {

    private final SystemPromptService systemPromptService;
    private final TokenService tokenService;
    private final GeminiModelRegistry modelRegistry;
    private final ProfileFactService profileFactService;
    private final ConversationService conversationService;
    private final BenchmarkService benchmarkService;
    private final ConversationLogService conversationLogService;
    private final ObjectiveCatalogueService objectiveCatalogueService;
    private final WarehouseService warehouseService;
    private final PathwayWhiteboardService pathwayWhiteboardService;
    private final GeminiService geminiService;
    private final SharedSettingsService sharedSettingsService;
    private final ConversationMemoryService memoryService;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Path TOKEN_LOG_FILE = Path.of("data", "token-log.ndjson");

    public SystemController(SystemPromptService systemPromptService, TokenService tokenService,
                             GeminiModelRegistry modelRegistry, ProfileFactService profileFactService,
                             ConversationService conversationService, BenchmarkService benchmarkService,
                             ConversationLogService conversationLogService,
                             ObjectiveCatalogueService objectiveCatalogueService,
                             WarehouseService warehouseService,
                             PathwayWhiteboardService pathwayWhiteboardService,
                             GeminiService geminiService,
                             SharedSettingsService sharedSettingsService,
                             ConversationMemoryService memoryService,
                             ObjectProvider<JdbcTemplate> jdbc) {
        this.systemPromptService = systemPromptService;
        this.tokenService = tokenService;
        this.modelRegistry = modelRegistry;
        this.profileFactService = profileFactService;
        this.conversationService = conversationService;
        this.benchmarkService = benchmarkService;
        this.conversationLogService = conversationLogService;
        this.objectiveCatalogueService = objectiveCatalogueService;
        this.warehouseService = warehouseService;
        this.pathwayWhiteboardService = pathwayWhiteboardService;
        this.geminiService = geminiService;
        this.sharedSettingsService = sharedSettingsService;
        this.memoryService = memoryService;
        this.jdbc = jdbc;
    }

    /** Original: {enabled: isDbEnabled(), ...dbPing()}. */
    @GetMapping("/api/db-status")
    public Map<String, Object> dbStatus() {
        JdbcTemplate db = jdbc.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", db != null);
        if (db == null) {
            out.put("ok", false);
            out.put("conversations", 0);
            out.put("logs", 0);
            out.put("error", "no pool");
            return out;
        }
        try {
            Integer c = db.queryForObject("SELECT COUNT(*)::int AS n FROM conversations", Integer.class);
            Integer l = db.queryForObject("SELECT COUNT(*)::int AS n FROM conversation_logs", Integer.class);
            out.put("ok", true);
            out.put("conversations", c);
            out.put("logs", l);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("conversations", 0);
            out.put("logs", 0);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Original: requestAssembler.getProtocolInfo(!!GEMINI_API_KEY, 4), values reproduced as they are. */
    @GetMapping("/api/protocol/info")
    public Map<String, Object> protocolInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("promptVersion", SystemPromptService.VERSION);
        info.put("protocolVersion", "1.3");
        info.put("schemaVersion", "1.3");
        info.put("promptHash", systemPromptService.getHash());
        info.put("schemaHash", schemaHash());
        info.put("promptBytes", systemPromptService.getBytes());
        info.put("targetRuntime", "Node.js / Express / @google/genai");
        info.put("configured", geminiService.isConfigured());
        info.put("trustedServicesCount", 4);
        return info;
    }

    private String schemaHash() {
        try (var in = new ClassPathResource("prompts/response-schema-v1.3.json").getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/api/config/capabilities")
    public Map<String, Object> capabilities() {
        boolean hasKey = geminiService.isConfigured();
        List<String> availableModels = new ArrayList<>();
        for (ModelInfo m : modelRegistry.listModels()) availableModels.add(m.getId());
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("configured", hasKey);
        caps.put("availableModels", availableModels);
        caps.put("modelsList", modelRegistry.listAllModels());
        caps.put("defaultModel", "gemini-3.5-flash");
        caps.put("supportsThinking", true);
        caps.put("supportsCachedTokens", true);
        caps.put("supportsInteractionsApi", true);
        caps.put("supportsExplicitCache", true);
        caps.put("geminiApiKeyPresent", hasKey);
        caps.put("runtime", "preview-adapter");
        return caps;
    }
    @GetMapping("/api/system-prompt")
    public Map<String, Object> getSystemPrompt() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", systemPromptService.getPrompt());
        out.put("hash", systemPromptService.getHash());
        out.put("bytes", systemPromptService.getBytes());
        out.put("filename", SystemPromptService.FILENAME);
        out.put("version", SystemPromptService.VERSION);
        out.put("filepath", "src/protocol/v1.3/" + SystemPromptService.FILENAME);
        return out;
    }

    @PostMapping("/api/system-prompt/reload")
    public Map<String, Object> reloadPrompt() {
        systemPromptService.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("hash", systemPromptService.getHash());
        out.put("bytes", systemPromptService.getBytes());
        return out;
    }
    /** Shared settings plus the default prompt's identity, as the original's GET /api/shared-settings. */
    @GetMapping("/api/shared-settings")
    public Map<String, Object> getSharedSettings() {
        Map<String, Object> out = sharedSettingsService.get();
        out.put("defaultPromptHash", systemPromptService.getHash());
        out.put("defaultPromptBytes", systemPromptService.getBytes());
        return out;
    }

    @PutMapping("/api/shared-settings")
    public Map<String, Object> updateSharedSettings(@RequestBody(required = false) Map<String, Object> body) {
        return sharedSettingsService.update(body != null ? body : Map.of());
    }

    @PostMapping("/api/shared-settings/reset-prompt")
    public Map<String, Object> resetPrompt() {
        return sharedSettingsService.resetPrompt();
    }

    @GetMapping("/api/tokens/session-stats")
    public Map<String, Object> sessionStats() {
        return tokenService.getSessionStats();
    }

    @PostMapping("/api/tokens/session-reset")
    public Map<String, Object> sessionReset() {
        tokenService.resetSession();
        return Map.of("status", "ok");
    }

    /** {@code (await fs.readFile(TOKEN_LOG_FILE,'utf-8')).trim().split('\n').filter(Boolean)}. */
    private static List<String> tokenLogLines() throws java.io.IOException {
        String content = new String(Files.readAllBytes(TOKEN_LOG_FILE), StandardCharsets.UTF_8);
        return java.util.Arrays.stream(com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim(content).split("\n", -1)).filter(l -> !l.isEmpty()).toList();
    }

    /** Last 500 lines of data/token-log.ndjson plus the total line count. */
    @GetMapping("/api/tokens/log")
    public Map<String, Object> tokenLog() {
        try {
            List<String> lines = tokenLogLines();
            List<Object> entries = new ArrayList<>();
            for (String l : lines.subList(Math.max(0, lines.size() - 500), lines.size())) entries.add(com.yuzee.tokenlab.service.JsJson.parse(l));
            return ordered("entries", entries, "total", lines.size());
        } catch (Exception e) {
            return ordered("entries", List.of(), "total", 0);
        }
    }

    /** DB lifetime totals; the original's zero object without a database or on a query failure. */
    @GetMapping("/api/tokens/lifetime-stats")
    public Map<String, Object> lifetimeStats() {
        if (conversationLogService instanceof JdbcConversationLogService) {
            try {
                return conversationLogService.loadLifetimeStats();
            } catch (Exception ignored) { /* loadLifetimeStats() -> null */ }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calls", 0);
        out.put("inputTokens", 0);
        out.put("outputTokens", 0);
        out.put("cachedTokens", 0);
        out.put("thinkingTokens", 0);
        out.put("costUsd", 0);
        Map<String, Object> wb = new LinkedHashMap<>();
        wb.put("calls", 0);
        wb.put("inputTokens", 0);
        wb.put("outputTokens", 0);
        wb.put("costUsd", 0);
        out.put("whiteboard", wb);
        return out;
    }

    /** DB first ('db'), then today's (UTC) entries in the token log ('file'), else 'none'. */
    @GetMapping("/api/tokens/daily-cost")
    public Map<String, Object> dailyCost() {
        if (conversationLogService instanceof JdbcConversationLogService) {
            try {
                return ordered("totalCostUsd", conversationLogService.loadDailyCost(), "source", "db");
            } catch (Exception ignored) { /* loadDailyCost() -> null: fall back to the file */ }
        }
        String today = Instant.now().toString().substring(0, 10);
        try {
            double total = 0;
            for (String line : tokenLogLines()) {
                try {
                    JsonNode e = com.yuzee.tokenlab.service.JsJson.parse(line);
                    if (e.path("datetime").asText("").startsWith(today)) total += e.path("estimatedCostUsd").asDouble(0);
                } catch (Exception ignored) { /* skip a malformed line */ }
            }
            return ordered("totalCostUsd", total, "source", "file");
        } catch (Exception e) {
            return ordered("totalCostUsd", 0, "source", "none");
        }
    }

    @GetMapping("/api/tokens/utility-stats")
    public Map<String, Object> utilityStats() {
        return pathwayWhiteboardService.utilityStats();
    }

    // getTokenCacheKey()-keyed caches, split by authenticity, 500 entries each (oldest evicted).
    private final Map<String, Integer> exactTokenCache = boundedCache();
    private final Map<String, Integer> estimateTokenCache = boundedCache();

    private static Map<String, Integer> boundedCache() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 500; }
        });
    }

    /** countExactTokens(): cached count, else provider countTokens, else a cached estimate. */
    private Object[] countExactTokens(String text, String model) {
        if (text == null || text.trim().isEmpty()) return new Object[]{0, "estimate"};
        String key = model + "::" + SystemPromptService.sha256(model + "::" + text);
        Integer exact = exactTokenCache.get(key);
        if (exact != null) return new Object[]{exact, "countTokens"};
        Integer est = estimateTokenCache.get(key);
        if (est != null) return new Object[]{est, "estimate"};
        if (geminiService.isConfigured()) {
            try {
                Integer n = geminiService.countTokens(model, text);
                if (n != null) {
                    exactTokenCache.put(key, n);
                    return new Object[]{n, "countTokens"};
                }
            } catch (Exception ignored) { /* fall back to estimation */ }
        }
        int e = ConversationMemoryService.estimateTokens(text);
        estimateTokenCache.put(key, e);
        return new Object[]{e, "estimate"};
    }

    /** Pre-flight token count, as the original's POST /api/tokens/count. */
    @PostMapping("/api/tokens/count")
    public Map<String, Object> countTokens(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Map.of();
        String message = b.get("message") instanceof String m ? m : "";
        Object conversationId = b.get("conversationId");
        String model = b.get("model") instanceof String m ? m : "gemini-3.5-flash-lite";
        boolean fastEstimate = Boolean.TRUE.equals(b.get("fastEstimate"));
        String trimmed = message.trim();

        if (trimmed.isEmpty()) {
            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("systemInstructionTokens", 0);
            breakdown.put("careerContextTokens", 0);
            breakdown.put("summaryTokens", 0);
            breakdown.put("recentTurnsTokens", 0);
            breakdown.put("currentMessageTokens", 0);
            breakdown.put("totalAssembledTokens", 0);
            breakdown.put("removedTokens", 0);
            breakdown.put("includedSections", List.of());
            breakdown.put("excludedSections", List.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("userMessageTokens", 0);
            out.put("estimatedTotalInputTokens", 0);
            out.put("exactCount", true);
            out.put("breakdown", breakdown);
            out.put("sources", sourcesMap("countTokens", "estimate", "estimate", "estimate", "countTokens"));
            return out;
        }

        Conversation conv = conversationId instanceof String id && !id.isEmpty()
            ? conversationService.findById(id).orElse(null) : null;
        String sysPrompt = conv != null && "custom".equals(conv.getSystemPromptMode())
            && conv.getCustomSystemPrompt() != null && !conv.getCustomSystemPrompt().isEmpty()
            ? conv.getCustomSystemPrompt() : systemPromptService.getPrompt();
        String careerStr = formatCareerContext(conv != null ? conv.getCareerContext() : null);
        MemoryResult mem = memoryService.assembleMemory(
            conv != null ? conv.getMessages() : List.of(),
            conv != null && conv.getContextBudget() != null && conv.getContextBudget() != 0 ? conv.getContextBudget() : 270000,
            conv != null && conv.getRecentTurnsToKeep() != null && conv.getRecentTurnsToKeep() != 0 ? conv.getRecentTurnsToKeep() : 100,
            conv != null && conv.getStrategy() != null && !conv.getStrategy().isEmpty() ? conv.getStrategy() : "ADAPTIVE_HYBRID",
            conv != null && conv.getSummaryText() != null ? conv.getSummaryText() : "",
            trimmed);
        String summaryText = mem.summaryText != null ? mem.summaryText : "";
        String recentText = mem.recentHistoryText != null ? mem.recentHistoryText : "";

        int userTokens, sysTokens, careerTokens, sumTokens, recTokens;
        Map<String, Object> sources;
        boolean exact;
        if (fastEstimate) {
            userTokens = ConversationMemoryService.estimateTokens(trimmed);
            sysTokens = ConversationMemoryService.estimateTokens(sysPrompt);
            careerTokens = ConversationMemoryService.estimateTokens(careerStr);
            sumTokens = ConversationMemoryService.estimateTokens(summaryText);
            recTokens = ConversationMemoryService.estimateTokens(recentText);
            sources = sourcesMap("estimate", "estimate", "estimate", "estimate", "estimate");
            exact = false;
        } else {
            Object[] u = countExactTokens(trimmed, model);
            Object[] sy = countExactTokens(sysPrompt, model);
            Object[] c = !careerStr.isEmpty() ? countExactTokens(careerStr, model) : new Object[]{0, "estimate"};
            Object[] su = !summaryText.isEmpty() ? countExactTokens(summaryText, model) : new Object[]{0, "estimate"};
            Object[] r = !recentText.isEmpty() ? countExactTokens(recentText, model) : new Object[]{0, "estimate"};
            userTokens = (int) u[0];
            sysTokens = (int) sy[0];
            careerTokens = (int) c[0];
            sumTokens = (int) su[0];
            recTokens = (int) r[0];
            sources = sourcesMap(sy[1], c[1], su[1], r[1], u[1]);
            exact = "countTokens".equals(u[1]) && "countTokens".equals(sy[1])
                && (careerStr.isEmpty() || "countTokens".equals(c[1]))
                && (summaryText.isEmpty() || "countTokens".equals(su[1]))
                && (recentText.isEmpty() || "countTokens".equals(r[1]));
        }
        int total = sysTokens + careerTokens + sumTokens + recTokens + userTokens;

        List<Map<String, Object>> included = new ArrayList<>();
        included.add(section("Yuzee Quiz Prompt v" + SystemPromptService.VERSION, "Authoritative counsellor instruction (systemInstruction)", sysTokens, sysPrompt));
        if (careerTokens > 0) included.add(section("Structured Memory Capsule", "Verified user constraints & goals", careerTokens, careerStr));
        if (sumTokens > 0) included.add(section("Conversation Summary", "Compact semantic memory", sumTokens, summaryText));
        if (recTokens > 0) included.add(section("Recent Dialogue Turns", "Verbatim recent exchanges", recTokens, recentText));
        included.add(section("Current User Input", "Active incoming prompt", userTokens, trimmed));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("systemInstructionTokens", sysTokens);
        breakdown.put("careerContextTokens", careerTokens);
        breakdown.put("summaryTokens", sumTokens);
        breakdown.put("recentTurnsTokens", recTokens);
        breakdown.put("currentMessageTokens", userTokens);
        breakdown.put("totalAssembledTokens", total);
        breakdown.put("removedTokens", mem.removedTokens);
        breakdown.put("includedSections", included);
        breakdown.put("excludedSections", mem.excludedItems != null ? mem.excludedItems : List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userMessageTokens", userTokens);
        out.put("estimatedTotalInputTokens", total);
        out.put("exactCount", exact);
        out.put("sources", sources);
        out.put("breakdown", breakdown);
        return out;
    }

    private static Map<String, Object> sourcesMap(Object system, Object career, Object summary, Object history, Object user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system", system);
        m.put("career", career);
        m.put("summary", summary);
        m.put("history", history);
        m.put("user", user);
        return m;
    }

    private static Map<String, Object> section(String name, String description, int tokens, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("tokens", tokens);
        m.put("preview", text.length() > 75 ? text.substring(0, 75) : text);
        return m;
    }

    /** YuzeeRequestAssembler.formatCareerContext(). */
    private static String formatCareerContext(Map<String, Object> capsule) {
        if (capsule == null) return "";
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : capsule.entrySet()) {
            if (!(e.getValue() instanceof String v) || v.trim().isEmpty()) continue;
            lines.add("- [" + e.getKey() + "]: " + v.trim());
        }
        return lines.isEmpty() ? "" : "YUZEE_STRUCTURED_MEMORY_CAPSULE:\n" + String.join("\n", lines);
    }
    @GetMapping("/api/warehouse/status")
    public Map<String, Object> warehouseStatus() {
        return warehouseService.status();
    }

    @GetMapping("/api/objectives/catalogue")
    public Map<String, Object> objectivesCatalogue() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("version", objectiveCatalogueService.objectiveVersion());
        out.put("experimental", true);
        out.put("objectives", objectiveCatalogueService.catalogueWithAvailability());
        return out;
    }

    @GetMapping("/api/pathway/stats")
    public Map<String, Object> pathwayStats() {
        return pathwayWhiteboardService.stats();
    }

    /** Modelled-estimate (default) or live Gemini benchmark, as the original's POST /api/benchmark. */
    @PostMapping("/api/benchmark")
    public Map<String, Object> benchmark(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Map.of();
        Object conversationId = b.get("conversationId");
        Conversation conv = conversationId instanceof String id && !id.isEmpty()
            ? conversationService.findById(id).orElse(null) : null;
        boolean live = truthy(b.get("isLive"));
        List<String> strategies = b.get("strategies") instanceof List<?> l
            ? l.stream().map(String::valueOf).toList() : null;
        return Map.of("results", benchmarkService.run(conv, str(b.get("prompt")), str(b.get("model")), strategies, live));
    }

    @PostMapping("/api/extract-profile-facts")
    public Map<String, Object> extractProfileFacts(@RequestBody(required = false) Map<String, Object> body) {
        return profileFactService.extractFacts(body != null ? body : Map.of());
    }

    @PostMapping("/api/detect-contradictions")
    public Map<String, Object> detectContradictions(@RequestBody(required = false) Map<String, Object> body) {
        return profileFactService.detectContradictions(body != null ? body : Map.of());
    }

    @PostMapping("/api/pre-check")
    public Map<String, Object> preCheck(@RequestBody(required = false) Map<String, Object> body) {
        return profileFactService.preCheck(body != null ? body : Map.of());
    }

    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    private String str(Object value) {
        return value != null ? value.toString() : null;
    }

    /** Array elements as maps; a non-object element reads as {} (every field undefined), as in JS. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) out.add(item instanceof Map ? (Map<String, Object>) item : Map.of());
        return out;
    }

    /** POST /api/pathway/generate: 503 "AI unavailable", 400 "No messages", 500 {error}, else {nodes, edges}. */
    @SuppressWarnings("unchecked")
    @PostMapping("/api/pathway/generate")
    public ResponseEntity<?> generatePathway(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> b = body != null ? body : Map.of();
        if (!pathwayWhiteboardService.aiAvailable()) return ResponseEntity.status(503).body(Map.of("error", "AI unavailable"));
        Object rawAnswers = b.get("answers");
        Map<String, Object> answers = rawAnswers instanceof Map ? (Map<String, Object>) rawAnswers : Map.of();
        try {
            return ResponseEntity.ok(pathwayWhiteboardService.generate(
                asListOfMaps(b.get("messages")), str(b.get("style")), answers, request.getRemoteAddr()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** POST /api/pathway/recommend: 503 {suggestions:[]} without AI, else {suggestions}. */
    @PostMapping("/api/pathway/recommend")
    public ResponseEntity<?> recommendPathway(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Map.of();
        if (!pathwayWhiteboardService.aiAvailable()) return ResponseEntity.status(503).body(Map.of("suggestions", List.of()));
        List<Map<String, Object>> nodes = asListOfMaps(b.get("nodes"));
        if (nodes.isEmpty()) return ResponseEntity.ok(Map.of("suggestions", List.of()));
        return ResponseEntity.ok(Map.of("suggestions", pathwayWhiteboardService.recommend(nodes, str(b.get("goalContext")))));
    }

    /** POST /api/pathway/explain: always {answer}. */
    @PostMapping("/api/pathway/explain")
    public ResponseEntity<?> explainPathway(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> b = body != null ? body : Map.of();
        return ResponseEntity.ok(Map.of("answer", pathwayWhiteboardService.explain(
            orEmpty(b, "nodeLabel"), orEmpty(b, "nodeSubtitle"), orEmpty(b, "question"), orEmpty(b, "goalContext"),
            request.getRemoteAddr())));
    }

    /** Destructuring default: "" only when the field is absent. */
    private static String orEmpty(Map<String, Object> b, String key) {
        return b.containsKey(key) ? String.valueOf(b.get(key)) : "";
    }

    /**
     * Seeds and returns a fully-populated demo conversation -- Java port of the old app's
     * POST /api/conversations/load-demo (server.ts lines 901-1056). Same "Cybersecurity Analyst
     * Pathway" scenario: a career-context capsule, one real user/assistant turn pair with a
     * genuine v1.3 protocol envelope (steps block + a single_select interaction), so the Sidebar's
     * "Load Demo Pathway" button has real, inspectable content instead of an empty conversation.
     */
    @PostMapping("/api/conversations/load-demo")
    public ResponseEntity<?> loadDemo() {
        long now = System.currentTimeMillis();
        Conversation conv = new Conversation();
        conv.useKeyOrder(Conversation.DEMO_ORDER);
        conv.setCreatedAt(Instant.ofEpochMilli(now));
        conv.setUpdatedAt(Instant.ofEpochMilli(now));
        conv.setId("conv-demo-" + now);
        conv.setTitle("Cybersecurity Analyst Pathway (Demo)");
        conv.setModelId("gemini-3.5-flash");
        conv.setOptimizationMode("AUTO");
        conv.setStrategy("ADAPTIVE_HYBRID");
        conv.setPreset("BALANCED");
        conv.setResponseMode("standard");
        conv.setThinkingLevel("adaptive");
        conv.setContextBudget(270000);
        conv.setRecentTurnsToKeep(100);
        conv.setSummaryText("");
        conv.setSummaryVersion(1);
        conv.setSystemPromptMode("default");
        conv.setCustomSystemPrompt("");
        conv.setUseInteractionsApi(false);
        conv.setUseFlashLiteUtility(true);
        conv.setSecurityBreachCount(0);
        conv.setActiveSecurityPenalty("");
        Map<String, Object> career = new LinkedHashMap<>();
        career.put("facts", "2 years IT Support, CompTIA Network+ certified, hands-on Linux experience");
        career.put("goals", "Transition into Junior SOC Analyst / Tier 1 Security Analyst within 6-9 months");
        career.put("constraints", "Under $1,000 learning budget, 12 hrs/week study time");
        career.put("decisions", "Will pursue CompTIA Security+ first before CySA+");
        career.put("openThreads", "Evaluating TryHackMe SOC Level 1 vs BTL1 certification");
        conv.setCareerContext(career);

        Map<String, Object> interaction = new LinkedHashMap<>();
        interaction.put("kind", "question");
        interaction.put("input_type", "single_select");
        interaction.put("question_id", "q_priority_focus");
        interaction.put("question", "Which milestone would you like to plan out first?");
        interaction.put("options", List.of(
            demoOption("opt_siem", "SIEM & Practical Lab Setup", "Configuring free local lab environments", "siem_lab"),
            demoOption("opt_cert", "Security+ Study Schedule", "Budget-friendly prep resources and exam tips", "sec_plus"),
            demoOption("opt_portfolio", "Incident Walkthrough Portfolio", "Structuring public GitHub investigation reports", "portfolio")
        ));
        interaction.put("allow_other_input", false);
        interaction.put("other_input_label", "");
        interaction.put("fields", List.of());
        interaction.put("recommended_actions", List.of());
        conv.setActiveInteraction(interaction);

        Map<String, Object> block1 = new LinkedHashMap<>();
        block1.put("id", "b1");
        block1.put("type", "text");
        block1.put("level", "none");
        block1.put("variant", "default");
        block1.put("title", "");
        block1.put("text", "Your 2 years in IT support and Network+ foundation give you an immediate "
            + "advantage in packet analysis and system diagnostics. Here is your targeted transition plan.");
        block1.put("items", List.of());
        block1.put("columns", List.of());
        block1.put("rows", List.of());

        Map<String, Object> block2 = new LinkedHashMap<>();
        block2.put("id", "b2");
        block2.put("type", "steps");
        block2.put("level", "h2");
        block2.put("variant", "info");
        block2.put("title", "SOC Analyst Transition Blueprint");
        block2.put("text", "Key milestones to reach Tier-1 SOC readiness within 6 months:");
        block2.put("items", List.of(
            demoStep("s1", "Month 1-2: SIEM & Log Interpretation",
                "Master Splunk Free and Elastic Security log queries for Windows Event IDs and Linux auth logs.",
                "Foundational"),
            demoStep("s2", "Month 3-4: Credential Milestone",
                "Prepare and clear CompTIA Security+ to pass automated HR filters.", "Certification"),
            demoStep("s3", "Month 5-6: Hands-On Portfolio",
                "Complete TryHackMe SOC Level 1 exercises and write up 2 incident walkthroughs in GitHub.", "Proof")
        ));
        block2.put("columns", List.of());
        block2.put("rows", List.of());

        Map<String, Object> parsedResponse = new LinkedHashMap<>();
        parsedResponse.put("schema_version", "1.3");
        parsedResponse.put("current_mode", "A_CONVERSATION");
        parsedResponse.put("response_intent", "ACTION_PLAN");
        parsedResponse.put("content_blocks", List.of(block1, block2));
        parsedResponse.put("interaction", interaction);
        parsedResponse.put("service", ordered(
            "flow", "NONE", "intent_detected", false, "goal_summary", "Junior SOC Analyst transition",
            "trigger", "", "confidence", "", "selected_rmo", "", "offer_target", "",
            "missing_inputs", List.of(), "actions", List.of()
        ));
        parsedResponse.put("state", ordered(
            "active_response_mode", "standard", "effective_response_mode", "standard",
            "mode_source", "default", "safety_override_applied", false,
            "user_confidence", ordered("score", 65, "band", "medium", "evidence_strength", "moderate",
                "trend", "stable", "reason_codes", List.of("GOAL_CLEAR", "ROUTE_UNRESOLVED")),
            "progress", ordered("explained", List.of("transition_overview"), "failed_attempts", 0,
                "loop_count_same_issue", 0)
        ));
        parsedResponse.put("followups", ordered(
            "enabled", true, "cancel_on_user_message", true, "topic_lock", true, "topic_key", "soc_pathway",
            "triggers", List.of(ordered(
                "after_seconds", 10,
                "message", "Would you like me to recommend free SIEM lab guides or Security+ study schedules?",
                "suggested_replies", List.of("Show free SIEM guides", "Security+ study schedule", "Portfolio template")
            ))
        ));

        // Fixed ids, as the original: messages.id is a global primary key and saveMessage() upserts without
        // moving conversation_id, so after a repeat load the stored messages stay with the first demo
        // conversation and a later demo reloads from PostgreSQL with none (the original's behaviour).
        com.yuzee.tokenlab.model.ChatMessage userMsg = new com.yuzee.tokenlab.model.ChatMessage();
        userMsg.setId("user-demo-1");
        userMsg.setRole("user");
        userMsg.setContent("What are the essential skills and certifications I need to transition "
            + "from IT support to a junior SOC analyst?");
        userMsg.setTimestamp(Instant.ofEpochMilli(now - 120000));

        com.yuzee.tokenlab.model.ChatMessage assistantMsg = new com.yuzee.tokenlab.model.ChatMessage();
        assistantMsg.setId("asst-demo-1");
        assistantMsg.setRole("assistant");
        // The original stores the envelope as a JSON string in content (no structuredResponse).
        assistantMsg.setContent(com.yuzee.tokenlab.service.JsJson.stringify(parsedResponse));
        assistantMsg.setTimestamp(Instant.ofEpochMilli(now - 60000));

        conv.getMessages().add(userMsg);
        conv.getMessages().add(assistantMsg);
        conversationService.put(conv); // conversations.set; saveConversation; saveMessage for both messages
        return ResponseEntity.ok(conv.toClientJson());
    }

    /** Insertion-ordered map from key/value pairs, so the stored JSON keeps the original key order. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> demoOption(String id, String label, String description, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("description", description);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> demoStep(String id, String title, String text, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("text", text);
        m.put("value", value);
        m.put("status", "planned");
        return m;
    }
}
