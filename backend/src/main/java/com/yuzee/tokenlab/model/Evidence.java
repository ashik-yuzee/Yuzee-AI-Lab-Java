package com.yuzee.tokenlab.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One grounded excerpt extracted from a Gemini search-grounding response, mapped from a
 * {@code groundingSupports} entry. Ported from yuzee-ai-token-lab/src/research/types.ts (Evidence).
 */
public class Evidence {
    private String id;
    private String text;
    private List<String> sourceIds = new ArrayList<>();

    public Evidence() {
    }

    public Evidence(String id, String text, List<String> sourceIds) {
        this.id = id;
        this.text = text;
        this.sourceIds = sourceIds;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<String> getSourceIds() { return sourceIds; }
    public void setSourceIds(List<String> sourceIds) { this.sourceIds = sourceIds; }
}
