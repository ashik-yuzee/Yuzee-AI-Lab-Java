package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved detail-research turn: the answer plus its request, provenance and usage. Ported from
 * yuzee-ai-token-lab/src/research/types.ts (DetailResult). Persisted as one entry of
 * {@code Conversation.getDetails()} (the same in-memory-Map-per-row pattern the mini-pathway and
 * objectives features use), not a separate file store.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "policyVersion", "conversationId", "request", "retrievedAt", "status", "summary", "facts", "gaps",
    "nextQuestions", "sources", "evidence", "searchSuggestionsHtml", "usage"})
public class DetailResult extends DetailAnswer {
    private String policyVersion;
    private String id;
    private String conversationId;
    private DetailRequest request;
    private String retrievedAt; // ISO-8601 instant string
    private List<DetailSource> sources = new ArrayList<>();
    private List<Evidence> evidence = new ArrayList<>();
    private String searchSuggestionsHtml = "";
    private Usage usage = new Usage();

    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public DetailRequest getRequest() { return request; }
    public void setRequest(DetailRequest request) { this.request = request; }
    public String getRetrievedAt() { return retrievedAt; }
    public void setRetrievedAt(String retrievedAt) { this.retrievedAt = retrievedAt; }
    public List<DetailSource> getSources() { return sources; }
    public void setSources(List<DetailSource> sources) { this.sources = sources; }
    public List<Evidence> getEvidence() { return evidence; }
    public void setEvidence(List<Evidence> evidence) { this.evidence = evidence; }
    public String getSearchSuggestionsHtml() { return searchSuggestionsHtml; }
    public void setSearchSuggestionsHtml(String searchSuggestionsHtml) { this.searchSuggestionsHtml = searchSuggestionsHtml; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        private int inputTokens;
        private int outputTokens;
        private int searchQueries;
        private int calls;

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public int getSearchQueries() { return searchQueries; }
        public void setSearchQueries(int searchQueries) { this.searchQueries = searchQueries; }
        public int getCalls() { return calls; }
        public void setCalls(int calls) { this.calls = calls; }
    }
}
