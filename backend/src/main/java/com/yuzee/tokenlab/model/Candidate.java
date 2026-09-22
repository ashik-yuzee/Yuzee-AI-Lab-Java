package com.yuzee.tokenlab.model;

/** A single (toolId, score) ranking entry, as the client-side embedding step produces. */
public class Candidate {
    private String toolId;
    private double score;

    public Candidate() {}
    public Candidate(String toolId, double score) {
        this.toolId = toolId;
        this.score = score;
    }

    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
