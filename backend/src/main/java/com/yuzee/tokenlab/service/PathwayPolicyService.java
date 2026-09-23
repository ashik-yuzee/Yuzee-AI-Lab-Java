package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Port of miniPathway/policy.ts's decision gate: {@code decideMiniPathway()} (as {@link #decide}),
 * {@code pathwayBoundary()}, {@code pathwayScore()} and {@code validPathwayHint()}. The client's
 * BGE pathway hint arrives in the request body and is validated here, as in the original.
 */
@Service
public class PathwayPolicyService {

    /** MINI_PATHWAY_THRESHOLD from policy.ts -- below this, a low-confidence turn auto-generates. */
    public static final int MINI_PATHWAY_THRESHOLD = 40;

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

    /** policy.ts pathwayScore(): the counsellor's integer 0-100 confidence, or null when unknown. */
    public static Integer pathwayScore(JsonNode response) {
        JsonNode c = response == null ? null : response.path("state").path("user_confidence");
        if (c == null) return null;
        JsonNode s = c.path("score");
        boolean integer = s.isNumber() && s.asDouble() == Math.rint(s.asDouble());
        return integer && s.asDouble() >= 0 && s.asDouble() <= 100
            && !"none".equals(c.path("evidence_strength").asText(null))
            && !"unknown".equals(c.path("band").asText(null)) ? s.asInt() : null;
    }

    /** policy.ts validPathwayHint(): the client's BGE (or legacy) relevance hint passes its gate. */
    public static boolean validPathwayHint(JsonNode h) {
        if (h == null || !h.isObject()) return false;
        JsonNode modelId = h.path("modelId");
        boolean bge = BgeGateService.BGE_MODEL_ID.equals(modelId.asText(null)) && modelId.isTextual();
        double gateScore = bge ? 0.70 : 0.48, gateMargin = bge ? 0.08 : 0.06; // bgeProfiles.pathway
        if (truthy(modelId) && (!bge || !"bge-pathway-v2".equals(h.path("profileVersion").asText(null)))) return false;
        JsonNode score = h.path("score"), margin = h.path("margin");
        return "selected".equals(h.path("status").asText(null))
            && score.isNumber() && Double.isFinite(score.asDouble()) && score.asDouble() >= gateScore && score.asDouble() <= 1
            && margin.isNumber() && Double.isFinite(margin.asDouble()) && margin.asDouble() >= gateMargin && margin.asDouble() <= 2;
    }

    private static boolean truthy(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return false;
        if (v.isTextual()) return !v.asText().isEmpty();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0 && !Double.isNaN(v.asDouble());
        return true;
    }

    /**
     * policy.ts decideMiniPathway() with the original hint contract: returns the
     * {action, reason, score} object service.ts stores on a MiniPathwayRun. {@code response} must
     * already be protocol-accepted (null means not).
     */
    public Map<String, Object> decide(JsonNode response, String userText, JsonNode hint, boolean alreadyHelped) {
        Integer score = pathwayScore(response);
        String action, reason;
        if (response == null || pathwayBoundary(response, userText)) { action = "none"; reason = "invalid-or-boundary"; }
        else if (!validPathwayHint(hint)) { action = "none"; reason = "no-relevant-match"; }
        else {
            // Unknown is not low; a relevant optional plan may still help without auto generation.
            action = score != null && score < MINI_PATHWAY_THRESHOLD && !alreadyHelped ? "automatic" : "offer";
            reason = alreadyHelped ? "already-helped" : score == null ? "confidence-unknown"
                : score < MINI_PATHWAY_THRESHOLD ? "low-decision-confidence" : "optional-pathway";
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", action);
        decision.put("reason", reason);
        decision.put("score", score);
        return decision;
    }
}
