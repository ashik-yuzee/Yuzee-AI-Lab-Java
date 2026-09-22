package com.yuzee.tokenlab.model;

/** One prior conversation turn, as fed into TurnNeedsService/RoutingPolicyService history logic. */
public class HistoryTurn {
    private String role;
    private String content;
    /** The TurnNeeds the server computed for this turn, when role == "assistant". */
    private TurnNeeds preflight;

    public HistoryTurn() {}
    public HistoryTurn(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public TurnNeeds getPreflight() { return preflight; }
    public void setPreflight(TurnNeeds preflight) { this.preflight = preflight; }
}
