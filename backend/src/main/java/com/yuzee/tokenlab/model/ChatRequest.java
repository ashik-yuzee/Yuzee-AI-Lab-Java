package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {
    private Object message;
    private String modelId;
    private String optimizationMode;
    private String responseMode;
    private String strategy;
    private Map<String, Object> careerContext;
    private Map<String, Object> userEvent;
    private String skillChoice;
    private String topicRoute;
    private Boolean stopStream;

    public Object getMessage() { return message; }
    public void setMessage(Object message) { this.message = message; }
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
    public Map<String, Object> getUserEvent() { return userEvent; }
    public void setUserEvent(Map<String, Object> userEvent) { this.userEvent = userEvent; }
    public String getSkillChoice() { return skillChoice; }
    public void setSkillChoice(String skillChoice) { this.skillChoice = skillChoice; }
    public String getTopicRoute() { return topicRoute; }
    public void setTopicRoute(String topicRoute) { this.topicRoute = topicRoute; }
    public Boolean getStopStream() { return stopStream; }
    public void setStopStream(Boolean stopStream) { this.stopStream = stopStream; }
}
