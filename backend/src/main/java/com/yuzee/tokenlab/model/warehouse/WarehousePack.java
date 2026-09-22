package com.yuzee.tokenlab.model.warehouse;

import java.util.ArrayList;
import java.util.List;

/** Result of a warehouse lookup/retrieve call. Ported from yuzee-ai-token-lab/src/warehouse/types.ts (WarehousePack)
 *  and service.ts (the `base()` helper). */
public class WarehousePack {
    private String status; // READY | NO_MATCH | NOT_NEEDED | UNAVAILABLE | PREPARING
    private String message = "";
    private List<String> queries = new ArrayList<>();
    private List<WarehouseCourse> courses = new ArrayList<>();
    private String retrievedAt;
    private final String sourcePolicy = "USER_APPROVED_CATALOGUE";
    private WarehouseConnections connected;
    private WarehouseComparison comparison;

    public static WarehousePack of(String status, String message, List<String> queries) {
        WarehousePack pack = new WarehousePack();
        pack.status = status;
        pack.message = message == null ? "" : message;
        pack.queries = queries == null ? new ArrayList<>() : queries;
        pack.retrievedAt = java.time.Instant.now().toString();
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
