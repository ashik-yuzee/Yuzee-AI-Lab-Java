package com.yuzee.tokenlab.service;

/**
 * Classification of why a {@link ReviewRetryService#runReview} attempt failed. Ported from
 * yuzee-ai-token-lab/src/services/ReviewRetry.ts (ReviewFailureCode).
 */
public enum ReviewFailureCode {
    TIMEOUT,
    NETWORK,
    RATE_LIMIT,
    PROVIDER,
    INVALID_RESPONSE,
    CONFIGURATION,
    CANCELLED,
    UNKNOWN
}
