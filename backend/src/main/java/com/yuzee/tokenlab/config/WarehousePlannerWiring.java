package com.yuzee.tokenlab.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.service.GeminiModelRegistry;
import com.yuzee.tokenlab.service.GeminiService;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Wires {@link GeminiService} into {@link WarehouseService}'s planner seam. Kept as a separate
 * component (rather than a constructor dependency on WarehouseService) exactly as suggested in
 * WarehousePlanner's javadoc, since WarehouseService was deliberately built without a GeminiService
 * dependency.
 */
@Component
public class WarehousePlannerWiring {

    private final WarehouseService warehouseService;
    private final GeminiService geminiService;

    public WarehousePlannerWiring(WarehouseService warehouseService, GeminiService geminiService) {
        this.warehouseService = warehouseService;
        this.geminiService = geminiService;
    }

    @PostConstruct
    void wire() {
        var schema = plannerResponseSchema();
        warehouseService.setPlanner((systemInstruction, inputJson) ->
            geminiService.generateJson(GeminiModelRegistry.DEFAULT_MODEL_ID, systemInstruction, inputJson, 1000, schema).text);
    }

    /**
     * Structured-output schema for WarehouseService.retrieve()'s query planner call. Without this,
     * Gemini only has the system instruction's prose to go on for field names and, in practice,
     * does not reliably land on the exact keys retrieve() requires (observed: it returned
     * {@code target} instead of the required {@code action} key, failing validation every time).
     * Field names/enums here must stay in lockstep with the ACTIONS/FACETS/STATES sets and the
     * validation checks in {@code WarehouseService.retrieve()}.
     */
    private static ObjectNode plannerResponseSchema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode schema = f.objectNode();
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");

        props.set("action", enumString(f, "NONE", "COURSES", "CAREERS", "INDUSTRY", "LOCAL", "SKILLS_JOBS"));
        props.set("queries", stringArray(f));
        props.set("reuse_selected", f.objectNode().put("type", "BOOLEAN"));
        props.set("comparison", f.objectNode().put("type", "BOOLEAN"));
        props.set("provider_queries", stringArray(f));
        props.set("role_queries", stringArray(f));
        props.set("skill_queries", stringArray(f));
        props.set("job_queries", stringArray(f));
        props.set("occupation_queries", stringArray(f));
        props.set("industry_queries", stringArray(f));
        props.set("facets", f.objectNode().put("type", "ARRAY")
            .set("items", enumString(f, "PROVIDER", "CAREERS", "INDUSTRY", "LOCAL", "FUNDING", "SKILLS", "JOBS", "LEARNING")));

        ObjectNode location = f.objectNode();
        location.put("type", "OBJECT");
        ObjectNode locationProps = location.putObject("properties");
        locationProps.set("name", f.objectNode().put("type", "STRING"));
        locationProps.set("postcode", f.objectNode().put("type", "STRING"));
        ObjectNode stateEnum = enumString(f, "ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA");
        stateEnum.put("nullable", true); // Gemini's schema validator rejects "" as an enum member; use null for "no state given" instead
        locationProps.set("state", stateEnum);
        props.set("location", location);

        ArrayNode required = schema.putArray("required");
        required.add("action").add("queries").add("reuse_selected");
        return schema;
    }

    private static ObjectNode enumString(JsonNodeFactory f, String... values) {
        ObjectNode node = f.objectNode();
        node.put("type", "STRING");
        ArrayNode enumArr = node.putArray("enum");
        for (String v : values) enumArr.add(v);
        return node;
    }

    private static ObjectNode stringArray(JsonNodeFactory f) {
        ObjectNode node = f.objectNode();
        node.put("type", "ARRAY");
        node.set("items", f.objectNode().put("type", "STRING"));
        return node;
    }
}
