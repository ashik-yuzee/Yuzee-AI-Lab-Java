package com.yuzee.tokenlab.config;

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
        warehouseService.setPlanner((systemInstruction, inputJson) ->
            geminiService.generateJson(GeminiModelRegistry.DEFAULT_MODEL_ID, systemInstruction, inputJson, 1000).text);
    }
}
