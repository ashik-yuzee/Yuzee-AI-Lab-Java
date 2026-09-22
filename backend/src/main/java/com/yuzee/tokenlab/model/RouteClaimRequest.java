package com.yuzee.tokenlab.model;

/**
 * Request body for POST /api/routing/validate.
 *
 * This is what the Angular BGE Web Worker submits after it computes a similarity score
 * client-side: its claimed selection (or abstain reason) plus the calibration numbers it used.
 * The server NEVER trusts this at face value -- see RoutingPolicyService#validateRouteSelection,
 * which is the direct port of policy.ts's validateRouteSelection().
 *
 * The dimensions/dtype/pooling/artifact/tokenBudget/release fields are the client's self-reported
 * "BGE ready" handshake (ported from bgeContract.ts's bgeReadyContract / validBgeReady()) -- the
 * worker knows these because it just loaded the pinned model artifact. When modelId is the BGE
 * model id, BgeGateService#validBgeReady re-checks all of them against the server's own pinned
 * config/bgeArtifact.json before the claim can be trusted.
 */
public class RouteClaimRequest {
    /** Which routing flow this claim belongs to: "route" | "topic" | "suggestion". Defaults to "route". */
    private String flow = "route";

    /** Must equal RoutingPolicyService.ROUTER_VERSION or the whole claim is discarded. */
    private String version;

    /** "selected" | "abstained" */
    private String status;

    /** Only meaningful when status == "abstained"; must be one of a known allowlist. */
    private String reason;

    private String toolId;
    private Double score;
    private Double margin;

    /** Defaults to the BGE model id (the only model id the server currently trusts client-side). */
    private String modelId;

    /** Must match the `flow` this claim was submitted for. */
    private String routingFlow;
    private String calibrationVersion;
    private Double domainMargin;

    // --- BGE "ready" handshake fields (see bgeContract.ts / bgeArtifact.json) ---
    private String modelRevision;
    private Integer dimensions;
    private String dtype;
    private String pooling;
    private String artifact;
    private Integer tokenBudget;
    private String release;

    public String getFlow() { return flow; }
    public void setFlow(String flow) { this.flow = flow; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
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
    public String getModelRevision() { return modelRevision; }
    public void setModelRevision(String modelRevision) { this.modelRevision = modelRevision; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }
    public String getDtype() { return dtype; }
    public void setDtype(String dtype) { this.dtype = dtype; }
    public String getPooling() { return pooling; }
    public void setPooling(String pooling) { this.pooling = pooling; }
    public String getArtifact() { return artifact; }
    public void setArtifact(String artifact) { this.artifact = artifact; }
    public Integer getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(Integer tokenBudget) { this.tokenBudget = tokenBudget; }
    public String getRelease() { return release; }
    public void setRelease(String release) { this.release = release; }
}
