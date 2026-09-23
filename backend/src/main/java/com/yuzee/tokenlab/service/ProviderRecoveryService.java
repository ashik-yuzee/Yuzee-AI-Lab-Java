package com.yuzee.tokenlab.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retry only stream creation, before any response content can be delivered.
 * Exact port of yuzee-ai-token-lab/src/services/ProviderRecovery.ts (openProviderStream).
 *
 * Java shape note: the TS version reads the SDK error's numeric {@code status}. Errors in this
 * codebase carry the HTTP status in their message (e.g. "Gemini error 503: ..."), so
 * {@link #httpStatus} extracts it from there. An AbortSignal is modelled as a
 * {@link BooleanSupplier}; an abort is surfaced as {@link CancellationException} (AbortError).
 */
@Service
public class ProviderRecoveryService {

    private static final List<Integer> TRANSIENT_STATUSES = List.of(429, 500, 502, 503, 504);
    private static final Pattern DAILY_QUOTA_PATTERN =
        Pattern.compile("per.day|daily.*quota|requests_per_day|tokens_per_day", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_PATTERN =
        Pattern.compile("(?:\\b(?:error|status|HTTP)\\s*:?\\s*|\"code\"\\s*:\\s*)(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    public <T> T openProviderStream(Callable<T> open, BooleanSupplier aborted, Runnable onRetry) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            throwIfAborted(aborted);
            try {
                return open.call();
            } catch (Exception error) {
                if (aborted.getAsBoolean()) throw error;
                Integer status = httpStatus(error);
                String message = error.getMessage();
                boolean dailyQuota = DAILY_QUOTA_PATTERN.matcher(message != null ? message : "").find();
                boolean transientFailure = status != null && TRANSIENT_STATUSES.contains(status) && !dailyQuota;
                if (attempt != 0 || !transientFailure) throw error;
                onRetry.run();
                waitForRetry(aborted);
            }
        }
        throw new Exception("Provider recovery exhausted");
    }

    /** Pre-existing no-abort shape kept for other callers. */
    public <T> T openWithRecovery(Callable<T> attemptOpen, Runnable onRetry) throws Exception {
        return openProviderStream(attemptOpen, () -> false, onRetry);
    }

    /** The HTTP status carried by an error message ("Gemini error 503: ...", {"code":503}), or null. */
    static Integer httpStatus(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) return null;
        Matcher matcher = STATUS_PATTERN.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static void throwIfAborted(BooleanSupplier aborted) {
        if (aborted.getAsBoolean()) throw new CancellationException("This operation was aborted");
    }

    private static void waitForRetry(BooleanSupplier aborted) {
        throwIfAborted(aborted);
        long deadline = System.currentTimeMillis() + 1500;
        // ponytail: 25ms abort polling stands in for the AbortSignal listener.
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(25, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("This operation was aborted");
            }
            throwIfAborted(aborted);
        }
    }
}
