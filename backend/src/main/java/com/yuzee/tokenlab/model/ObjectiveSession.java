package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of contract.ts's ObjectiveSession. Persisted as one JSON file per workspace under
 * data/objective-preview, like the original (see ObjectiveService). Dynamic, per-objective shaped data (the plan the model
 * returned, and the running context handed to the model) stays as Map/List rather than typed
 * fields, matching how the rest of this codebase treats Gemini's dynamic JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectiveSession {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_COMPLETE = "COMPLETE";
    public static final String STATE_CANCELLED = "CANCELLED";

    private String id;
    private String conversationId;
    private String objectiveId;
    private String label;
    private String sourceMessageId;
    private int revision;
    private int interactionCount;
    private String state;
    private long createdAt;
    private long updatedAt;
    /** The last validated planner output (schema.mjs's plannerSchema shape), or null before the first plan. */
    private Map<String, Object> plan;
    /** Running model context: user_message, prior_context_text, confirmed_facts, approved_evidence, ... */
    private Map<String, Object> context = new LinkedHashMap<>();
    /** Null for older workspaces that kept only answer_N facts (history.ts answerHistory). */
    private List<Map<String, Object>> answers;
    private Map<String, Object> pendingAnswer;
    private Map<String, Object> pendingCorrection;
    private String activation;
    /** Always serialised (null for manual openings), like the original. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> routing;
    private boolean autoHandoff;
    private Long handoffAt;
    private String handoffMessageId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getObjectiveId() { return objectiveId; }
    public void setObjectiveId(String objectiveId) { this.objectiveId = objectiveId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public int getInteractionCount() { return interactionCount; }
    public void setInteractionCount(int interactionCount) { this.interactionCount = interactionCount; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, Object> getPlan() { return plan; }
    public void setPlan(Map<String, Object> plan) { this.plan = plan; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public List<Map<String, Object>> getAnswers() { return answers; }
    public void setAnswers(List<Map<String, Object>> answers) { this.answers = answers; }
    public Map<String, Object> getPendingAnswer() { return pendingAnswer; }
    public void setPendingAnswer(Map<String, Object> pendingAnswer) { this.pendingAnswer = pendingAnswer; }
    public Map<String, Object> getPendingCorrection() { return pendingCorrection; }
    public void setPendingCorrection(Map<String, Object> pendingCorrection) { this.pendingCorrection = pendingCorrection; }
    public String getActivation() { return activation; }
    public void setActivation(String activation) { this.activation = activation; }
    public Map<String, Object> getRouting() { return routing; }
    public void setRouting(Map<String, Object> routing) { this.routing = routing; }
    public boolean isAutoHandoff() { return autoHandoff; }
    public void setAutoHandoff(boolean autoHandoff) { this.autoHandoff = autoHandoff; }
    public Long getHandoffAt() { return handoffAt; }
    public void setHandoffAt(Long handoffAt) { this.handoffAt = handoffAt; }
    public String getHandoffMessageId() { return handoffMessageId; }
    public void setHandoffMessageId(String handoffMessageId) { this.handoffMessageId = handoffMessageId; }
}
