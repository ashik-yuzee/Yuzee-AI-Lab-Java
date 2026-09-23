package com.yuzee.tokenlab.model.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Row-based comparison table. Ported from yuzee-ai-token-lab/src/warehouse/types.ts (WarehouseComparison)
 *  and comparison.ts (buildComparison). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WarehouseComparison {
    private final String snippetId = "rto_course_comparison";
    private String title;
    private String baseline;
    private List<String> notes = new ArrayList<>();
    private List<Option> options = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();
    private List<ProviderMatch> providerMatches = new ArrayList<>();
    private List<Qualification> qualifications = new ArrayList<>();

    public String getSnippetId() { return snippetId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBaseline() { return baseline; }
    public void setBaseline(String baseline) { this.baseline = baseline; }
    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
    public List<Option> getOptions() { return options; }
    public void setOptions(List<Option> options) { this.options = options; }
    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
    public List<ProviderMatch> getProviderMatches() { return providerMatches; }
    public void setProviderMatches(List<ProviderMatch> providerMatches) { this.providerMatches = providerMatches; }
    public List<Qualification> getQualifications() { return qualifications; }
    public void setQualifications(List<Qualification> qualifications) { this.qualifications = qualifications; }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Option {
        private String id;
        private String title;
        private String subtitle;
        public Option() {}
        public Option(String id, String title, String subtitle) { this.id = id; this.title = title; this.subtitle = subtitle; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSubtitle() { return subtitle; }
        public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Row {
        private String key;
        private String label;
        private String basis; // COURSE_RECORD | PROVIDER_RECORD | YUZEE_ANALYSIS
        private String status; // SHARED | DIFFERENT_RECORDS | INCOMPLETE | UNKNOWN
        private List<List<String>> values = new ArrayList<>();
        private String meaning;
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<List<String>> getValues() { return values; }
        public void setValues(List<List<String>> values) { this.values = values; }
        public String getMeaning() { return meaning; }
        public void setMeaning(String meaning) { this.meaning = meaning; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Qualification {
        private String code;
        private String evidenceId;
        private List<Unit> units = new ArrayList<>();
        private String scope;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public List<Unit> getUnits() { return units; }
        public void setUnits(List<Unit> units) { this.units = units; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Unit {
        private String code;
        private String title;
        private String type;
        public Unit() {}
        public Unit(String code, String title, String type) { this.code = code; this.title = title; this.type = type; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
