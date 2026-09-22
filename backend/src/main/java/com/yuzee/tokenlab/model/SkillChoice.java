package com.yuzee.tokenlab.model;

/** Port of skillSuggestions.ts's SkillChoice -- the user clicked an offered skill card. */
public class SkillChoice {
    private String toolId;
    private String sourceMessageId;

    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
}
