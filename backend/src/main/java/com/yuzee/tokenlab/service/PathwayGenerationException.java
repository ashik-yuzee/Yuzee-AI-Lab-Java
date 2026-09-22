package com.yuzee.tokenlab.service;

/**
 * Thrown by {@link MiniPathwayService#generate} when a mini pathway could not be produced --
 * Gemini is not configured, the stream was cut off before completion, or the report still failed
 * validation after the one allowed regenerate-with-feedback retry. Ported from miniPathway/
 * service.ts's {@code PathwayError}: {@link #getMessage()} is already a short, plain, user-facing
 * sentence -- show it directly rather than the underlying cause.
 */
public class PathwayGenerationException extends RuntimeException {

    public PathwayGenerationException(String message) {
        super(message);
    }

    public PathwayGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
