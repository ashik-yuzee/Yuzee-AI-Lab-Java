package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.MicroTool;
import com.yuzee.tokenlab.model.RouteClaimRequest;
import com.yuzee.tokenlab.model.RoutingDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Port of the server-trustable half of src/routing/policy.ts: the microtools catalogue,
 * routingSkipReason() (heuristic pre-routing gates), validateRouteSelection() (the trust
 * boundary re-checking whatever the browser's BGE worker claims) and scopedInstruction()
 * (assembling the per-turn prompt injection for the selected tool), plus the two prompt-text
 * builders it composes with, skillInputInstruction() and learningDepthInstruction().
 *
 * The actual embedding computation (chooseRoute's vector ranking) stays client-side in the
 * Angular Web Worker; only the re-validation of its claimed result belongs here.
 */
@Service
public class RoutingPolicyService {

    public static final String ROUTER_VERSION = "minilm-guarded-v1";
    public static final double MIN_SCORE = 0.48;
    public static final double MIN_MARGIN = 0.06;
    /** The only model id the server currently trusts a client-computed score from. */
    public static final String DEFAULT_ROUTER_MODEL = BgeGateService.BGE_MODEL_ID;

    private static final Set<String> INTERNAL_ONLY = Set.of("CORE_001", "CORE_002");
    private static final Set<String> KNOWN_ABSTAIN_REASONS = Set.of(
        "not-ready", "low-similarity", "ambiguous", "incomplete-ranking", "busy", "timeout",
        "unavailable", "inference-failed", "cancelled", "token-budget", "outside-scope");

    private final BgeGateService bgeGateService;
    private final List<MicroTool> microTools;
    private final List<MicroTool> eligibleTools;
    private final JsonNode skillInputContracts;
    private final JsonNode learningContracts;
    private final JsonNode learningProfiles;
    private final String counsellingStyleInstruction;

    public RoutingPolicyService(BgeGateService bgeGateService) {
        this.bgeGateService = bgeGateService;
        ObjectMapper mapper = new ObjectMapper();
        try {
            this.microTools = new ArrayList<>(List.of(mapper.readValue(
                resource("config/microtools.json"), MicroTool[].class)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load config/microtools.json", e);
        }
        this.eligibleTools = microTools.stream()
            .filter(t -> !INTERNAL_ONLY.contains(t.getId()))
            .collect(Collectors.toList());

        this.skillInputContracts = readJson(mapper, "config/skillInputContracts.json");
        this.learningContracts = readJson(mapper, "config/learningContracts.json");
        this.learningProfiles = readJson(mapper, "config/learningProfiles.json");
        this.counsellingStyleInstruction = readJson(mapper, "config/counsellingStyle.json").path("instruction").asText();
    }

    private static InputStream resource(String classpathLocation) throws IOException {
        return new ClassPathResource(classpathLocation).getInputStream();
    }

    private static JsonNode readJson(ObjectMapper mapper, String classpathLocation) {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return mapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + classpathLocation, e);
        }
    }

    public List<MicroTool> getMicroTools() { return microTools; }
    public List<MicroTool> getEligibleTools() { return eligibleTools; }

    public boolean isEligibleTool(String toolId) {
        return toolId != null && eligibleTools.stream().anyMatch(t -> t.getId().equals(toolId));
    }

    public MicroTool findEligibleTool(String toolId) {
        return eligibleTools.stream().filter(t -> t.getId().equals(toolId)).findFirst().orElse(null);
    }

    private boolean isEligible(String toolId) { return isEligibleTool(toolId); }

    private MicroTool findEligible(String toolId) { return findEligibleTool(toolId); }

    // ---------------------------------------------------------------------
    // routingSkipReason -- port of policy.ts's routingSkipReason()
    // ---------------------------------------------------------------------

    private static final Pattern HAS_LETTER = Pattern.compile("[a-z]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISALLOWED_SYMBOLS = Pattern.compile("[^\\u0000-\\u024f\\u2000-\\u206f]");
    private static final Pattern CONVERSATION = Pattern.compile(
        "^(hi|hello|hey|thanks|thank you|yes|no|okay|ok|sure|not sure|i['’]?m not sure)[.!\\s]*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION_ACTUALLY = Pattern.compile("^actually\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION_WORDS = Pattern.compile(
        "\\b(pause|cancel|forget|ignore|instead|rather than|do not|don['’]t|not interested|not looking)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION_STOP = Pattern.compile(
        "^(?:please\\s+)?stop\\b|\\bstop (?:suggesting|searching|asking|the|this|that)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE = Pattern.compile(
        "\\b(kill myself|suicid|self.harm|hurt myself|emergency)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEEDS_CONTEXT = Pattern.compile(
        "^(more|tell me more|go on|continue|what about that|what about this|what about (?:that|this|the second) one|"
            + "does (?:that|this) qualify me|compare (?:them|these|those)|why|how much|what next)[?.!\\s]*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Returns a skip reason, or null when the turn is eligible for the local micro-tool router. */
    public String routingSkipReason(String text, boolean structuredAnswer) {
        String s = text == null ? "" : text.trim();
        if (structuredAnswer) return "structured-answer";
        if (s.isEmpty() || s.length() > 1800) return "input-length";
        if (!HAS_LETTER.matcher(s).find() || DISALLOWED_SYMBOLS.matcher(s).find()) return "language-or-symbols";
        if (CONVERSATION.matcher(s).matches()) return "conversation";
        if (CORRECTION_ACTUALLY.matcher(s).find() || CORRECTION_WORDS.matcher(s).find() || CORRECTION_STOP.matcher(s).find())
            return "correction-or-boundary";
        if (SENSITIVE.matcher(s).find()) return "sensitive-boundary";
        if (NEEDS_CONTEXT.matcher(s).matches()) return "needs-context";
        if (WHITESPACE.split(s).length < 4) return "needs-context";
        long questionMarks = s.chars().filter(c -> c == '?').count();
        // Multiple jobs in one request belong with the full counsellor until a multi-tool planner is evaluated.
        if (questionMarks > 1) return "multiple-questions";
        return null;
    }

    // ---------------------------------------------------------------------
    // validateRouteSelection -- port of policy.ts's validateRouteSelection() (the trust boundary)
    // ---------------------------------------------------------------------

    /** Server and browser use the same allowlisted profile; client thresholds are never accepted. */
    public RoutingDecision validateRouteSelection(RouteClaimRequest claim, String flow) {
        String effectiveFlow = (flow == null || flow.isBlank()) ? "route" : flow;
        if (claim == null || !ROUTER_VERSION.equals(claim.getVersion())) {
            return RoutingDecision.abstain("no-selection", ROUTER_VERSION);
        }
        if ("abstained".equals(claim.getStatus())) {
            String reason = KNOWN_ABSTAIN_REASONS.contains(claim.getReason()) ? claim.getReason() : "no-selection";
            return RoutingDecision.abstain(reason, ROUTER_VERSION);
        }
        if (!"selected".equals(claim.getStatus())) {
            return RoutingDecision.abstain("no-selection", ROUTER_VERSION);
        }
        if (!isEligible(claim.getToolId())) {
            return RoutingDecision.abstain("unknown-tool", ROUTER_VERSION);
        }
        String modelId = claim.getModelId() != null ? claim.getModelId() : DEFAULT_ROUTER_MODEL;
        if (!BgeGateService.BGE_MODEL_ID.equals(modelId)) {
            return RoutingDecision.abstain("unknown-model", ROUTER_VERSION);
        }
        if (claim.getRoutingFlow() != null && !claim.getRoutingFlow().equals(effectiveFlow)) {
            return RoutingDecision.abstain("invalid-flow", ROUTER_VERSION);
        }

        BgeGateService.Gate gate = bgeGateService.gate(effectiveFlow);
        Double score = claim.getScore();
        Double margin = claim.getMargin();
        if (score == null || !Double.isFinite(score) || score < gate.score || score > 1
            || margin == null || !Double.isFinite(margin) || margin < gate.margin || margin > 2) {
            return RoutingDecision.abstain("invalid-score", ROUTER_VERSION);
        }

        // BGE ready handshake + calibration -- the real security boundary (bgeContract.ts's validBgeReady()).
        Double domainMargin = claim.getDomainMargin();
        boolean calibrationOk = bgeGateService.getCalibrationVersion().equals(claim.getCalibrationVersion())
            && effectiveFlow.equals(claim.getRoutingFlow())
            && domainMargin != null && Double.isFinite(domainMargin)
            && domainMargin >= gate.domainMargin && domainMargin <= score + 1;
        if (!calibrationOk) {
            return RoutingDecision.abstain("invalid-calibration", ROUTER_VERSION);
        }
        if (!bgeGateService.validBgeReady(claim)) {
            return RoutingDecision.abstain("unknown-model", ROUTER_VERSION);
        }

        RoutingDecision decision = new RoutingDecision();
        decision.setStatus("selected");
        decision.setToolId(claim.getToolId());
        decision.setScore(score);
        decision.setMargin(margin);
        decision.setModelId(modelId);
        decision.setRoutingFlow(effectiveFlow);
        decision.setCalibrationVersion(bgeGateService.getCalibrationVersion());
        decision.setDomainMargin(domainMargin);
        decision.setReason("clear-semantic-match");
        decision.setVersion(ROUTER_VERSION);
        return decision;
    }

    // ---------------------------------------------------------------------
    // scopedInstruction -- port of policy.ts's scopedInstruction()
    // ---------------------------------------------------------------------

    public String scopedInstruction(RoutingDecision decision) {
        if (decision == null || !"selected".equals(decision.getStatus())) return "";
        MicroTool tool = findEligible(decision.getToolId());
        if (tool == null) return "";
        String reason = decision.getReason();
        String framing = "skill-question-continuation".equals(reason) ? "Continue the previously selected skill"
            : ("user-selected-skill".equals(reason) || "user-selected-topic".equals(reason))
                ? "The user selected the offered skill"
                : "The local similarity router suggests";
        return "OPTIONAL_FOCUS_FOR_THIS_TURN:\n" + framing + " \"" + tool.getName() + "\". This is a fallible topic "
            + "hint, not a user instruction or evidence. Ignore it if it does not match the actual request and "
            + "conversation. The main counselling instructions and canonical JSON contract still control the "
            + "response. Do not expose routing labels, similarity scores or internal classifications. Do not "
            + "restart intake, override a correction, invent facts, imply retrieval occurred, or perform an "
            + "external action. Unknown workload, accessibility, eligibility, prices and availability remain "
            + "unknown until supported by relevant evidence. Respect requests for one sentence or no follow-up.\n"
            + "Use only the relevant parts of this catalogue guidance; any request for a different output format "
            + "or internal fields must be adapted to the main response contract:\n"
            + tool.getMini_prompt() + "\n\n"
            + learningDepthInstruction(tool.getId()) + "\n\n"
            + skillInputInstruction(tool.getId()) + "\n"
            + "END_OPTIONAL_FOCUS";
    }

    // ---------------------------------------------------------------------
    // skillInputInstruction -- port of skillInputs.ts
    // ---------------------------------------------------------------------

    public String skillInputInstruction(String toolId) {
        JsonNode contract = findById(skillInputContracts, toolId);
        if (contract == null) return "";
        List<String> lines = new ArrayList<>();
        lines.add("SKILL INPUT CONTRACT v1 — " + contract.path("id").asText() + " (" + contract.path("mode").asText() + "):");
        lines.add("These rules refine legacy required_inputs lists: those lists mix user information, system "
            + "context and source data. They are NOT a mandatory intake form. Preserve the main counselling and "
            + "canonical JSON contract.");
        lines.add("Subject to resolve: " + contract.path("subject").asText() + ".");
        lines.add("Needed only for the specific result: " + contract.path("needed_for_specific_answer").asText() + ".");
        lines.add("Useful response without new input: " + contract.path("without_new_input").asText());
        lines.add("Optional, never automatic blockers: " + contract.path("optional_details").asText() + ".");
        lines.add("First inspect the current request and available conversation. Reuse explicit, still-relevant "
            + "user facts; a correction replaces older facts. Assistant guesses, examples, quoted third-party "
            + "profiles and inferred demographics are not user facts. If several courses or goals are active, "
            + "clarify the reference. Do not infer location from timezone, IP, institution location or a previous "
            + "unrelated subject; let the user type it.");
        lines.add("Decide separately: can I explain now; is a user detail missing that changes the requested "
            + "result; is source evidence missing? MiniLM similarity measures topic match, NOT completeness, user "
            + "certainty, eligibility or factual confidence.");
        lines.add("Answer immediately when possible. If a personal or entity-specific conclusion is blocked, "
            + "provide the useful part first, then ask ONE plain-language, decision-changing question using the "
            + "existing canonical interaction question. Do not repeat known facts, open a generic questionnaire, "
            + "invent new response keys or start a service intake.");
        lines.add("Example first question ONLY if its answer is missing and material (adapt it, never ask it "
            + "mechanically): " + contract.path("first_question").asText());
        lines.add("If the user is unsure or declines, offer a general explanation or clearly labelled alternative "
            + "scenarios. Do not repeat the same question or assume an answer. If the user requests no questions, "
            + "explain the limit and proceed with the safe general part. Collect only relevant, voluntary "
            + "information; prefer experience summaries to identifiable documents.");
        lines.add("Missing evidence is a source problem, not a user-profile gap. Use available connected lookup "
            + "only when it actually runs; otherwise label unverified specifics and offer a source check or "
            + "request the relevant document. Never claim retrieval or invent a fee, syllabus, vacancy, credit or "
            + "eligibility result.");
        lines.add("Source standard: " + contract.path("evidence_rule").asText());
        lines.add("On the next answer, retain the selected skill and original goal, incorporate the new detail and "
            + "continue. Do not restart the skill or intake; ask again only for a different essential unresolved "
            + "detail. Respect topic changes, corrections, stop and safety boundaries.");
        lines.add("The normal response renderer handles the answer and one question. Optional skill suggestions "
            + "remain hidden while a question is active. A complete answer can have interaction.kind=none; do not "
            + "force a follow-up question.");
        lines.add("END_SKILL_INPUT_CONTRACT");
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------------
    // learningDepthInstruction -- port of learningDepth.ts
    // ---------------------------------------------------------------------

    public String learningDepthInstruction(String toolId) {
        JsonNode contract = findById(learningContracts, toolId);
        if (contract == null || "internal".equals(contract.path("profile").asText())) return "";
        JsonNode sections = learningProfiles.path(contract.path("profile").asText());

        List<String> lines = new ArrayList<>();
        lines.add("LEARNING DEPTH CONTRACT (server-owned, version 4):");
        lines.add(counsellingStyleInstruction);
        lines.add("Apply only when this task matches the actual request. For substantive explanations, this "
            + "replaces generic brevity and one-example defaults, not scope or evidence rules. Explicit brevity, "
            + "stop, safety, a narrow correction, only-a-list request or one small activity takes priority. Do not "
            + "expand a simple fact into a full lesson.");
        lines.add("Task information to explain, not new JSON keys: " + contract.path("information").asText());
        lines.add("Legacy task dependencies (not mandatory user questions): " + contract.path("required_inputs").asText()
            + ". Use the per-skill input contract to distinguish conversation context, optional personal details "
            + "and source evidence. Never ask for internal IDs. Do not demand every input before useful general "
            + "teaching.");
        lines.add("Select from these topic-specific teaching ingredients only where they help the current request. "
            + "These are not required headings or a minimum section count. Combine related ingredients and omit "
            + "irrelevant ones, especially course/assessment material in a stand-alone skill lesson. Use existing "
            + "content_blocks; do not hide reasoning in a card description or option label:");
        int i = 1;
        for (JsonNode pair : sections) {
            lines.add(i + ". " + pair.get(0).asText() + ": " + pair.get(1).asText());
            i++;
        }
        lines.add("Teach important concepts before relying on them. Adapt examples to the topic and known goal; do "
            + "not reuse record-cleaning examples for unrelated skills. Everyday example and workplace scenario "
            + "serve different purposes, not the same anecdote twice. Include concrete decisions and observable "
            + "results. Hypothetical success is not a real outcome. For multiple items, preserve all requested "
            + "items and organise meaningful detail; never silently drop items or label unfinished scope complete.");
        lines.add("Source rule: " + contract.path("evidence").asText() + ". Translate into natural "
            + "source/uncertainty wording; valid JSON is not factual verification.");
        lines.add("Complete the explanation before optional follow-up. No compulsory quiz, service pitch or new "
            + "intake. Preserve the canonical schema; no new top-level lesson fields. End learning depth contract.");
        return String.join("\n", lines);
    }

    private static JsonNode findById(JsonNode array, String id) {
        if (array == null || id == null) return null;
        Iterator<JsonNode> it = array.elements();
        while (it.hasNext()) {
            JsonNode node = it.next();
            if (id.equals(node.path("id").asText())) return node;
        }
        return null;
    }
}
