package com.yuzee.tokenlab.model.warehouse;

import java.util.ArrayList;
import java.util.List;

/** Bounded query plan for one lookup, either supplied directly or produced by the LLM planner in
 *  {@code WarehouseService.retrieve()}. Ported from yuzee-ai-token-lab/src/warehouse/service.ts (WarehouseQueryPlan). */
public class WarehouseQueryPlan {
    private boolean comparison;
    private List<String> providerQueries = new ArrayList<>();
    private List<String> occupationQueries = new ArrayList<>();
    private List<String> skillQueries = new ArrayList<>();
    private List<String> roleQueries = new ArrayList<>();
    private List<String> roleIds; // null = not supplied (JS Array.isArray(plan.role_ids) is false)
    private boolean candidatePool;
    private List<String> jobQueries = new ArrayList<>();
    private List<String> industryQueries = new ArrayList<>();
    private LocationQuery location;
    private List<String> facets; // null = not supplied: every facet applies

    public boolean isComparison() { return comparison; }
    public void setComparison(boolean comparison) { this.comparison = comparison; }
    public List<String> getProviderQueries() { return providerQueries; }
    public void setProviderQueries(List<String> providerQueries) { this.providerQueries = providerQueries; }
    public List<String> getOccupationQueries() { return occupationQueries; }
    public void setOccupationQueries(List<String> occupationQueries) { this.occupationQueries = occupationQueries; }
    public List<String> getSkillQueries() { return skillQueries; }
    public void setSkillQueries(List<String> skillQueries) { this.skillQueries = skillQueries; }
    public List<String> getRoleQueries() { return roleQueries; }
    public void setRoleQueries(List<String> roleQueries) { this.roleQueries = roleQueries; }
    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }
    public boolean isCandidatePool() { return candidatePool; }
    public void setCandidatePool(boolean candidatePool) { this.candidatePool = candidatePool; }
    public List<String> getJobQueries() { return jobQueries; }
    public void setJobQueries(List<String> jobQueries) { this.jobQueries = jobQueries; }
    public List<String> getIndustryQueries() { return industryQueries; }
    public void setIndustryQueries(List<String> industryQueries) { this.industryQueries = industryQueries; }
    public LocationQuery getLocation() { return location; }
    public void setLocation(LocationQuery location) { this.location = location; }
    public List<String> getFacets() { return facets; }
    public void setFacets(List<String> facets) { this.facets = facets; }

    /** Whether facet gating should behave as "all facets" (mirrors the .cjs `all = !Array.isArray(plan.facets)`). */
    public boolean hasExplicitFacets() { return facets != null; }

    public static class LocationQuery {
        private String name = "";
        private String postcode = "";
        private String state = "";
        public LocationQuery() {}
        public LocationQuery(String name, String postcode, String state) { this.name = name; this.postcode = postcode; this.state = state; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPostcode() { return postcode; }
        public void setPostcode(String postcode) { this.postcode = postcode; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }
}
