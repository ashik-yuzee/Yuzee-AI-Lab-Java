package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.Candidate;
import com.yuzee.tokenlab.model.RouteClaimRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Port of bgeProfiles.ts (the gate/threshold half -- not the embedding ranking, which stays
 * client-side) and bgeContract.ts's validBgeReady() / bgeReadyContract.
 *
 * Reads calibration numbers from config/bgeCalibration.json (route/topic/suggestion) and
 * config/bgeArtifact.json (the pinned model artifact the "ready" handshake is checked against).
 * needs/pathway gates are hardcoded, exactly as they are in the original bgeProfiles.ts.
 */
@Service
public class BgeGateService {

    public static final String BGE_MODEL_ID = "Xenova/bge-small-en-v1.5";
    /** Immutable v1 skill calibration remains the rollback point. No model weights changed. */
    public static final String BGE_RELEASE = "bge-single-encoder-v2";
    /** bgeDomain.ts: broad out-of-scope competitors. These are not a keyword denylist or a safety classifier. */
    public static final String BGE_OUT_OF_SCOPE = "__OUT_OF_SCOPE__";

    public static final class Gate {
        public final double score;
        public final double margin;
        public final double domainMargin;

        public Gate(double score, double margin, double domainMargin) {
            this.score = score;
            this.margin = margin;
            this.domainMargin = domainMargin;
        }
    }

    private final Map<String, Gate> gatesByFlow = new LinkedHashMap<>();
    private String calibrationVersion;
    private String contentVersion;
    private Map<String, Object> bgeReadyContract;

    public BgeGateService() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode calibration = readJson(mapper, "config/bgeCalibration.json");
        this.calibrationVersion = calibration.path("version").asText();
        this.contentVersion = calibration.path("contentVersion").asText();
        for (String flow : new String[]{"route", "topic", "suggestion"}) {
            JsonNode g = calibration.path(flow);
            gatesByFlow.put(flow, new Gate(g.path("score").asDouble(), g.path("margin").asDouble(), g.path("domainMargin").asDouble()));
        }

        JsonNode artifact = readJson(mapper, "config/bgeArtifact.json");
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("modelId", artifact.path("modelId").asText());
        ready.put("revision", artifact.path("revision").asText());
        ready.put("tokenBudget", artifact.path("maxTokens").asInt());
        ready.put("pooling", artifact.path("pooling").asText());
        ready.put("dimensions", artifact.path("dimensions").asInt());
        ready.put("dtype", artifact.path("dtype").asText());
        ready.put("artifact", artifact.path("artifact").asText());
        ready.put("release", BGE_RELEASE);
        this.bgeReadyContract = ready;
    }

    private static JsonNode readJson(ObjectMapper mapper, String classpathLocation) {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return mapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + classpathLocation, e);
        }
    }

    public String getCalibrationVersion() { return calibrationVersion; }

    /** bgeMatching.ts's BGE_CONTENT_VERSION -- the version tag for the skill-matching catalogue itself. */
    public String getContentVersion() { return contentVersion; }

    /** Gate for the route/topic/suggestion flow. Falls back to the "route" gate for an unknown flow. */
    public Gate gate(String flow) {
        return gatesByFlow.getOrDefault(flow, gatesByFlow.get("route"));
    }

    /** needs.ts's bgeProfiles.needs -- asking for MORE information requires a stronger gate than an advisory hint. */
    public Gate needGate(String kind) {
        return "clarify".equals(kind) ? new Gate(0.60, 0.04, 0) : new Gate(0.55, 0.01, 0);
    }

    public static final String NEEDS_PROFILE_VERSION = "bge-input-needs-v3";

    /** bgeMatching.ts bgeGateResult() result; score/margin/domainMargin are null when the ranking is incomplete. */
    public record GateResult(boolean selected, String reason, String toolId, Double score, Double margin, Double domainMargin) {}

    /** Port of bgeMatching.ts's bgeGateResult(). */
    public static GateResult bgeGateResult(List<Candidate> candidates, Gate gate) {
        List<Candidate> valid = candidates.stream()
            .filter(c -> Double.isFinite(c.getScore()) && c.getScore() >= -1 && c.getScore() <= 1)
            .collect(Collectors.toList());
        Candidate domain = valid.stream()
            .filter(c -> BGE_OUT_OF_SCOPE.equals(c.getToolId())).findFirst().orElse(null);
        List<Candidate> ranked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        valid.stream().filter(c -> !BGE_OUT_OF_SCOPE.equals(c.getToolId()))
            .sorted(Comparator.comparingDouble(Candidate::getScore).reversed())
            .forEach(c -> { if (seen.add(c.getToolId())) ranked.add(c); });
        if (ranked.size() < 2 || domain == null) return new GateResult(false, "incomplete-ranking", null, null, null, null);
        Candidate a = ranked.get(0), b = ranked.get(1);
        double margin = a.getScore() - b.getScore(), domainMargin = a.getScore() - domain.getScore();
        String reason = a.getScore() < gate.score ? "low-similarity" : domainMargin < gate.domainMargin ? "outside-scope"
            : margin < gate.margin ? "ambiguous" : "clear-semantic-match";
        return new GateResult("clear-semantic-match".equals(reason), reason, a.getToolId(), a.getScore(), margin, domainMargin);
    }

    /**
     * Port of bgeContract.ts's validBgeReady(). The browser worker must report every one of these
     * fields (it knows them because it just loaded the pinned model) and all must match the
     * server's own pinned config/bgeArtifact.json -- this is the real trust boundary that stops a
     * spoofed or stale client from having its similarity score accepted at MIN_SCORE-adjacent
     * thresholds without actually running the calibrated model.
     */
    public boolean validBgeReady(Map<String, Object> claimed) {
        if (claimed == null) return false;
        for (Map.Entry<String, Object> e : bgeReadyContract.entrySet()) {
            if (!Objects.equals(claimed.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    /** Convenience overload reading the ready-handshake fields directly off a RouteClaimRequest. */
    public boolean validBgeReady(RouteClaimRequest claim) {
        if (claim == null) return false;
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("modelId", claim.getModelId());
        claimed.put("revision", claim.getModelRevision());
        claimed.put("tokenBudget", claim.getTokenBudget());
        claimed.put("pooling", claim.getPooling());
        claimed.put("dimensions", claim.getDimensions());
        claimed.put("dtype", claim.getDtype());
        claimed.put("artifact", claim.getArtifact());
        claimed.put("release", claim.getRelease());
        return validBgeReady(claimed);
    }
}
