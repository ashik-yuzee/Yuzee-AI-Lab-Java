package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Body of POST /api/conversations/:id/messages -- the original client's streamChatMessage payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {
    private Object message;
    private String objectiveResultId;
    private Integer objectiveResultRevision;
    private Map<String, Object> userEvent;
    private String model;
    private String strategy;
    private String preset;
    private String responseMode;
    private String thinkingLevel;
    private Integer contextBudget;
    private Integer recentTurnsToKeep;
    private Map<String, Object> careerContext;
    private String systemPromptMode;
    private String customSystemPrompt;
    private Double temperature;
    private Double topP;
    private Double maxOutputTokens;
    private Boolean useMultiTurn;
    private Boolean useStructuredOutput;
    private Boolean usePathwayRag;
    private Map<String, Object> userContext;
    private List<Object> userProfileFacts;
    private List<Object> userQuestionAnswers;
    private Boolean isOptionSelection;
    private List<Map<String, Object>> attachments;
    private String mode;
    private JsonNode needsAssessment;
    private JsonNode skillChoice;
    private JsonNode topicSelection;
    private JsonNode microToolSelection;
    private Object visibleWorkspaceId;
    private Boolean useInteractionsApi;
    private Boolean useFlashLiteUtility;
    /** Earlier Angular client names, accepted as fallbacks for model/mode. */
    private String modelId;
    private String optimizationMode;

    public Object getMessage() { return message; }
    public void setMessage(Object message) { this.message = message; }
    public String getObjectiveResultId() { return objectiveResultId; }
    public void setObjectiveResultId(String v) { this.objectiveResultId = v; }
    public Integer getObjectiveResultRevision() { return objectiveResultRevision; }
    public void setObjectiveResultRevision(Integer v) { this.objectiveResultRevision = v; }
    public Map<String, Object> getUserEvent() { return userEvent; }
    public void setUserEvent(Map<String, Object> userEvent) { this.userEvent = userEvent; }
    public String getModel() { return model != null && !model.isEmpty() ? model : modelId; }
    public void setModel(String model) { this.model = model; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
    public String getResponseMode() { return responseMode; }
    public void setResponseMode(String responseMode) { this.responseMode = responseMode; }
    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String thinkingLevel) { this.thinkingLevel = thinkingLevel; }
    public Integer getContextBudget() { return contextBudget; }
    public void setContextBudget(Integer contextBudget) { this.contextBudget = contextBudget; }
    public Integer getRecentTurnsToKeep() { return recentTurnsToKeep; }
    public void setRecentTurnsToKeep(Integer v) { this.recentTurnsToKeep = v; }
    public Map<String, Object> getCareerContext() { return careerContext; }
    public void setCareerContext(Map<String, Object> careerContext) { this.careerContext = careerContext; }
    public String getSystemPromptMode() { return systemPromptMode; }
    public void setSystemPromptMode(String v) { this.systemPromptMode = v; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public void setCustomSystemPrompt(String v) { this.customSystemPrompt = v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Double getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Double maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public Boolean getUseMultiTurn() { return useMultiTurn; }
    public void setUseMultiTurn(Boolean useMultiTurn) { this.useMultiTurn = useMultiTurn; }
    public Boolean getUseStructuredOutput() { return useStructuredOutput; }
    public void setUseStructuredOutput(Boolean v) { this.useStructuredOutput = v; }
    public Boolean getUsePathwayRag() { return usePathwayRag; }
    public void setUsePathwayRag(Boolean usePathwayRag) { this.usePathwayRag = usePathwayRag; }
    public Map<String, Object> getUserContext() { return userContext; }
    public void setUserContext(Map<String, Object> userContext) { this.userContext = userContext; }
    public List<Object> getUserProfileFacts() { return userProfileFacts; }
    public void setUserProfileFacts(List<Object> v) { this.userProfileFacts = v; }
    public List<Object> getUserQuestionAnswers() { return userQuestionAnswers; }
    public void setUserQuestionAnswers(List<Object> v) { this.userQuestionAnswers = v; }
    public Boolean getIsOptionSelection() { return isOptionSelection; }
    public void setIsOptionSelection(Boolean v) { this.isOptionSelection = v; }
    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) { this.attachments = attachments; }
    public String getMode() { return mode != null && !mode.isEmpty() ? mode : optimizationMode; }
    public void setMode(String mode) { this.mode = mode; }
    public JsonNode getNeedsAssessment() { return needsAssessment; }
    public void setNeedsAssessment(JsonNode v) { this.needsAssessment = v; }
    public JsonNode getSkillChoice() { return skillChoice; }
    public void setSkillChoice(JsonNode skillChoice) { this.skillChoice = skillChoice; }
    public JsonNode getTopicSelection() { return topicSelection; }
    public void setTopicSelection(JsonNode topicSelection) { this.topicSelection = topicSelection; }
    public JsonNode getMicroToolSelection() { return microToolSelection; }
    public void setMicroToolSelection(JsonNode v) { this.microToolSelection = v; }
    public Object getVisibleWorkspaceId() { return visibleWorkspaceId; }
    public void setVisibleWorkspaceId(Object v) { this.visibleWorkspaceId = v; }
    public Boolean getUseInteractionsApi() { return useInteractionsApi; }
    public void setUseInteractionsApi(Boolean v) { this.useInteractionsApi = v; }
    public Boolean getUseFlashLiteUtility() { return useFlashLiteUtility; }
    public void setUseFlashLiteUtility(Boolean v) { this.useFlashLiteUtility = v; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setOptimizationMode(String optimizationMode) { this.optimizationMode = optimizationMode; }
}
