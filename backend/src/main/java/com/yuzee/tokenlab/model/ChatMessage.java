package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    private String id = UUID.randomUUID().toString();
    private String role;
    private Object content;
    private Instant timestamp = Instant.now();
    private Map<String, Object> parsedResponse;
    private Boolean streamStopped;
    private Boolean validationFailed;
    /** The server's research-offer classification for this turn (TurnNeedsService), when it
     *  decided a scoped "explore more" lookup is relevant -- null for every ordinary turn. */
    private TurnNeeds turnNeeds;
    /** Per-turn telemetry in the old app's MessageTelemetry shape (usage, timeline, model,
     *  appliedThinkingLevel, validation, contextMetrics, compactionMetrics, preflight) -- persisted
     *  to the shared messages.telemetry JSONB column so both apps render the same footer chips. */
    private Map<String, Object> telemetry;
    /** The structured interaction answer behind a user turn (messages.user_event). */
    private Map<String, Object> userEvent;
    /** server.ts MessageItem extras: warehouseData (READY pack on an accepted reply) and objectiveTransfer. */
    private Object warehouseData;
    private Map<String, Object> objectiveTransfer;
    /** server.ts POST /feedback stores the request body on the message (messages.feedback). */
    private Map<String, Object> feedback;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** JavaScript key insertion order of the original's MessageItem (recorded on first serialisation, never moves). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.LinkedHashSet<String> keyOrder = new java.util.LinkedHashSet<>();
    /** Keys a stored or restored message had that this class does not model; written back unchanged. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final Map<String, Object> extras = new java.util.LinkedHashMap<>();
    /** db.ts saveMessage() already ran for this message (it is upserted once, when the original first saves it). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean saved;

    /** server.ts assistant MessageItem literal; `feedback` is assigned later by POST /feedback. */
    private static final java.util.List<String> ASSISTANT_ORDER = java.util.List.of("objectiveTransfer", "warehouseData", "preflight",
        "id", "role", "content", "userEvent", "structuredResponse", "telemetry", "createdAt", "feedback");
    /** server.ts user MessageItem literal (`{id, role, content, userEvent, objectiveTransfer, createdAt}`). */
    private static final java.util.List<String> USER_ORDER = java.util.List.of("id", "role", "content", "userEvent", "objectiveTransfer",
        "warehouseData", "preflight", "structuredResponse", "telemetry", "createdAt", "feedback");
    /** db.ts loadConversations() message mapping order. */
    public static final java.util.List<String> DB_ORDER = java.util.List.of("id", "role", "content", "structuredResponse", "userEvent",
        "telemetry", "feedback", "createdAt");

    public void useKeyOrder(java.util.List<String> order) {
        synchronized (keyOrder) {
            keyOrder.clear();
            keyOrder.addAll(order);
        }
    }

    public boolean isSaved() { return saved; }
    public void setSaved(boolean saved) { this.saved = saved; }

    /**
     * The message as JSON.stringify writes server.ts's MessageItem: string content, `structuredResponse` only for an
     * accepted reply, `preflight`, epoch-ms `createdAt`, the original's key order; Java-only bookkeeping is left out.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toClientJson() {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        for (String k : "user".equals(role) ? USER_ORDER : ASSISTANT_ORDER) v.put(k, null);
        Object event = userEvent;
        String text;
        if (content == null || content instanceof String) text = (String) content;
        else if ("user".equals(role) && content instanceof Map<?, ?> m) {
            text = com.yuzee.tokenlab.repository.JdbcConversationRepository.userEventLabel(m);
            if (event == null) event = m;
        } else {
            text = com.yuzee.tokenlab.service.JsJson.stringify(content);
        }
        v.put("objectiveTransfer", objectiveTransfer);
        v.put("warehouseData", warehouseData);
        v.put("preflight", turnNeeds != null ? JSON.convertValue(turnNeeds, Map.class) : null);
        v.put("id", id);
        v.put("role", role);
        v.put("content", text != null ? text : "");
        v.put("userEvent", event);
        v.put("structuredResponse", Boolean.TRUE.equals(validationFailed) ? null : parsedResponse);
        v.put("telemetry", telemetry);
        v.put("feedback", feedback);
        v.put("createdAt", timestamp != null ? timestamp.toEpochMilli() : null);
        v.values().removeIf(java.util.Objects::isNull);
        extras.forEach(v::putIfAbsent);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        synchronized (keyOrder) {
            keyOrder.addAll(v.keySet());
            for (String k : keyOrder) if (v.containsKey(k)) out.put(k, v.get(k));
        }
        return out;
    }

    /** A message read back from data/conversations.json or sent to /restore: known fields mapped, raw keys and order kept. */
    @SuppressWarnings("unchecked")
    public static ChatMessage fromClientJson(Map<String, Object> m) {
        ChatMessage msg = new ChatMessage();
        msg.id = null;
        msg.timestamp = null;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "id" -> msg.id = v == null ? null : String.valueOf(v);
                case "role" -> msg.role = v == null ? null : String.valueOf(v);
                case "content" -> msg.content = v;
                case "createdAt" -> msg.timestamp = v instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null;
                case "structuredResponse" -> msg.parsedResponse = v instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
                case "userEvent" -> msg.userEvent = v instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
                case "telemetry" -> msg.telemetry = v instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
                case "feedback" -> msg.feedback = v instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
                case "objectiveTransfer" -> msg.objectiveTransfer = v instanceof Map<?, ?> s ? (Map<String, Object>) s : null;
                case "warehouseData" -> msg.warehouseData = v;
                case "preflight" -> {
                    if (v instanceof Map<?, ?> pf) {
                        try { msg.turnNeeds = JSON.convertValue(pf, TurnNeeds.class); } catch (Exception ignored) { msg.extras.put("preflight", v); }
                    }
                }
                default -> msg.extras.put(e.getKey(), v);
            }
            msg.keyOrder.add(e.getKey());
        }
        return msg;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Map<String, Object> getParsedResponse() { return parsedResponse; }
    public void setParsedResponse(Map<String, Object> parsedResponse) { this.parsedResponse = parsedResponse; }
    public Boolean getStreamStopped() { return streamStopped; }
    public void setStreamStopped(Boolean streamStopped) { this.streamStopped = streamStopped; }
    public Boolean getValidationFailed() { return validationFailed; }
    public void setValidationFailed(Boolean validationFailed) { this.validationFailed = validationFailed; }
    public TurnNeeds getTurnNeeds() { return turnNeeds; }
    public void setTurnNeeds(TurnNeeds turnNeeds) { this.turnNeeds = turnNeeds; }
    public Map<String, Object> getTelemetry() { return telemetry; }
    public void setTelemetry(Map<String, Object> telemetry) { this.telemetry = telemetry; }
    public Map<String, Object> getUserEvent() { return userEvent; }
    public void setUserEvent(Map<String, Object> userEvent) { this.userEvent = userEvent; }
    public Object getWarehouseData() { return warehouseData; }
    public void setWarehouseData(Object warehouseData) { this.warehouseData = warehouseData; }
    public Map<String, Object> getObjectiveTransfer() { return objectiveTransfer; }
    public void setObjectiveTransfer(Map<String, Object> objectiveTransfer) { this.objectiveTransfer = objectiveTransfer; }
    public Map<String, Object> getFeedback() { return feedback; }
    public void setFeedback(Map<String, Object> feedback) { this.feedback = feedback; }
}
