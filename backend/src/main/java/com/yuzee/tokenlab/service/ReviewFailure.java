package com.yuzee.tokenlab.service;

import java.util.List;

/**
 * Thrown by {@link ReviewRetryService#runReview} when the bounded retry is exhausted. Carries
 * a full audit trail (attempt count + one failure code per attempt) so the caller can log or
 * surface exactly what happened -- callers must never silently fall back to an unreviewed
 * answer on this failure; it must be surfaced. Ported from
 * yuzee-ai-token-lab/src/services/ReviewRetry.ts (ReviewFailure / ReviewAudit).
 */
public class ReviewFailure extends Exception {

    private final int attempts;
    private final List<ReviewFailureCode> failures;

    public ReviewFailure(int attempts, List<ReviewFailureCode> failures) {
        super("Explanation review failed: " + lastFailure(failures));
        this.attempts = attempts;
        this.failures = List.copyOf(failures);
    }

    private static ReviewFailureCode lastFailure(List<ReviewFailureCode> failures) {
        return failures.isEmpty() ? null : failures.get(failures.size() - 1);
    }

    public int getAttempts() {
        return attempts;
    }

    public List<ReviewFailureCode> getFailures() {
        return failures;
    }
}
