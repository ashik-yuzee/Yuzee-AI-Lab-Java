package com.yuzee.tokenlab.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded 2-attempt retry for the explanation/teaching review call, ported from
 * yuzee-ai-token-lab/src/services/ReviewRetry.ts (runReview). On final failure this throws
 * {@link ReviewFailure} with the full attempt/failure-code audit trail -- callers must never
 * catch that and silently fall back to an unreviewed answer; the failure has to be surfaced to
 * the user (see {@link #reviewFailureMessage}).
 */
@Service
public class ReviewRetryService {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 800L;

    /** Failure codes worth a single retry; anything else fails fast. */
    private static final Set<ReviewFailureCode> RETRYABLE = EnumSet.of(
        ReviewFailureCode.TIMEOUT,
        ReviewFailureCode.NETWORK,
        ReviewFailureCode.RATE_LIMIT,
        ReviewFailureCode.PROVIDER,
        ReviewFailureCode.INVALID_RESPONSE
    );

    private static final Pattern STATUS_PATTERN = Pattern.compile("\\b(4\\d{2}|5\\d{2})\\b");
    private static final Pattern INVALID_RESPONSE_PATTERN =
        Pattern.compile("Incomplete (?:teaching|pathway) review|Empty (?:teaching|pathway) review|Review output limit");

    /**
     * Runs {@code operation}, retrying exactly once (800ms fixed delay) for a
     * TIMEOUT/NETWORK/RATE_LIMIT/PROVIDER/INVALID_RESPONSE failure. Any other failure code, or
     * a second failure of any kind, throws {@link ReviewFailure} with the full audit trail.
     */
    public <T> T runReview(Callable<T> operation) throws ReviewFailure {
        List<ReviewFailureCode> failures = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return operation.call();
            } catch (Exception error) {
                ReviewFailureCode code = classify(error);
                failures.add(code);
                boolean isLastAttempt = attempt == MAX_ATTEMPTS - 1;
                if (isLastAttempt || !RETRYABLE.contains(code)) {
                    throw new ReviewFailure(attempt + 1, failures);
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.add(ReviewFailureCode.CANCELLED);
                    throw new ReviewFailure(attempt + 1, failures);
                }
            }
        }
        // Unreachable: the loop above always returns or throws.
        throw new ReviewFailure(MAX_ATTEMPTS, failures);
    }

    /** User-facing message for a given failure code, mirroring ReviewRetry.ts#reviewFailureMessage. */
    public static String reviewFailureMessage(ReviewFailureCode code) {
        if (code == ReviewFailureCode.PROVIDER || code == ReviewFailureCode.RATE_LIMIT) {
            return "Gemini is temporarily unavailable or busy. Your message is saved. Please try again shortly.";
        }
        if (code == ReviewFailureCode.TIMEOUT || code == ReviewFailureCode.NETWORK) {
            return "The reply check could not finish because Gemini took too long or the connection failed. Your message is saved. Please try again.";
        }
        return "I couldn't prepare a clear response. Please try again. Your answer has been kept.";
    }

    private static ReviewFailureCode classify(Exception error) {
        if (error instanceof InterruptedException) {
            return ReviewFailureCode.CANCELLED;
        }
        if (error instanceof SocketTimeoutException || error instanceof TimeoutException) {
            return ReviewFailureCode.TIMEOUT;
        }
        Integer status = extractStatus(error.getMessage());
        if (status != null) {
            if (status == 429) {
                return ReviewFailureCode.RATE_LIMIT;
            }
            if (status >= 500 && status <= 599) {
                return ReviewFailureCode.PROVIDER;
            }
            if (status == 400 || status == 401 || status == 403 || status == 404) {
                return ReviewFailureCode.CONFIGURATION;
            }
        }
        if (isInvalidResponse(error)) {
            return ReviewFailureCode.INVALID_RESPONSE;
        }
        if (error instanceof IOException) {
            return ReviewFailureCode.NETWORK;
        }
        return ReviewFailureCode.UNKNOWN;
    }

    private static boolean isInvalidResponse(Exception error) {
        if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return true;
        }
        String message = error.getMessage();
        return message != null && INVALID_RESPONSE_PATTERN.matcher(message).find();
    }

    private static Integer extractStatus(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = STATUS_PATTERN.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
