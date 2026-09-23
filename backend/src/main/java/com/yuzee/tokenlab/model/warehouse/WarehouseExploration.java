package com.yuzee.tokenlab.model.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Skills -> occupations -> learning/jobs exploration bundle. Ported from
 *  yuzee-ai-token-lab/src/warehouse/types.ts (WarehouseExploration) and opportunities.cjs (createOpportunityReader). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WarehouseExploration {
    private List<Role> roles = new ArrayList<>();
    private List<SkillRef> skills = new ArrayList<>();
    private List<LearningLink> learning = new ArrayList<>();
    private List<JobAd> jobs = new ArrayList<>();
    private List<ObservedSkill> observedSkills = new ArrayList<>();
    private Geography geography;
    private Coverage coverage = new Coverage();

    public List<Role> getRoles() { return roles; }
    public void setRoles(List<Role> roles) { this.roles = roles; }
    public List<SkillRef> getSkills() { return skills; }
    public void setSkills(List<SkillRef> skills) { this.skills = skills; }
    public List<LearningLink> getLearning() { return learning; }
    public void setLearning(List<LearningLink> learning) { this.learning = learning; }
    public List<JobAd> getJobs() { return jobs; }
    public void setJobs(List<JobAd> jobs) { this.jobs = jobs; }
    public List<ObservedSkill> getObservedSkills() { return observedSkills; }
    public void setObservedSkills(List<ObservedSkill> observedSkills) { this.observedSkills = observedSkills; }
    public Geography getGeography() { return geography; }
    public void setGeography(Geography geography) { this.geography = geography; }
    public Coverage getCoverage() { return coverage; }
    public void setCoverage(Coverage coverage) { this.coverage = coverage; }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Role {
        private String id;
        private String evidenceId;
        private String title;
        private String description;
        private List<String> tasks = new ArrayList<>();
        private List<String> matchedSkills = new ArrayList<>();
        private List<RoleSkill> skills = new ArrayList<>();
        private List<Mapping> mappings = new ArrayList<>();
        private String source;
        private String scope;
        private String matchReason;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getTasks() { return tasks; }
        public void setTasks(List<String> tasks) { this.tasks = tasks; }
        public List<String> getMatchedSkills() { return matchedSkills; }
        public void setMatchedSkills(List<String> matchedSkills) { this.matchedSkills = matchedSkills; }
        public List<RoleSkill> getSkills() { return skills; }
        public void setSkills(List<RoleSkill> skills) { this.skills = skills; }
        public List<Mapping> getMappings() { return mappings; }
        public void setMappings(List<Mapping> mappings) { this.mappings = mappings; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getMatchReason() { return matchReason; }
        public void setMatchReason(String matchReason) { this.matchReason = matchReason; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RoleSkill {
        private String id;
        private String name;
        private String description;
        public RoleSkill() {}
        public RoleSkill(String id, String name, String description) { this.id = id; this.name = name; this.description = description; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Mapping {
        private String anzscoCode;
        private String anzscoTitle;
        private String method;
        private Double confidence;
        public Mapping() {}
        public Mapping(String anzscoCode, String anzscoTitle, String method, Double confidence) {
            this.anzscoCode = anzscoCode; this.anzscoTitle = anzscoTitle; this.method = method; this.confidence = confidence;
        }
        @com.fasterxml.jackson.annotation.JsonProperty("anzsco_code") public String getAnzscoCode() { return anzscoCode; }
        public void setAnzscoCode(String anzscoCode) { this.anzscoCode = anzscoCode; }
        @com.fasterxml.jackson.annotation.JsonProperty("anzsco_title") public String getAnzscoTitle() { return anzscoTitle; }
        public void setAnzscoTitle(String anzscoTitle) { this.anzscoTitle = anzscoTitle; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class SkillRef {
        private String id;
        private String name;
        private String description;
        private List<String> roleIds = new ArrayList<>();
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getRoleIds() { return roleIds; }
        public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class LearningLink {
        private String id;
        private String evidenceId;
        private String code;
        private String skill;
        private String kind;
        private String method;
        private String query;
        private List<CourseRef> courses = new ArrayList<>();
        private String scope;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getSkill() { return skill; }
        public void setSkill(String skill) { this.skill = skill; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public List<CourseRef> getCourses() { return courses; }
        public void setCourses(List<CourseRef> courses) { this.courses = courses; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class CourseRef {
        private String id;
        private String name;
        private String provider;
        public CourseRef() {}
        public CourseRef(String id, String name, String provider) { this.id = id; this.name = name; this.provider = provider; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class JobAd {
        private String id;
        private String evidenceId;
        private String title;
        private String company;
        private String area;
        private List<String> skills = new ArrayList<>();
        private List<String> requirements = new ArrayList<>();
        private String description;
        private String employmentType;
        private String workMode;
        private String salary;
        private String postedAt;
        private String updatedAt;
        private String url;
        private String source;
        private final String availability = "NOT_CONFIRMED_CURRENT";
        private Geography geography;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getCompany() { return company; }
        public void setCompany(String company) { this.company = company; }
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public List<String> getSkills() { return skills; }
        public void setSkills(List<String> skills) { this.skills = skills; }
        public List<String> getRequirements() { return requirements; }
        public void setRequirements(List<String> requirements) { this.requirements = requirements; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getEmploymentType() { return employmentType; }
        public void setEmploymentType(String employmentType) { this.employmentType = employmentType; }
        public String getWorkMode() { return workMode; }
        public void setWorkMode(String workMode) { this.workMode = workMode; }
        public String getSalary() { return salary; }
        public void setSalary(String salary) { this.salary = salary; }
        public String getPostedAt() { return postedAt; }
        public void setPostedAt(String postedAt) { this.postedAt = postedAt; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getAvailability() { return availability; }
        public Geography getGeography() { return geography; }
        public void setGeography(Geography geography) { this.geography = geography; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ObservedSkill {
        private String id;
        private String evidenceId;
        private String name;
        private int count;
        private int denominator;
        private String scope;
        private String geography;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public int getDenominator() { return denominator; }
        public void setDenominator(int denominator) { this.denominator = denominator; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getGeography() { return geography; }
        public void setGeography(String geography) { this.geography = geography; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Geography {
        private String scope;
        private String name;
        private boolean localMatch;
        public Geography() {}
        public Geography(String scope, String name, boolean localMatch) { this.scope = scope; this.name = name; this.localMatch = localMatch; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isLocalMatch() { return localMatch; }
        public void setLocalMatch(boolean localMatch) { this.localMatch = localMatch; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Coverage {
        private int sampleSize;
        private int withStructuredSkills;
        private int returnedJobs;
        private final String localSkillDemand = "NOT_ESTABLISHED";
        private String note = "Job records are dated observations. Skill counts describe only the retrieved sample, not overall local demand. Missing skill fields do not mean a skill is not requested. Role matching is exploratory, not evidence the user has a skill or is eligible for a job.";
        public int getSampleSize() { return sampleSize; }
        public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
        public int getWithStructuredSkills() { return withStructuredSkills; }
        public void setWithStructuredSkills(int withStructuredSkills) { this.withStructuredSkills = withStructuredSkills; }
        public int getReturnedJobs() { return returnedJobs; }
        public void setReturnedJobs(int returnedJobs) { this.returnedJobs = returnedJobs; }
        public String getLocalSkillDemand() { return localSkillDemand; }
        public String getNote() { return note; }
    }
}
