package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * service.ts's {@code Model} type ({@code (request, signal) => Promise<any>}): the Interactions API call the
 * query planner sends its request through. server.ts sets it to callObjectiveModel.
 */
@FunctionalInterface
public interface WarehousePlanner {
    /**
     * Sends the Interactions request body and returns the provider's response body, bounded by {@code timeoutMs};
     * a completed {@code signal} (the AbortSignal) cancels it.
     */
    JsonNode call(JsonNode request, long timeoutMs, java.util.concurrent.CompletableFuture<?> signal) throws Exception;
}
