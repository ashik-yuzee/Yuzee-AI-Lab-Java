package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * The structured answer contract Gemini's analysis stage must return. Ported from
 * yuzee-ai-token-lab/src/research/types.ts (DetailAnswer) and the shape enforced by
 * contract.ts's Ajv answerSchema. Fact/NextQuestion are nested here (rather than their own
 * files) since nothing else in the codebase needs them standalone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetailAnswer {
    private String status; // "answered" | "partial" | "needs_clarification" | "no_evidence"
    private String summary = "";
    private List<Fact> facts = new ArrayList<>();
    private List<String> gaps = new ArrayList<>();
    private List<NextQuestion> nextQuestions = new ArrayList<>();

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<Fact> getFacts() { return facts; }
    public void setFacts(List<Fact> facts) { this.facts = facts; }
    public List<String> getGaps() { return gaps; }
    public void setGaps(List<String> gaps) { this.gaps = gaps; }
    public List<NextQuestion> getNextQuestions() { return nextQuestions; }
    public void setNextQuestions(List<NextQuestion> nextQuestions) { this.nextQuestions = nextQuestions; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Fact {
        private String text;
        private String kind; // "source_backed" | "inference" | "benchmark"
        private List<String> evidenceIds = new ArrayList<>();

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public List<String> getEvidenceIds() { return evidenceIds; }
        public void setEvidenceIds(List<String> evidenceIds) { this.evidenceIds = evidenceIds; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NextQuestion {
        private String kind; // "ask_user" | "suggested_question"
        private String text;

        public NextQuestion() {
        }

        public NextQuestion(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }
}
