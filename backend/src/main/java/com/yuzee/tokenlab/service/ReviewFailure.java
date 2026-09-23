package com.yuzee.tokenlab.service;

import java.util.List;
import java.util.Map;

/**
 * Thrown by {@link ReviewRetryService#runReview} when the bounded retry is exhausted. Carries
 * the ReviewAudit ({attempts, failures}). Callers must never silently fall back to an
 * unreviewed answer. Ported from yuzee-ai-token-lab/src/services/ReviewRetry.ts (ReviewFailure).
 */
public class ReviewFailure extends Exception {

    private final int attempts;
    private final List<ReviewFailureCode> failures;

    public ReviewFailure(int attempts, List<ReviewFailureCode> failures) {
        super("Explanation review failed: " + (failures.isEmpty() ? "undefined" : failures.get(failures.size() - 1)));
        this.attempts = attempts;
        this.failures = List.copyOf(failures);
    }

    public int getAttempts() {
        return attempts;
    }

    public List<ReviewFailureCode> getFailures() {
        return failures;
    }

    /** The original ReviewAudit shape: {attempts:number, failures:ReviewFailureCode[]}. */
    public Map<String, Object> getAudit() {
        return ReviewRetryService.audit(attempts, failures);
    }
}
