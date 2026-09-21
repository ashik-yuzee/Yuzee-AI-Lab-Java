package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * Port of protocol/securityOverride.ts.
 *
 * security_breach_count and active_security_penalty are APPLICATION state, not
 * model-generated content. The server tracks them (e.g. on the Conversation) and
 * overwrites whatever the model generated BEFORE validation occurs.
 *
 * These methods are pure/side-effect-limited-to-the-passed-node so they can be
 * unit tested without any Spring context.
 *
 * IMPORTANT: response_intent is model-generated and MUST NOT be used to derive or
 * increment breach count. Breach count is only ever incremented by explicit
 * authoritative server-side security events (rate-limit violations, content-filter
 * flags, etc.), passed in via authoritativeBreachDelta — never from model output.
 */
@Service
public class SecurityStateService {

    /** Mirrors TS SecurityPenalty union: '' | '10_min_timeout' | '24_hr_ban'. */
    public static final String PENALTY_NONE = "";
    public static final String PENALTY_10_MIN_TIMEOUT = "10_min_timeout";
    public static final String PENALTY_24_HR_BAN = "24_hr_ban";

    public static class NextSecurityState {
        public final int newBreachCount;
        public final String newPenalty;

        public NextSecurityState(int newBreachCount, String newPenalty) {
            this.newBreachCount = newBreachCount;
            this.newPenalty = newPenalty;
        }
    }

    /** Derive the canonical penalty from the authoritative breach count. */
    public String deriveSecurityPenalty(int breachCount) {
        if (breachCount >= 3) return PENALTY_24_HR_BAN;
        if (breachCount >= 1) return PENALTY_10_MIN_TIMEOUT;
        return PENALTY_NONE;
    }

    /**
     * Mutate parsedResponse in-place, replacing the model's security fields with
     * authoritative server values. No-op if state.progress is missing or not an object.
     */
    public void applyServerSecurityState(JsonNode parsedResponse, int serverBreachCount, String serverPenalty) {
        if (parsedResponse == null) return;
        JsonNode state = parsedResponse.path("state");
        JsonNode progress = state.path("progress");
        if (!(progress instanceof ObjectNode)) return;
        ObjectNode progressNode = (ObjectNode) progress;
        progressNode.put("security_breach_count", serverBreachCount);
        progressNode.put("active_security_penalty", serverPenalty);
    }

    /**
     * Compute the next server security state for this turn.
     * Caller must persist the returned values (e.g. onto the Conversation).
     */
    public NextSecurityState computeNextSecurityState(int prevBreachCount, int authoritativeBreachDelta) {
        int newBreachCount = prevBreachCount + authoritativeBreachDelta;
        return new NextSecurityState(newBreachCount, deriveSecurityPenalty(newBreachCount));
    }

    public NextSecurityState computeNextSecurityState(int prevBreachCount) {
        return computeNextSecurityState(prevBreachCount, 0);
    }

    /**
     * Normalise the security fields inside a parsed response, coercing null/missing or
     * unexpected types to the canonical empty-string/zero form before
     * applyServerSecurityState writes the authoritative values. Guards against the
     * model outputting null when the response schema marks the field nullable.
     */
    public void normaliseSecurityFields(JsonNode parsedResponse) {
        if (parsedResponse == null) return;
        JsonNode state = parsedResponse.path("state");
        JsonNode progress = state.path("progress");
        if (!(progress instanceof ObjectNode)) return;
        ObjectNode progressNode = (ObjectNode) progress;

        JsonNode breachCount = progressNode.path("security_breach_count");
        if (!breachCount.isInt() && !breachCount.isLong()) {
            progressNode.put("security_breach_count", 0);
        }

        JsonNode penalty = progressNode.path("active_security_penalty");
        if (!penalty.isTextual()) {
            progressNode.put("active_security_penalty", "");
        }
    }
}
