package com.yuzee.tokenlab.model.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Result of a warehouse lookup/retrieve call. Ported from yuzee-ai-token-lab/src/warehouse/types.ts (WarehousePack)
 *  and service.ts (the `base()` helper). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WarehousePack {
    private String status; // READY | NO_MATCH | NOT_NEEDED | UNAVAILABLE | PREPARING
    private String message = "";
    private List<String> queries = new ArrayList<>();
    private List<WarehouseCourse> courses = new ArrayList<>();
    private String retrievedAt;
    private final String sourcePolicy = "USER_APPROVED_CATALOGUE";
    @JsonInclude(JsonInclude.Include.NON_NULL) private WarehouseConnections connected;
    @JsonInclude(JsonInclude.Include.NON_NULL) private WarehouseComparison comparison;

    public static WarehousePack of(String status, String message, List<String> queries) {
        WarehousePack pack = new WarehousePack();
        pack.status = status;
        pack.message = message == null ? "" : message;
        pack.queries = queries == null ? new ArrayList<>() : queries;
        // JS toISOString(): always millisecond precision.
        pack.retrievedAt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        return pack;
    }

    public static WarehousePack of(String status) {
        return of(status, "", new ArrayList<>());
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<String> getQueries() { return queries; }
    public void setQueries(List<String> queries) { this.queries = queries; }
    public List<WarehouseCourse> getCourses() { return courses; }
    public void setCourses(List<WarehouseCourse> courses) { this.courses = courses; }
    public String getRetrievedAt() { return retrievedAt; }
    public void setRetrievedAt(String retrievedAt) { this.retrievedAt = retrievedAt; }
    public String getSourcePolicy() { return sourcePolicy; }
    public WarehouseConnections getConnected() { return connected; }
    public void setConnected(WarehouseConnections connected) { this.connected = connected; }
    public WarehouseComparison getComparison() { return comparison; }
    public void setComparison(WarehouseComparison comparison) { this.comparison = comparison; }
}
