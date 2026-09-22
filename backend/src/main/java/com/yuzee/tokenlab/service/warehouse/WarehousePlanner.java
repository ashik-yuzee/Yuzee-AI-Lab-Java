package com.yuzee.tokenlab.service.warehouse;

/**
 * The LLM call {@code WarehouseService.retrieve()} needs to turn free text into a
 * {@link com.yuzee.tokenlab.model.warehouse.WarehouseQueryPlan}. Ported from the {@code Model} type
 * and {@code setPlanner()} in yuzee-ai-token-lab/src/warehouse/service.ts.
 * <p>
 * Deliberately decoupled from {@code GeminiService} (a NEW file must not edit or depend on wiring
 * that belongs to the controllers this task must not touch). A future engineer wires this with, e.g.:
 * <pre>
 *   warehouseService.setPlanner((systemInstruction, inputJson) -&gt;
 *       geminiService.generateJson(GeminiService.DEFAULT_MODEL, systemInstruction, inputJson, 1000).text);
 * </pre>
 */
@FunctionalInterface
public interface WarehousePlanner {
    /** Returns the raw JSON text produced by the planning model for the given input. */
    String plan(String systemInstruction, String inputJson) throws Exception;
}
