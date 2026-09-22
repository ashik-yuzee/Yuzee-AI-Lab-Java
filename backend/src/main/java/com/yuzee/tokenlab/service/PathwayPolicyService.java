package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Port of miniPathway/policy.ts's decision gate: {@code decideMiniPathway()} and its
 * {@code pathwayBoundary()} guard.
 *
 * The old app's confidence score came from a client-side BGE classifier scoring the current
 * response against a "pathway" vs "other" embedding profile ({@code choosePathwayHint()} /
 * {@code validPathwayHint()} in policy.ts). That embedding-index machinery is being ported
 * separately on the routing side, so this port takes the resulting confidence score and
 * relevance verdict as plain parameters instead of recomputing them.
 * // ponytail: score/relevance come in as parameters rather than being computed here -- wire up
 * // the routing engineer's BGE port as the caller once it lands.
 */
@Service
public class PathwayPolicyService {

    /** MINI_PATHWAY_THRESHOLD from policy.ts -- below this, a low-confidence turn auto-generates. */
    public static final int MINI_PATHWAY_THRESHOLD = 40;

    public enum Decision { AUTOMATIC, OFFER, NONE }

    private static final Pattern NO_PATHWAY_REQUESTED = Pattern.compile(
        "\\b(?:no|without|don't|do not)\\s+(?:a\\s+)?(?:mini\\s+)?pathway\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFETY_INTENT = Pattern.compile("SAFETY|SECURITY|CRITICAL_CLARIFICATION");

    private final SkillSuggestionService skillSuggestionService;

    public PathwayPolicyService(SkillSuggestionService skillSuggestionService) {
        this.skillSuggestionService = skillSuggestionService;
    }

    /**
     * Port of policy.ts's {@code pathwayBoundary()}. True when a mini pathway must not run for
     * this turn, regardless of relevance: the user asked to stop/opted out, or the source
     * response is itself a safety override, a service handoff, a safety/security/critical-
     * clarification intent, or an active service trigger.
     */
    public boolean pathwayBoundary(JsonNode sourceResponse, String userText) {
        if (!skillSuggestionService.allowSkillReview(userText)) return true;
        if (NO_PATHWAY_REQUESTED.matcher(userText == null ? "" : userText).find()) return true;
        if (sourceResponse == null || sourceResponse.isNull() || sourceResponse.isMissingNode()) return false;
        if (sourceResponse.path("state").path("safety_override_applied").asBoolean(false)) return true;
        if ("S_SERVICE_HANDOFF".equals(sourceResponse.path("current_mode").asText(""))) return true;
        if (SAFETY_INTENT.matcher(sourceResponse.path("response_intent").asText("")).find()) return true;
        return sourceResponse.path("service_trigger").path("trigger_now").asBoolean(false);
    }

    /**
     * Port of policy.ts's {@code decideMiniPathway()}. {@code confidenceScore} is the caller-
     * supplied BGE-derived score (0-100), or {@code null} for "unknown" (matching the TS
     * {@code score:number|null}). {@code pathwayRelevant} is the caller's already-computed
     * relevance verdict (TS's {@code validPathwayHint(hint)}).
     */
    public Decision decideMiniPathway(JsonNode sourceResponse, String userText, Double confidenceScore,
                                       boolean pathwayRelevant, boolean alreadyHelped) {
        if (sourceResponse == null || sourceResponse.isNull() || sourceResponse.isMissingNode()) return Decision.NONE;
        if (pathwayBoundary(sourceResponse, userText)) return Decision.NONE;
        if (!pathwayRelevant) return Decision.NONE;
        // Unknown confidence is not low; a relevant optional plan may still help without auto generation.
        boolean lowConfidence = confidenceScore != null && confidenceScore < MINI_PATHWAY_THRESHOLD;
        return lowConfidence && !alreadyHelped ? Decision.AUTOMATIC : Decision.OFFER;
    }
}
