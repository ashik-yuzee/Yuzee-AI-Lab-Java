package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Bounded recovery for the explanation review; never falls back to an unreviewed answer.
 * Exact port of yuzee-ai-token-lab/src/services/ReviewRetry.ts.
 *
 * Java mapping of the TS error checks: TimeoutError/AbortError = timeout exceptions and
 * {@link CancellationException}; error.status = the HTTP status in the message
 * ({@link ProviderRecoveryService#httpStatus}); fetch TypeError = a status-less IOException;
 * SyntaxError = {@link JsonProcessingException}.
 */
@Service
public class ReviewRetryService {

    private static final Set<ReviewFailureCode> RETRYABLE = EnumSet.of(
        ReviewFailureCode.TIMEOUT,
        ReviewFailureCode.NETWORK,
        ReviewFailureCode.RATE_LIMIT,
        ReviewFailureCode.PROVIDER,
        ReviewFailureCode.INVALID_RESPONSE
    );

    // ponytail: "pathway" alternatives keep MiniPathwayService's own invalid-output errors retryable;
    // the teaching review never throws them, so its classification is exactly the original's.
    private static final Pattern INVALID_RESPONSE_PATTERN =
        Pattern.compile("Incomplete (?:teaching|pathway) review|Empty (?:teaching|pathway) review|Review output limit");

    /** {value, audit} returned by {@link #runReview(Callable, BooleanSupplier)}. */
    public static final class ReviewOutcome<T> {
        public final T value;
        public final Map<String, Object> audit;

        ReviewOutcome(T value, Map<String, Object> audit) {
            this.value = value;
            this.audit = audit;
        }
    }

    public <T> ReviewOutcome<T> runReview(Callable<T> attempt, BooleanSupplier aborted) throws ReviewFailure {
        int attempts = 0;
        List<ReviewFailureCode> failures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (aborted.getAsBoolean()) {
                failures.add(ReviewFailureCode.CANCELLED);
                throw new ReviewFailure(attempts, failures);
            }
            attempts++;
            try {
                T value = attempt.call();
                return new ReviewOutcome<>(value, audit(attempts, failures));
            } catch (Exception error) {
                ReviewFailureCode code = classify(error, aborted.getAsBoolean());
                failures.add(code);
                if (i == 1 || !RETRYABLE.contains(code)) throw new ReviewFailure(attempts, failures);
                try {
                    Thread.sleep(800);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new ReviewFailure(attempts, failures);
    }

    /** Pre-existing no-abort shape kept for other callers (MiniPathwayService, DetailResearchService). */
    public <T> T runReview(Callable<T> operation) throws ReviewFailure {
        return runReview(operation, () -> false).value;
    }

    public static String reviewFailureCode(Throwable error, boolean aborted) {
        return classify(error, aborted).name();
    }

    public static String reviewFailureMessage(String code) {
        if ("PROVIDER".equals(code) || "RATE_LIMIT".equals(code)) {
            return "Gemini is temporarily unavailable or busy. Your message is saved. Please try again shortly.";
        }
        if ("TIMEOUT".equals(code) || "NETWORK".equals(code)) {
            return "The reply check could not finish because Gemini took too long or the connection failed. Your message is saved. Please try again.";
        }
        return "I couldn’t prepare a clear response. Please try again. Your answer has been kept.";
    }

    public static String reviewFailureMessage(ReviewFailureCode code) {
        return reviewFailureMessage(code == null ? null : code.name());
    }

    static Map<String, Object> audit(int attempts, List<ReviewFailureCode> failures) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("attempts", attempts);
        audit.put("failures", failures.stream().map(Enum::name).toList());
        return audit;
    }

    private static ReviewFailureCode classify(Throwable error, boolean aborted) {
        if (aborted) return ReviewFailureCode.CANCELLED;
        if (error instanceof InterruptedIOException || error instanceof HttpTimeoutException
            || error instanceof TimeoutException || error instanceof CancellationException) {
            return ReviewFailureCode.TIMEOUT;
        }
        Integer status = ProviderRecoveryService.httpStatus(error);
        if (status != null) {
            if (status == 429) return ReviewFailureCode.RATE_LIMIT;
            if (status >= 500 && status <= 599) return ReviewFailureCode.PROVIDER;
            if (status == 400 || status == 401 || status == 403 || status == 404) return ReviewFailureCode.CONFIGURATION;
        }
        if (status == null && error instanceof IOException && !(error instanceof JsonProcessingException)) {
            return ReviewFailureCode.NETWORK;
        }
        String message = error == null ? null : error.getMessage();
        if (error instanceof JsonProcessingException
            || (message != null && INVALID_RESPONSE_PATTERN.matcher(message).find())) {
            return ReviewFailureCode.INVALID_RESPONSE;
        }
        return ReviewFailureCode.UNKNOWN;
    }
}
