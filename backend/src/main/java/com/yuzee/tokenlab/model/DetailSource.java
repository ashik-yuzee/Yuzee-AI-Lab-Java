package com.yuzee.tokenlab.model;

/**
 * A grounding source (one {@code groundingChunks} entry that survived SafeUrlValidator).
 * Ported from yuzee-ai-token-lab/src/research/types.ts (DetailSource).
 */
public class DetailSource {
    private String id;
    private String title;
    private String url;

    public DetailSource() {
    }

    public DetailSource(String id, String title, String url) {
        this.id = id;
        this.title = title;
        this.url = url;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
