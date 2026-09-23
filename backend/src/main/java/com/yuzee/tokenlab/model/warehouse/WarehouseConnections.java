package com.yuzee.tokenlab.model.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Providers/careers/industries/signals expanded from a set of courses (or a location/role search).
 *  Ported from yuzee-ai-token-lab/src/warehouse/types.ts (WarehouseConnections) and linked-data.cjs (createLinkedReader). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WarehouseConnections {
    private LocalOverview localOverview;
    private WarehouseExploration exploration;
    private LocationInfo location = new LocationInfo();
    private List<ProviderProfile> providers = new ArrayList<>();
    private List<Career> careers = new ArrayList<>();
    private List<Industry> industries = new ArrayList<>();
    private List<Signal> signals = new ArrayList<>();
    private List<Relationship> relationships = new ArrayList<>();
    private String scopeNote = "Stored links and dated signals support exploration; they do not guarantee admission, employment, current vacancies or personal eligibility.";

    public LocalOverview getLocalOverview() { return localOverview; }
    public void setLocalOverview(LocalOverview localOverview) { this.localOverview = localOverview; }
    public WarehouseExploration getExploration() { return exploration; }
    public void setExploration(WarehouseExploration exploration) { this.exploration = exploration; }
    public LocationInfo getLocation() { return location; }
    public void setLocation(LocationInfo location) { this.location = location; }
    public List<ProviderProfile> getProviders() { return providers; }
    public void setProviders(List<ProviderProfile> providers) { this.providers = providers; }
    public List<Career> getCareers() { return careers; }
    public void setCareers(List<Career> careers) { this.careers = careers; }
    public List<Industry> getIndustries() { return industries; }
    public void setIndustries(List<Industry> industries) { this.industries = industries; }
    public List<Signal> getSignals() { return signals; }
    public void setSignals(List<Signal> signals) { this.signals = signals; }
    public List<Relationship> getRelationships() { return relationships; }
    public void setRelationships(List<Relationship> relationships) { this.relationships = relationships; }
    public String getScopeNote() { return scopeNote; }
    public void setScopeNote(String scopeNote) { this.scopeNote = scopeNote; }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RegionRef {
        private String key;
        private String name;
        private String tier;
        private String state;
        public RegionRef() {}
        public RegionRef(String key, String name, String tier, String state) { this.key = key; this.name = name; this.tier = tier; this.state = state; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class LocationInfo {
        private String requested = "";
        private RegionRef region;
        private List<RegionRef> candidates = new ArrayList<>();
        public String getRequested() { return requested; }
        public void setRequested(String requested) { this.requested = requested; }
        public RegionRef getRegion() { return region; }
        public void setRegion(RegionRef region) { this.region = region; }
        public List<RegionRef> getCandidates() { return candidates; }
        public void setCandidates(List<RegionRef> candidates) { this.candidates = candidates; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class LocalOverview {
        private String evidenceId;
        private String area;
        private String state;
        private String scope;
        private List<Ancestor> ancestors = new ArrayList<>();
        private Profile profile;
        private List<CommunityGroup> community = new ArrayList<>();
        private boolean limited;
        private String coverage = "Named organisations in the retrieved directory, deduplicated by name, type and place. Not a count of all local companies, current vacancies or confirmed service availability.";
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public List<Ancestor> getAncestors() { return ancestors; }
        public void setAncestors(List<Ancestor> ancestors) { this.ancestors = ancestors; }
        public Profile getProfile() { return profile; }
        public void setProfile(Profile profile) { this.profile = profile; }
        public List<CommunityGroup> getCommunity() { return community; }
        public void setCommunity(List<CommunityGroup> community) { this.community = community; }
        public boolean isLimited() { return limited; }
        public void setLimited(boolean limited) { this.limited = limited; }
        public String getCoverage() { return coverage; }
        public void setCoverage(String coverage) { this.coverage = coverage; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Ancestor {
        private String name;
        private String scope;
        public Ancestor() {}
        public Ancestor(String name, String scope) { this.name = name; this.scope = scope; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Profile {
        private String area;
        private String scope;
        private Double population;
        private String setting;
        private String period;
        private String source;
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public Double getPopulation() { return population; }
        public void setPopulation(Double population) { this.population = population; }
        public String getSetting() { return setting; }
        public void setSetting(String setting) { this.setting = setting; }
        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class CommunityGroup {
        private String key;
        private String label;
        private int recordedCount;
        private List<Example> examples = new ArrayList<>();
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public int getRecordedCount() { return recordedCount; }
        public void setRecordedCount(int recordedCount) { this.recordedCount = recordedCount; }
        public List<Example> getExamples() { return examples; }
        public void setExamples(List<Example> examples) { this.examples = examples; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Example {
        private String name;
        private String type;
        private String source;
        private String updatedAt;
        private String area;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Campus {
        private String name;
        private String town;
        private String state;
        private String postcode;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTown() { return town; }
        public void setTown(String town) { this.town = town; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getPostcode() { return postcode; }
        public void setPostcode(String postcode) { this.postcode = postcode; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ProviderProfile {
        private String id;
        private String evidenceId;
        private String name;
        private String type;
        private String higherEducationCode;
        private String city;
        private String state;
        private List<Campus> campuses = new ArrayList<>();
        private List<String> support = new ArrayList<>();
        private Map<String, Object> higherEducationFunding;
        private List<Map<String, Object>> funding = new ArrayList<>();
        private List<String> courseIds = new ArrayList<>();
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getHigherEducationCode() { return higherEducationCode; }
        public void setHigherEducationCode(String higherEducationCode) { this.higherEducationCode = higherEducationCode; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public List<Campus> getCampuses() { return campuses; }
        public void setCampuses(List<Campus> campuses) { this.campuses = campuses; }
        public List<String> getSupport() { return support; }
        public void setSupport(List<String> support) { this.support = support; }
        public Map<String, Object> getHigherEducationFunding() { return higherEducationFunding; }
        public void setHigherEducationFunding(Map<String, Object> higherEducationFunding) { this.higherEducationFunding = higherEducationFunding; }
        public List<Map<String, Object>> getFunding() { return funding; }
        public void setFunding(List<Map<String, Object>> funding) { this.funding = funding; }
        public List<String> getCourseIds() { return courseIds; }
        public void setCourseIds(List<String> courseIds) { this.courseIds = courseIds; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class CourseLink {
        private String courseId;
        private String courseName;
        private String method;
        private Double confidence;
        public CourseLink() {}
        public CourseLink(String courseId, String courseName, String method, Double confidence) {
            this.courseId = courseId; this.courseName = courseName; this.method = method; this.confidence = confidence;
        }
        public String getCourseId() { return courseId; }
        public void setCourseId(String courseId) { this.courseId = courseId; }
        public String getCourseName() { return courseName; }
        public void setCourseName(String courseName) { this.courseName = courseName; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Career {
        private String id;
        private String evidenceId;
        private String title;
        private String description;
        private List<String> tasks = new ArrayList<>();
        private List<String> skills = new ArrayList<>();
        private List<String> workStyles = new ArrayList<>();
        private List<CourseLink> courseLinks = new ArrayList<>();
        private String profileScope;
        private String profileSource;
        private String profileMethod;
        private String groupCode;
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
        public List<String> getSkills() { return skills; }
        public void setSkills(List<String> skills) { this.skills = skills; }
        public List<String> getWorkStyles() { return workStyles; }
        public void setWorkStyles(List<String> workStyles) { this.workStyles = workStyles; }
        public List<CourseLink> getCourseLinks() { return courseLinks; }
        public void setCourseLinks(List<CourseLink> courseLinks) { this.courseLinks = courseLinks; }
        public String getProfileScope() { return profileScope; }
        public void setProfileScope(String profileScope) { this.profileScope = profileScope; }
        public String getProfileSource() { return profileSource; }
        public void setProfileSource(String profileSource) { this.profileSource = profileSource; }
        public String getProfileMethod() { return profileMethod; }
        public void setProfileMethod(String profileMethod) { this.profileMethod = profileMethod; }
        public String getGroupCode() { return groupCode; }
        public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Industry {
        private String id;
        private String evidenceId;
        private String name;
        @JsonInclude(JsonInclude.Include.NON_NULL) private String courseId;
        @JsonInclude(JsonInclude.Include.NON_NULL) private String careerId;
        private String method;
        private String scope;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCourseId() { return courseId; }
        public void setCourseId(String courseId) { this.courseId = courseId; }
        public String getCareerId() { return careerId; }
        public void setCareerId(String careerId) { this.careerId = careerId; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }




    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Signal {
        private String id;
        private String evidenceId;
        private String kind;
        @JsonInclude(JsonInclude.Include.NON_NULL) private String careerId;
        private String title;
        private String text;
        private String scope;
        private String region;
        private String period;
        private String source;
        private String method;
        private boolean localMatch;
        @JsonInclude(JsonInclude.Include.NON_NULL) private String updatedAt;
        @JsonInclude(JsonInclude.Include.NON_NULL) private Map<String, Object> metrics;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getCareerId() { return careerId; }
        public void setCareerId(String careerId) { this.careerId = careerId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public boolean isLocalMatch() { return localMatch; }
        public void setLocalMatch(boolean localMatch) { this.localMatch = localMatch; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
    }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Relationship {
        private String from;
        private String to;
        private String relation;
        private String method;
        private Double confidence;
        public Relationship() {}
        public Relationship(String from, String to, String relation, String method, Double confidence) {
            this.from = from; this.to = to; this.relation = relation; this.method = method; this.confidence = confidence;
        }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getRelation() { return relation; }
        public void setRelation(String relation) { this.relation = relation; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }
}
