package com.yuzee.tokenlab.config;

import com.yuzee.tokenlab.service.ObjectiveService;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** server.ts {@code warehouseService.setPlanner(callObjectiveModel)}. */
@Component
public class WarehousePlannerWiring {

    private final WarehouseService warehouseService;
    private final ObjectiveService objectiveService;

    public WarehousePlannerWiring(WarehouseService warehouseService, ObjectiveService objectiveService) {
        this.warehouseService = warehouseService;
        this.objectiveService = objectiveService;
    }

    @PostConstruct
    void wire() {
        warehouseService.setPlanner((request, timeout, signal) -> objectiveService.callObjectiveModel(request, timeout, signal));
    }
}
