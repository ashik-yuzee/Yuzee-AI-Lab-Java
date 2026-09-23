package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conversation {
    private String id = UUID.randomUUID().toString();
    private String title = "New conversation";
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private List<ChatMessage> messages = new ArrayList<>();
    private String modelId;
    private String optimizationMode;
    private String responseMode;
    private String strategy;
    private Map<String, Object> careerContext;
    private String summaryText;
    // Lab settings the old app persists (server.ts PUT /api/conversations/:id -> db.ts columns).
    private String preset;
    private String thinkingLevel;
    private Integer contextBudget;
    private Integer recentTurnsToKeep;
    private String systemPromptMode;
    private String customSystemPrompt;
    private Boolean useInteractionsApi;
    private Boolean useFlashLiteUtility;
    // server.ts ConversationItem fields used by the chat turn (db.ts: summary_version,
    // compaction_history, active_interaction; the security counters are in-memory there).
    private Integer summaryVersion;
    private List<Map<String, Object>> compactionHistory = new ArrayList<>();
    private Map<String, Object> activeInteraction;
    private Integer securityBreachCount;
    private String activeSecurityPenalty;
    private Boolean useStructuredOutput;
    // server.ts PUT /api/conversations/:id copies these onto the in-memory conversation as sent
    // (no db.ts column); they are echoed back and kept by the local file store.
    private Boolean useMultiTurn;
    private Object temperature;
    private Object topP;
    private Object maxOutputTokens;

    /** JavaScript key insertion order of the original's ConversationItem object: keys are recorded the first time
     *  they are serialised and never move (Object.assign / property assignment append new keys at the end). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final LinkedHashSet<String> keyOrder = new LinkedHashSet<>();
    /** Whether the original object has an `activeInteraction` key (create never sets it; a turn, load, demo or restore does). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean activeInteractionSet;
    /** Modelled keys the original object holds as an explicit null (PUT assigns a sent null; a stored null is read back). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.Set<String> nullKeys = new java.util.HashSet<>();
    /** Keys a stored conversation had that this class does not model; written back unchanged. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final Map<String, Object> extras = new LinkedHashMap<>();

    /** Create-route order, then keys the original adds later (PUT extras, then a turn's security/interaction state). */
    private static final List<String> DEFAULT_ORDER = List.of("id", "title", "createdAt", "updatedAt", "model", "mode", "strategy",
        "preset", "responseMode", "thinkingLevel", "contextBudget", "recentTurnsToKeep", "careerContext", "summary", "summaryVersion",
        "systemPromptMode", "customSystemPrompt", "useInteractionsApi", "useFlashLiteUtility", "securityBreachCount",
        "activeSecurityPenalty", "messages", "compactionHistory", "useMultiTurn", "useStructuredOutput", "temperature", "topP",
        "maxOutputTokens", "activeInteraction");
    /** POST /api/conversations/load-demo object literal order. */
    public static final List<String> DEMO_ORDER = List.of("id", "title", "createdAt", "updatedAt", "model", "mode", "strategy", "preset",
        "responseMode", "thinkingLevel", "contextBudget", "recentTurnsToKeep", "careerContext", "summary", "summaryVersion",
        "systemPromptMode", "customSystemPrompt", "useInteractionsApi", "useFlashLiteUtility", "activeInteraction", "messages",
        "securityBreachCount", "activeSecurityPenalty", "compactionHistory");
    /** POST /api/conversations/restore object literal order. */
    public static final List<String> RESTORE_ORDER = List.of("id", "title", "createdAt", "updatedAt", "model", "mode", "strategy", "preset",
        "responseMode", "thinkingLevel", "contextBudget", "recentTurnsToKeep", "careerContext", "summary", "summaryVersion",
        "systemPromptMode", "customSystemPrompt", "useInteractionsApi", "useFlashLiteUtility", "activeInteraction",
        "securityBreachCount", "activeSecurityPenalty", "messages", "compactionHistory");
    /** db.ts loadConversations() row mapping order. */
    public static final List<String> DB_ORDER = List.of("id", "title", "createdAt", "updatedAt", "model", "mode", "strategy", "preset",
        "responseMode", "thinkingLevel", "contextBudget", "recentTurnsToKeep", "summary", "summaryVersion", "systemPromptMode",
        "customSystemPrompt", "useInteractionsApi", "useFlashLiteUtility", "careerContext", "compactionHistory", "activeInteraction",
        "messages");

    /** Fix the key order up front (the demo, restore and DB-load object literals). */
    public synchronized void useKeyOrder(List<String> order) {
        keyOrder.clear();
        keyOrder.addAll(order);
    }

    /**
     * The conversation exactly as JSON.stringify writes server.ts's ConversationItem (API responses and
     * data/conversations.json): `model`/`mode`/`summary`, epoch-ms dates, the original's key order, and no
     * undefined keys (a null field is absent, except `activeInteraction`, which is written as null once set).
     */
    public synchronized Map<String, Object> toClientJson() {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k : DEFAULT_ORDER) v.put(k, null);
        v.put("id", id);
        v.put("title", title);
        v.put("createdAt", createdAt != null ? createdAt.toEpochMilli() : null);
        v.put("updatedAt", updatedAt != null ? updatedAt.toEpochMilli() : null);
        v.put("model", modelId);
        v.put("mode", optimizationMode);
        v.put("strategy", strategy);
        v.put("preset", preset);
        v.put("responseMode", responseMode);
        v.put("thinkingLevel", thinkingLevel);
        v.put("contextBudget", contextBudget);
        v.put("recentTurnsToKeep", recentTurnsToKeep);
        v.put("careerContext", careerContext);
        v.put("summary", summaryText);
        v.put("summaryVersion", summaryVersion);
        v.put("systemPromptMode", systemPromptMode);
        v.put("customSystemPrompt", customSystemPrompt);
        v.put("useInteractionsApi", useInteractionsApi);
        v.put("useFlashLiteUtility", useFlashLiteUtility);
        v.put("securityBreachCount", securityBreachCount);
        v.put("activeSecurityPenalty", activeSecurityPenalty);
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (messages != null) for (ChatMessage m : new ArrayList<>(messages)) msgs.add(m.toClientJson());
        v.put("messages", msgs);
        v.put("compactionHistory", compactionHistory);
        v.put("useMultiTurn", useMultiTurn);
        v.put("useStructuredOutput", useStructuredOutput);
        v.put("temperature", temperature);
        v.put("topP", topP);
        v.put("maxOutputTokens", maxOutputTokens);
        v.put("activeInteraction", activeInteraction);
        v.entrySet().removeIf(e -> e.getValue() == null && !nullKeys.contains(e.getKey()) && !("activeInteraction".equals(e.getKey()) && activeInteractionSet));
        extras.forEach(v::putIfAbsent);
        keyOrder.addAll(v.keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keyOrder) if (v.containsKey(k)) out.put(k, v.get(k));
        return out;
    }

    /** A conversation read back from data/conversations.json: same fields, same key order, unknown keys kept. */
    @SuppressWarnings("unchecked")
    public static Conversation fromClientJson(Map<String, Object> raw) {
        Conversation c = new Conversation();
        c.id = null;
        c.title = null;
        c.createdAt = null;
        c.updatedAt = null;
        c.messages = new ArrayList<>();
        c.compactionHistory = null;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "id" -> c.id = str(v);
                case "title" -> c.title = str(v);
                case "createdAt" -> { if (v instanceof Number n) c.createdAt = Instant.ofEpochMilli(n.longValue()); else if (v != null) c.extras.put("createdAt", v); }
                case "updatedAt" -> { if (v instanceof Number n) c.updatedAt = Instant.ofEpochMilli(n.longValue()); else if (v != null) c.extras.put("updatedAt", v); }
                case "model" -> c.modelId = str(v);
                case "mode" -> c.optimizationMode = str(v);
                case "strategy" -> c.strategy = str(v);
                case "preset" -> c.preset = str(v);
                case "responseMode" -> c.responseMode = str(v);
                case "thinkingLevel" -> c.thinkingLevel = str(v);
                case "contextBudget" -> c.contextBudget = v instanceof Number n ? n.intValue() : null;
                case "recentTurnsToKeep" -> c.recentTurnsToKeep = v instanceof Number n ? n.intValue() : null;
                case "careerContext" -> c.careerContext = v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                case "summary" -> c.summaryText = str(v);
                case "summaryVersion" -> c.summaryVersion = v instanceof Number n ? n.intValue() : null;
                case "systemPromptMode" -> c.systemPromptMode = str(v);
                case "customSystemPrompt" -> c.customSystemPrompt = str(v);
                case "useInteractionsApi" -> c.useInteractionsApi = v instanceof Boolean b ? b : null;
                case "useFlashLiteUtility" -> c.useFlashLiteUtility = v instanceof Boolean b ? b : null;
                case "securityBreachCount" -> c.securityBreachCount = v instanceof Number n ? n.intValue() : null;
                case "activeSecurityPenalty" -> c.activeSecurityPenalty = str(v);
                case "compactionHistory" -> c.compactionHistory = v instanceof List<?> l ? (List<Map<String, Object>>) l : null;
                case "useMultiTurn" -> c.useMultiTurn = v instanceof Boolean b ? b : null;
                case "useStructuredOutput" -> c.useStructuredOutput = v instanceof Boolean b ? b : null;
                case "temperature" -> c.temperature = v;
                case "topP" -> c.topP = v;
                case "maxOutputTokens" -> c.maxOutputTokens = v;
                case "activeInteraction" -> {
                    c.activeInteraction = v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                    c.activeInteractionSet = true;
                }
                case "messages" -> {
                    if (v instanceof List<?> list) {
                        for (Object o : list) if (o instanceof Map<?, ?> m) c.messages.add(ChatMessage.fromClientJson((Map<String, Object>) m));
                    }
                }
                default -> c.extras.put(e.getKey(), v);
            }
            if (v == null && !c.extras.containsKey(e.getKey())) c.nullKeys.add(e.getKey());
            c.keyOrder.add(e.getKey());
        }
        return c;
    }

    /** Object.assign(conv, {key: value}) with a JSON null value keeps the key, holding null. */
    public synchronized void setExplicitNull(String key, boolean isNull) {
        if (isNull) nullKeys.add(key); else nullKeys.remove(key);
    }

    private static String str(Object v) {
        return v == null ? null : v instanceof String s ? s : String.valueOf(v);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getOptimizationMode() { return optimizationMode; }
    public void setOptimizationMode(String optimizationMode) { this.optimizationMode = optimizationMode; }
    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String responseMode) { this.responseMode = responseMode; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Map<String, Object> getCareerContext() { return careerContext; }
    public void setCareerContext(Map<String, Object> careerContext) { this.careerContext = careerContext; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String thinkingLevel) { this.thinkingLevel = thinkingLevel; }
    public Integer getContextBudget() { return contextBudget; }
    public void setContextBudget(Integer contextBudget) { this.contextBudget = contextBudget; }
    public Integer getRecentTurnsToKeep() { return recentTurnsToKeep; }
    public void setRecentTurnsToKeep(Integer recentTurnsToKeep) { this.recentTurnsToKeep = recentTurnsToKeep; }
    public String getSystemPromptMode() { return systemPromptMode; }
    public void setSystemPromptMode(String systemPromptMode) { this.systemPromptMode = systemPromptMode; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public void setCustomSystemPrompt(String customSystemPrompt) { this.customSystemPrompt = customSystemPrompt; }
    public Boolean getUseInteractionsApi() { return useInteractionsApi; }
    public void setUseInteractionsApi(Boolean useInteractionsApi) { this.useInteractionsApi = useInteractionsApi; }
    public Boolean getUseFlashLiteUtility() { return useFlashLiteUtility; }
    public void setUseFlashLiteUtility(Boolean useFlashLiteUtility) { this.useFlashLiteUtility = useFlashLiteUtility; }
    public Integer getSummaryVersion() { return summaryVersion; }
    public void setSummaryVersion(Integer summaryVersion) { this.summaryVersion = summaryVersion; }
    public List<Map<String, Object>> getCompactionHistory() { return compactionHistory; }
    public void setCompactionHistory(List<Map<String, Object>> compactionHistory) { this.compactionHistory = compactionHistory; }
    public Map<String, Object> getActiveInteraction() { return activeInteraction; }
    public void setActiveInteraction(Map<String, Object> activeInteraction) { this.activeInteraction = activeInteraction; this.activeInteractionSet = true; }
    public Integer getSecurityBreachCount() { return securityBreachCount; }
    public void setSecurityBreachCount(Integer securityBreachCount) { this.securityBreachCount = securityBreachCount; }
    public String getActiveSecurityPenalty() { return activeSecurityPenalty; }
    public void setActiveSecurityPenalty(String activeSecurityPenalty) { this.activeSecurityPenalty = activeSecurityPenalty; }
    public Boolean getUseStructuredOutput() { return useStructuredOutput; }
    public void setUseStructuredOutput(Boolean useStructuredOutput) { this.useStructuredOutput = useStructuredOutput; }
    public Boolean getUseMultiTurn() { return useMultiTurn; }
    public void setUseMultiTurn(Boolean useMultiTurn) { this.useMultiTurn = useMultiTurn; }
    public Object getTemperature() { return temperature; }
    public void setTemperature(Object temperature) { this.temperature = temperature; }
    public Object getTopP() { return topP; }
    public void setTopP(Object topP) { this.topP = topP; }
    public Object getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Object maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
}
