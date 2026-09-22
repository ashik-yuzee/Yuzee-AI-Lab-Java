package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Port of turnNeeds.ts's TurnNeeds -- the server's answer|clarify|research plan for one turn. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TurnNeeds {
    private String version = "turn-needs-v1";
    private String action = "answer"; // "answer" | "clarify" | "research"
    private String reason = "ordinary-answer";
    private String basis = "rules"; // "rules" | "minilm"
    private List<String> missing = new ArrayList<>();
    private String question;
    private NeedHint classifier;
    private Scope scope = new Scope();
    private Research research;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Scope {
        private String target = "";
        private String studyYear = "";
        private String location = "";

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getStudyYear() { return studyYear; }
        public void setStudyYear(String studyYear) { this.studyYear = studyYear; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Research {
        private String title;
        private String description;

        public Research() {}
        public Research(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
    public List<String> getMissing() { return missing; }
    public void setMissing(List<String> missing) { this.missing = missing; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public NeedHint getClassifier() { return classifier; }
    public void setClassifier(NeedHint classifier) { this.classifier = classifier; }
    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }
    public Research getResearch() { return research; }
    public void setResearch(Research research) { this.research = research; }
}
