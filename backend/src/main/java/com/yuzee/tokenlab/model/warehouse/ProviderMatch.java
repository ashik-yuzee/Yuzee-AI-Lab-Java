package com.yuzee.tokenlab.model.warehouse;

import java.util.ArrayList;
import java.util.List;

/** Result of matching one free-text provider query against live_institutions. Ported from
 *  yuzee-ai-token-lab/src/warehouse/types.ts (ProviderMatch) and provider-data.cjs (matches()). */
public class ProviderMatch {
    private String query;
    private String status; // MATCHED | AMBIGUOUS | NOT_FOUND
    private List<ProviderRecord> providers = new ArrayList<>();
    private String resolution;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<ProviderRecord> getProviders() { return providers; }
    public void setProviders(List<ProviderRecord> providers) { this.providers = providers; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public static class ProviderRecord {
        private String id;
        private String evidenceId;
        private String name;
        private String rtoCode;
        private String type;
        private String area;
        private String description;
        private List<String> support = new ArrayList<>();
        private String updatedAt;
        private String scope;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRtoCode() { return rtoCode; }
        public void setRtoCode(String rtoCode) { this.rtoCode = rtoCode; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getSupport() { return support; }
        public void setSupport(List<String> support) { this.support = support; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }
}
