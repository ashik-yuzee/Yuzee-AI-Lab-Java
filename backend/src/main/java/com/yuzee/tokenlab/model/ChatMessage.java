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
    private Map<String, Object> tokenUsage;
    private Boolean streamStopped;
    private Boolean validationFailed;
    /** The server's research-offer classification for this turn (TurnNeedsService), when it
     *  decided a scoped "explore more" lookup is relevant -- null for every ordinary turn. */
    private TurnNeeds turnNeeds;

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
    public Map<String, Object> getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(Map<String, Object> tokenUsage) { this.tokenUsage = tokenUsage; }
    public Boolean getStreamStopped() { return streamStopped; }
    public void setStreamStopped(Boolean streamStopped) { this.streamStopped = streamStopped; }
    public Boolean getValidationFailed() { return validationFailed; }
    public void setValidationFailed(Boolean validationFailed) { this.validationFailed = validationFailed; }
    public TurnNeeds getTurnNeeds() { return turnNeeds; }
    public void setTurnNeeds(TurnNeeds turnNeeds) { this.turnNeeds = turnNeeds; }
}
