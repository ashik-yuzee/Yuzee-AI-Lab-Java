package com.yuzee.tokenlab.model.warehouse;

import java.util.ArrayList;
import java.util.List;

/** A single normalized course record. Ported from yuzee-ai-token-lab/src/warehouse/types.ts (WarehouseCourse). */
public class WarehouseCourse {
    private String id;
    private String evidenceId;
    private String name;
    private String provider;
    private String code;
    private String level;
    private String type;
    private String duration;
    private List<String> delivery = new ArrayList<>();
    private List<String> locations = new ArrayList<>();
    private List<String> entry = new ArrayList<>();
    private String description;
    private Fees fees = new Fees();
    private List<String> skills = new ArrayList<>();
    private List<String> outcomes = new ArrayList<>();
    private List<String> assessments = new ArrayList<>();
    private List<String> bestFor = new ArrayList<>();
    private List<String> considerations = new ArrayList<>();
    private List<CourseQuality> quality = new ArrayList<>();
    private String qualityExplanation;
    private List<String> evidenceIssues;
    private List<IntelligenceSection> intelligence = new ArrayList<>();
    private String providerId;
    private ComparisonDetails comparisonDetails = new ComparisonDetails();
    private Source source = new Source();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public List<String> getDelivery() { return delivery; }
    public void setDelivery(List<String> delivery) { this.delivery = delivery; }
    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }
    public List<String> getEntry() { return entry; }
    public void setEntry(List<String> entry) { this.entry = entry; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Fees getFees() { return fees; }
    public void setFees(Fees fees) { this.fees = fees; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public List<String> getOutcomes() { return outcomes; }
    public void setOutcomes(List<String> outcomes) { this.outcomes = outcomes; }
    public List<String> getAssessments() { return assessments; }
    public void setAssessments(List<String> assessments) { this.assessments = assessments; }
    public List<String> getBestFor() { return bestFor; }
    public void setBestFor(List<String> bestFor) { this.bestFor = bestFor; }
    public List<String> getConsiderations() { return considerations; }
    public void setConsiderations(List<String> considerations) { this.considerations = considerations; }
    public List<CourseQuality> getQuality() { return quality; }
    public void setQuality(List<CourseQuality> quality) { this.quality = quality; }
    public String getQualityExplanation() { return qualityExplanation; }
    public void setQualityExplanation(String qualityExplanation) { this.qualityExplanation = qualityExplanation; }
    public List<String> getEvidenceIssues() { return evidenceIssues; }
    public void setEvidenceIssues(List<String> evidenceIssues) { this.evidenceIssues = evidenceIssues; }
    public List<IntelligenceSection> getIntelligence() { return intelligence; }
    public void setIntelligence(List<IntelligenceSection> intelligence) { this.intelligence = intelligence; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public ComparisonDetails getComparisonDetails() { return comparisonDetails; }
    public void setComparisonDetails(ComparisonDetails comparisonDetails) { this.comparisonDetails = comparisonDetails; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public static class Fees {
        private String domestic;
        private String international;
        private List<String> details = new ArrayList<>();
        public String getDomestic() { return domestic; }
        public void setDomestic(String domestic) { this.domestic = domestic; }
        public String getInternational() { return international; }
        public void setInternational(String international) { this.international = international; }
        public List<String> getDetails() { return details; }
        public void setDetails(List<String> details) { this.details = details; }
    }

    public static class IntelligenceSection {
        private String key;
        private String label;
        private List<String> items = new ArrayList<>();
        public IntelligenceSection() {}
        public IntelligenceSection(String key, String label, List<String> items) {
            this.key = key; this.label = label; this.items = items;
        }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<String> getItems() { return items; }
        public void setItems(List<String> items) { this.items = items; }
    }

    public static class ComparisonDetails {
        private List<String> learning = new ArrayList<>();
        private List<String> practice = new ArrayList<>();
        private List<String> attendance = new ArrayList<>();
        private List<String> credit = new ArrayList<>();
        private List<String> strengths = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
        private String outcome;
        public List<String> getLearning() { return learning; }
        public void setLearning(List<String> learning) { this.learning = learning; }
        public List<String> getPractice() { return practice; }
        public void setPractice(List<String> practice) { this.practice = practice; }
        public List<String> getAttendance() { return attendance; }
        public void setAttendance(List<String> attendance) { this.attendance = attendance; }
        public List<String> getCredit() { return credit; }
        public void setCredit(List<String> credit) { this.credit = credit; }
        public List<String> getStrengths() { return strengths; }
        public void setStrengths(List<String> strengths) { this.strengths = strengths; }
        public List<String> getLimitations() { return limitations; }
        public void setLimitations(List<String> limitations) { this.limitations = limitations; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
    }

    public static class Source {
        private String label = "Yuzee course catalogue";
        private String url;
        private String updatedAt;
        private final String origin = "YUZEE_WAREHOUSE";
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getOrigin() { return origin; }
    }
}
