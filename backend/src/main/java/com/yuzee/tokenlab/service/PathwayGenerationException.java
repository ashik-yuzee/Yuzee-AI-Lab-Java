package com.yuzee.tokenlab.service;

/**
 * Port of miniPathway/service.ts's {@code PathwayError}: {@link #getMessage()} is a short, plain,
 * user-facing sentence and {@link #getStatus()} the HTTP-style status sent in the SSE error event
 * (default 400, as in the original).
 */
public class PathwayGenerationException extends RuntimeException {

    private final int status;

    public PathwayGenerationException(String message) {
        this(message, 400);
    }

    public PathwayGenerationException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
