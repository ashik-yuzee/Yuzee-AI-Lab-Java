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
    private List<Map<String, Object>> profileFacts = new ArrayList<>();
    private String summaryText;
    private List<Map<String, Object>> miniPathways = new ArrayList<>();
    private List<Map<String, Object>> details = new ArrayList<>();
    private List<Map<String, Object>> objectives = new ArrayList<>();

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
    public List<Map<String, Object>> getProfileFacts() { return profileFacts; }
    public void setProfileFacts(List<Map<String, Object>> profileFacts) { this.profileFacts = profileFacts; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public List<Map<String, Object>> getMiniPathways() { return miniPathways; }
    public void setMiniPathways(List<Map<String, Object>> miniPathways) { this.miniPathways = miniPathways; }
    public List<Map<String, Object>> getDetails() { return details; }
    public void setDetails(List<Map<String, Object>> details) { this.details = details; }
    public List<Map<String, Object>> getObjectives() { return objectives; }
    public void setObjectives(List<Map<String, Object>> objectives) { this.objectives = objectives; }
}
