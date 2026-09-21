package com.yuzee.tokenlab.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Thin retry wrapper around opening a provider stream/call, ported from
 * yuzee-ai-token-lab/src/services/ProviderRecovery.ts (openProviderStream). Retries only the
 * *opening* of a stream/request -- never anything once content may already have been
 * delivered to the client.
 *
 * Java shape note: the TS version wraps a Promise-returning {@code open()} and simply retries
 * it, because a rejected promise carries a typed {@code status}/{@code message}. GeminiService
 * in this codebase is callback-based ({@code streamGenerate(..., onChunk, onDone, onError)})
 * rather than something that returns a stream object to retry, and its errors are plain
 * {@link RuntimeException}/{@link java.io.IOException} with the HTTP status folded into the
 * message (e.g. "Gemini error 503: ..."), matching how {@code GeminiService} already reports
 * failures. So this is exposed as a generic {@link Callable}-based wrapper: pass a
 * {@code Callable} that attempts to open the stream/request and throws on failure (e.g. by
 * turning GeminiService's {@code onError} callback into a thrown exception for the duration of
 * that attempt). Call it like:
 *
 * <pre>{@code
 * providerRecoveryService.openWithRecovery(() -> {
 *     // attempt to open the stream; throw if GeminiService's onError fires before any chunk
 *     return openedStreamHandleOrNull;
 * }, () -> log.info("retrying provider stream open"));
 * }</pre>
 */
@Service
public class ProviderRecoveryService {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1500L;

    private static final Pattern TRANSIENT_STATUS_PATTERN = Pattern.compile("\\b(429|500|502|503|504)\\b");
    private static final Pattern DAILY_QUOTA_PATTERN =
        Pattern.compile("per.day|daily.*quota|requests_per_day|tokens_per_day", Pattern.CASE_INSENSITIVE);

    /**
     * Attempts {@code attemptOpen} once; on a transient failure (HTTP 429/500/502/503/504 that
     * is not a daily-quota-exceeded error) retries exactly once more after a fixed 1.5s delay.
     * Any other failure, or a second failure, is rethrown as-is.
     *
     * @param attemptOpen opens the stream/request, throwing on failure. Its return value (if
     *                     any) is returned on success.
     * @param onRetry      invoked (on the calling thread) right before the single retry sleep;
     *                     use it for logging/metrics. Pass {@code () -> {}} if not needed.
     */
    public <T> T openWithRecovery(Callable<T> attemptOpen, Runnable onRetry) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return attemptOpen.call();
            } catch (Exception error) {
                lastError = error;
                boolean transientFailure = isTransient(error) && !isDailyQuotaExceeded(error);
                if (attempt == MAX_ATTEMPTS - 1 || !transientFailure) {
                    throw error;
                }
                onRetry.run();
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("Provider recovery exhausted");
    }

    private static boolean isTransient(Throwable error) {
        String message = error.getMessage();
        return message != null && TRANSIENT_STATUS_PATTERN.matcher(message).find();
    }

    private static boolean isDailyQuotaExceeded(Throwable error) {
        String message = error.getMessage();
        return message != null && DAILY_QUOTA_PATTERN.matcher(message).find();
    }
}
