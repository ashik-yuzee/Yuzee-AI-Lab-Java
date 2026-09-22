package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Port of policy.ts's RoutingDecision. Returned by POST /api/routing/validate and by every
 * internal accept/continue helper (skill choice, topic route, skill-question continuation).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingDecision {
    private String status; // "selected" | "abstained"
    private String toolId;
    private Double score;
    private Double margin;
    private String modelId;
    private String routingFlow; // "route" | "topic" | "suggestion"
    private String calibrationVersion;
    private Double domainMargin;
    private String reason;
    private String version;
    private Long latencyMs;

    public static RoutingDecision abstain(String reason, String version) {
        RoutingDecision d = new RoutingDecision();
        d.status = "abstained";
        d.reason = reason;
        d.version = version;
        return d;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Double getMargin() { return margin; }
    public void setMargin(Double margin) { this.margin = margin; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getRoutingFlow() { return routingFlow; }
    public void setRoutingFlow(String routingFlow) { this.routingFlow = routingFlow; }
    public String getCalibrationVersion() { return calibrationVersion; }
    public void setCalibrationVersion(String calibrationVersion) { this.calibrationVersion = calibrationVersion; }
    public Double getDomainMargin() { return domainMargin; }
    public void setDomainMargin(Double domainMargin) { this.domainMargin = domainMargin; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
}
