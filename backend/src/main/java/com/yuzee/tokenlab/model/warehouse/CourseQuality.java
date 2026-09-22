package com.yuzee.tokenlab.model.warehouse;

/** One scored quality dimension for a course. Ported from yuzee-ai-token-lab/src/warehouse/types.ts (CourseQuality). */
public class CourseQuality {
    private String key;
    private String label;
    private Double value;
    private String explanation;

    public CourseQuality() {}
    public CourseQuality(String key, String label, Double value, String explanation) {
        this.key = key; this.label = label; this.value = value; this.explanation = explanation;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
