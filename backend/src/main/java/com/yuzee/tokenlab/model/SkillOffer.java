package com.yuzee.tokenlab.model;

/** Port of skillSuggestions.ts's SkillOffer -- one post-response "explore this skill" card. */
public class SkillOffer {
    private String toolId;
    private String label;
    private String description;
    private double score;

    public SkillOffer() {}
    public SkillOffer(String toolId, String label, String description, double score) {
        this.toolId = toolId;
        this.label = label;
        this.description = description;
        this.score = score;
    }

    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
