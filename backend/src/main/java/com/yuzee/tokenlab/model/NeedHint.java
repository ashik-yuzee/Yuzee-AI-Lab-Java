package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Port of turnNeeds.ts's NeedHint -- an optional client-supplied "answer|clarify|research" hint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NeedHint {
    private String status; // "selected" | "abstained"
    private String kind;   // "answer" | "clarify" | "research"
    private Double score;
    private Double margin;
    private String reason;
    private String modelId;
    private String profileVersion;
    private List<String> failedGates;

    public static NeedHint abstained(String reason) {
        NeedHint h = new NeedHint();
        h.status = "abstained";
        h.reason = reason;
        return h;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Double getMargin() { return margin; }
    public void setMargin(Double margin) { this.margin = margin; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String profileVersion) { this.profileVersion = profileVersion; }
    public List<String> getFailedGates() { return failedGates; }
    public void setFailedGates(List<String> failedGates) { this.failedGates = failedGates; }
}
