package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.Candidate;
import com.yuzee.tokenlab.model.MicroTool;
import com.yuzee.tokenlab.model.RouteClaimRequest;
import com.yuzee.tokenlab.model.RoutingDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Port of src/routing/policy.ts (with conversationQuery.ts, skillInputs.ts and learningDepth.ts,
 * which it composes with). The embedding computation stays in the browser; the server only
 * re-validates the client's claimed selection.
 *
 * Also hosts the small JavaScript-semantics helpers (JS regex \s / $ / . , String.trim, truthiness,
 * ===) that the routing ports share so every regex behaves exactly as in the original.
 */
@Service
public class RoutingPolicyService {

    public static final String ROUTER_VERSION = "minilm-guarded-v1";
    public static final String MODEL_ID = "Xenova/all-MiniLM-L6-v2";
    public static final double MIN_SCORE = 0.48;
    public static final double MIN_MARGIN = 0.06;
    /** models.ts DEFAULT_ROUTER_MODEL = ROUTER_MODELS[2].id. */
    public static final String DEFAULT_ROUTER_MODEL = BgeGateService.BGE_MODEL_ID;
    /** models.ts ROUTER_MODELS ids. */
    public static final List<String> ROUTER_MODELS = List.of(
        "Xenova/all-MiniLM-L6-v2", "Xenova/all-MiniLM-L12-v2", "Xenova/bge-small-en-v1.5");

    // These catalogue entries describe internal control operations, not user-facing answers.
    private static final Set<String> INTERNAL_ONLY = Set.of("CORE_001", "CORE_002");
    private static final List<String> KNOWN_ABSTAIN_REASONS = List.of(
        "not-ready", "low-similarity", "ambiguous", "incomplete-ranking", "busy", "timeout",
        "unavailable", "inference-failed", "cancelled", "token-budget", "outside-scope");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BgeGateService bgeGateService;
    private final List<MicroTool> microTools;
    private final List<MicroTool> eligibleTools;
    private final JsonNode skillInputContracts;
    private final JsonNode learningContracts;
    private final JsonNode learningProfiles;
    private final String counsellingStyleInstruction;

    public RoutingPolicyService(BgeGateService bgeGateService) {
        this.bgeGateService = bgeGateService;
        try (InputStream is = new ClassPathResource("config/microtools.json").getInputStream()) {
            this.microTools = List.of(MAPPER.readValue(is, MicroTool[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load config/microtools.json", e);
        }
        this.eligibleTools = microTools.stream()
            .filter(t -> !INTERNAL_ONLY.contains(t.getId()))
            .collect(Collectors.toList());
        this.skillInputContracts = readJson("config/skillInputContracts.json");
        this.learningContracts = readJson("config/learningContracts.json");
        this.learningProfiles = readJson("config/learningProfiles.json");
        this.counsellingStyleInstruction = readJson("config/counsellingStyle.json").path("instruction").asText();
    }

    private static JsonNode readJson(String classpathLocation) {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return MAPPER.readTree(is);
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

    /** server.ts: microTools.find(t=>t.id===toolId) -- searches the whole catalogue, internal entries included. */
    public MicroTool findTool(String toolId) {
        return microTools.stream().filter(t -> t.getId().equals(toolId)).findFirst().orElse(null);
    }

    /** server.ts: microTools.find(t=>t.id===toolId)?.mini_prompt (null when absent). */
    public String findToolMiniPrompt(String toolId) {
        MicroTool tool = findTool(toolId);
        return tool == null ? null : tool.getMini_prompt();
    }

    /** eligibleTools.some(t=>t.id===value) with JS strict equality (a non-string never matches). */
    boolean isEligibleNode(JsonNode value) {
        return value != null && value.isTextual() && isEligibleTool(value.asText());
    }

    public static RoutingDecision abstain(String reason) {
        return RoutingDecision.abstain(reason, ROUTER_VERSION);
    }

    // ---------------------------------------------------------------------
    // routingSkipReason / routingInput
    // ---------------------------------------------------------------------

    private static final Pattern HAS_LETTER = jsRegex("[a-z]", true);
    private static final Pattern DISALLOWED_SYMBOLS = jsRegex("[^\\u0000-\\u024f\\u2000-\\u206f]", false);
    private static final Pattern CONVERSATION = jsRegex(
        "^(hi|hello|hey|thanks|thank you|yes|no|okay|ok|sure|not sure|i[’']?m not sure)[.!\\s]*$", true);
    private static final Pattern CORRECTION_ACTUALLY = jsRegex("^actually\\b", true);
    private static final Pattern CORRECTION_WORDS = jsRegex(
        "\\b(pause|cancel|forget|ignore|instead|rather than|do not|don[’']t|not interested|not looking)\\b", true);
    private static final Pattern CORRECTION_STOP = jsRegex(
        "^(?:please\\s+)?stop\\b|\\bstop (?:suggesting|searching|asking|the|this|that)\\b", true);
    private static final Pattern SENSITIVE = jsRegex(
        "\\b(kill myself|suicid|self.harm|hurt myself|emergency)\\b", true);
    private static final Pattern NEEDS_CONTEXT = jsRegex(
        "^(more|tell me more|go on|continue|what about that|what about this|what about (?:that|this|the second) one|"
            + "does (?:that|this) qualify me|compare (?:them|these|those)|why|how much|what next)[?.!\\s]*$", true);
    private static final Pattern WHITESPACE = jsRegex("\\s+", false);

    public String routingSkipReason(String text, boolean structuredAnswer) {
        String s = jsTrim(text == null ? "" : text);
        if (structuredAnswer) return "structured-answer";
        if (s.isEmpty() || s.length() > 1800) return "input-length";
        if (!HAS_LETTER.matcher(s).find() || DISALLOWED_SYMBOLS.matcher(s).find()) return "language-or-symbols";
        if (CONVERSATION.matcher(s).find()) return "conversation";
        if (CORRECTION_ACTUALLY.matcher(s).find() || CORRECTION_WORDS.matcher(s).find() || CORRECTION_STOP.matcher(s).find())
            return "correction-or-boundary";
        if (SENSITIVE.matcher(s).find()) return "sensitive-boundary";
        if (NEEDS_CONTEXT.matcher(s).find()) return "needs-context";
        if (WHITESPACE.split(s).length < 4) return "needs-context";
        // Multiple jobs in one request belong with the full counsellor until a multi-tool planner is evaluated.
        if (s.chars().filter(c -> c == '?').count() > 1) return "multiple-questions";
        return null;
    }

    public record RoutingInput(String text, String skip, boolean resolved) {}

    public RoutingInput routingInput(String text, List<JsonNode> history, boolean structured) {
        String skip = routingSkipReason(text, structured);
        if (!"needs-context".equals(skip)) return new RoutingInput(text, skip, false);
        String query = resolveShortQuery(text, history);
        return query != null ? new RoutingInput(query, null, true) : new RoutingInput(text, skip, false);
    }

    // ---------------------------------------------------------------------
    // conversationQuery.ts -- resolveShortQuery()
    // ---------------------------------------------------------------------

    // Only explicit user subjects can resolve a reference. Assistant prose is never copied as fact.
    private static final Pattern RESET = jsRegex(
        "\\b(?:new topic|different topic|forget that|instead|not that course|cancel|stop)\\b", true);
    private static final Pattern SUBJECT = jsRegex(
        "\\b(?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)\\b[^\\n.!?;]{2,180}", true);
    private static final Pattern QUALIFICATION_MARKER = jsRegex("\\b(?:Bachelor|Master|Diploma|Certificate)\\b", true);
    private static final Pattern OALA_PREFIX = jsRegex("^@oala\\s*", true);
    private static final Pattern Q_COSTS = jsRegex("^(?:what about (?:the )?costs?|(?:course )?(?:costs?|fees)|how much)[?.!\\s]*$", true);
    private static final Pattern Q_JOBS = jsRegex("^(?:what jobs|what about jobs)[?.!\\s]*$", true);
    private static final Pattern Q_QUALITY = jsRegex("^(?:quality|course quality)[?.!\\s]*$", true);
    private static final Pattern Q_COMPARE = jsRegex("^(?:compare (?:them|these|those)|compare (?:the )?two)[?.!\\s]*$", true);
    private static final Pattern Q_QUALIFY = jsRegex("^(?:does (?:that|this) qualify me)[?.!\\s]*$", true);
    private static final Pattern Q_DEEPER = jsRegex("^(?:go deeper|tell me more|more details|explain (?:it|that|this)|why)[?.!\\s]*$", true);

    /** history elements are {role, content} message objects. */
    public static String resolveShortQuery(String request, List<JsonNode> history) {
        String s = jsTrim(request == null ? "" : request);
        if (s.length() > 160 || WHITESPACE.split(s).length > 12 || history == null || history.isEmpty()) return null;
        String intent = Q_COSTS.matcher(s).find() ? "Explain course tuition, funding and additional costs"
            : Q_JOBS.matcher(s).find() ? "Explain career outcomes and jobs after this course"
            : Q_QUALITY.matcher(s).find() ? "Explain how to evaluate this course and provider quality"
            : Q_COMPARE.matcher(s).find() ? "Compare these two courses for the user"
            : Q_QUALIFY.matcher(s).find() ? "Explain eligibility and qualification requirements; identify missing evidence"
            : Q_DEEPER.matcher(s).find() ? "Explain the current course topic in more detail, with examples" : null;
        if (intent == null) return null;
        List<JsonNode> turns = new ArrayList<>();
        for (JsonNode m : history) if (m != null && "user".equals(m.path("role").asText(null))) turns.add(m);
        turns = new ArrayList<>(turns.subList(Math.max(0, turns.size() - 3), turns.size()));
        Collections.reverse(turns);
        for (JsonNode turn : turns) {
            String content = jsTrim(OALA_PREFIX.matcher(turn.path("content").asText("")).replaceFirst(""));
            if (RESET.matcher(content).find()) return null;
            if (content.length() > 700) return null; // Do not truncate away a correction in a long turn.
            boolean anyMatch = SUBJECT.matcher(content).find();
            if (!anyMatch) {
                if (WHITESPACE.split(content).length >= 4) return null;
                continue;
            }
            // Each qualification marker counts as an object, even if joined by "and".
            int count = 0;
            Matcher qm = QUALIFICATION_MARKER.matcher(content);
            while (qm.find()) count++;
            boolean comparing = intent.startsWith("Compare");
            if ((comparing && count != 2) || (!comparing && count != 1)) return null;
            return intent + ".\nUser's request: " + s + "\nUser-provided subject and constraints: " + content;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // chooseRoute -- port of policy.ts's chooseRoute()
    // ---------------------------------------------------------------------

    public RoutingDecision chooseRoute(List<Candidate> candidates, String modelId, String flow) {
        String model = modelId == null ? DEFAULT_ROUTER_MODEL : modelId;
        String f = flow == null ? "route" : flow;
        if (!ROUTER_MODELS.contains(model)) return abstain("unknown-model");
        if (BgeGateService.BGE_MODEL_ID.equals(model)) {
            List<Candidate> allowed = candidates.stream()
                .filter(c -> BgeGateService.BGE_OUT_OF_SCOPE.equals(c.getToolId()) || isEligibleTool(c.getToolId()))
                .collect(Collectors.toList());
            BgeGateService.GateResult d = BgeGateService.bgeGateResult(allowed, bgeGateService.gate(f));
            RoutingDecision out = d.selected() ? new RoutingDecision() : abstain(d.reason());
            out.setModelId(model);
            out.setCalibrationVersion(bgeGateService.getCalibrationVersion());
            out.setRoutingFlow(f);
            out.setScore(d.score());
            out.setMargin(d.margin());
            out.setDomainMargin(d.domainMargin());
            if (d.selected()) {
                out.setStatus("selected");
                out.setToolId(d.toolId());
                out.setReason(d.reason());
                out.setVersion(ROUTER_VERSION);
            }
            return out;
        }
        List<Candidate> ranked = candidates.stream()
            .filter(c -> isEligibleTool(c.getToolId()) && Double.isFinite(c.getScore()) && c.getScore() >= -1 && c.getScore() <= 1)
            .sorted(Comparator.comparingDouble(Candidate::getScore).reversed())
            .collect(Collectors.toList());
        List<Candidate> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : ranked) if (seen.add(c.getToolId())) unique.add(c);
        if (unique.size() < 2) return abstain("incomplete-ranking");
        Candidate first = unique.get(0), second = unique.get(1);
        double margin = first.getScore() - second.getScore();
        if (first.getScore() < MIN_SCORE || margin < MIN_MARGIN) {
            RoutingDecision d = abstain(first.getScore() < MIN_SCORE ? "low-similarity" : "ambiguous");
            d.setScore(first.getScore());
            d.setMargin(margin);
            return d;
        }
        RoutingDecision d = new RoutingDecision();
        d.setModelId(model);
        d.setRoutingFlow(f);
        d.setStatus("selected");
        d.setToolId(first.getToolId());
        d.setScore(first.getScore());
        d.setMargin(margin);
        d.setReason("clear-semantic-match");
        d.setVersion(ROUTER_VERSION);
        return d;
    }

    // ---------------------------------------------------------------------
    // validateRouteSelection -- port of policy.ts's validateRouteSelection() (the trust boundary)
    // ---------------------------------------------------------------------

    /** Server and browser use the same allowlisted profile; client thresholds are never accepted. */
    public RoutingDecision validateRouteSelection(JsonNode r, String flow) {
        String f = flow == null ? "route" : flow;
        if (!truthy(r) || !r.path("version").isTextual() || !ROUTER_VERSION.equals(r.path("version").asText())) return abstain("no-selection");
        JsonNode status = r.path("status");
        if (status.isTextual() && "abstained".equals(status.asText())) {
            JsonNode reason = r.path("reason");
            String value = truthy(reason) ? (reason.isTextual() ? reason.asText() : null) : "";
            return abstain(value != null && KNOWN_ABSTAIN_REASONS.contains(value) ? value : "no-selection");
        }
        if (!status.isTextual() || !"selected".equals(status.asText())) return abstain("no-selection");
        if (!isEligibleNode(r.path("toolId"))) return abstain("unknown-tool");
        JsonNode modelNode = r.path("modelId");
        boolean nullish = modelNode.isMissingNode() || modelNode.isNull();
        if (!nullish && !(modelNode.isTextual() && BgeGateService.BGE_MODEL_ID.equals(modelNode.asText()))) return abstain("unknown-model");
        String modelId = BgeGateService.BGE_MODEL_ID;
        JsonNode routingFlow = r.path("routingFlow");
        if (truthy(routingFlow) && !(routingFlow.isTextual() && f.equals(routingFlow.asText()))) return abstain("invalid-flow");
        BgeGateService.Gate gate = bgeGateService.gate(f);
        JsonNode scoreNode = r.path("score"), marginNode = r.path("margin");
        if (!scoreNode.isNumber() || !Double.isFinite(scoreNode.asDouble()) || scoreNode.asDouble() < gate.score || scoreNode.asDouble() > 1
            || !marginNode.isNumber() || !Double.isFinite(marginNode.asDouble()) || marginNode.asDouble() < gate.margin || marginNode.asDouble() > 2)
            return abstain("invalid-score");
        double score = scoreNode.asDouble(), margin = marginNode.asDouble();
        JsonNode cal = r.path("calibrationVersion"), dm = r.path("domainMargin");
        if (!(cal.isTextual() && bgeGateService.getCalibrationVersion().equals(cal.asText()))
            || !(routingFlow.isTextual() && f.equals(routingFlow.asText()))
            || !dm.isNumber() || !Double.isFinite(dm.asDouble()) || dm.asDouble() < gate.domainMargin || dm.asDouble() > score + 1)
            return abstain("invalid-calibration");
        RoutingDecision decision = new RoutingDecision();
        decision.setStatus("selected");
        decision.setToolId(r.path("toolId").asText());
        decision.setScore(score);
        decision.setMargin(margin);
        decision.setModelId(modelId);
        decision.setRoutingFlow(f);
        decision.setCalibrationVersion(bgeGateService.getCalibrationVersion());
        decision.setDomainMargin(dm.asDouble());
        decision.setReason("clear-semantic-match");
        decision.setVersion(ROUTER_VERSION);
        return decision;
    }

    /**
     * Used by RoutingController's /api/routing/validate (a Java-port endpoint with no original
     * counterpart): the exact validateRouteSelection() plus that endpoint's existing BGE ready-handshake check.
     */
    public RoutingDecision validateRouteSelection(RouteClaimRequest claim, String flow) {
        RoutingDecision decision = validateRouteSelection(claim == null ? null : (JsonNode) MAPPER.valueToTree(claim), flow);
        if ("selected".equals(decision.getStatus()) && !bgeGateService.validBgeReady(claim)) return abstain("unknown-model");
        return decision;
    }

    /** Browser routing is advisory. Only allowlisted catalogue text can reach Gemini. */
    public RoutingDecision acceptClientRoute(JsonNode value, String mode, String text, boolean structuredAnswer, List<JsonNode> history) {
        OalaService.OalaMention mention = OalaService.parseOalaMention(text);
        if (!mention.active()) return abstain("not-addressed");
        String skip = routingInput(mention.message(), history == null ? List.of() : history, structuredAnswer).skip();
        if (skip != null) return abstain(skip);
        return validateRouteSelection(value, "route");
    }

    // ---------------------------------------------------------------------
    // scopedInstruction -- port of policy.ts's scopedInstruction()
    // ---------------------------------------------------------------------

    public String scopedInstruction(RoutingDecision decision) {
        if (decision == null || !"selected".equals(decision.getStatus())) return "";
        MicroTool tool = findEligibleTool(decision.getToolId());
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

    // ---------------------------------------------------------------------
    // JavaScript-semantics helpers shared by the routing ports
    // ---------------------------------------------------------------------

    /** ECMAScript WhiteSpace + LineTerminator (what JS \s and String.prototype.trim use). */
    private static final String JS_WS = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    /**
     * Compiles a JS regex source (no u/m/s flags) with JS semantics: \s/\S use the ECMAScript whitespace
     * set, "$" means end of input and "." excludes only \n \r    .
     */
    public static Pattern jsRegex(String source, boolean ignoreCase) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (int k = 0; k < source.length(); k++) {
            char c = source.charAt(k);
            if (c == '\\' && k + 1 < source.length()) {
                char n = source.charAt(++k);
                if (n == 's') out.append(inClass ? JS_WS : "[" + JS_WS + "]");
                else if (n == 'S') out.append("[^" + JS_WS + "]");
                else out.append(c).append(n);
            } else if (inClass) {
                if (c == ']') inClass = false;
                out.append(c);
            } else if (c == '[') {
                inClass = true;
                out.append(c);
            } else if (c == '$') {
                out.append("\\z");
            } else if (c == '.') {
                out.append("[^\\n\\r\\u2028\\u2029]");
            } else {
                out.append(c);
            }
        }
        return Pattern.compile(out.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
    }

    private static boolean isJsWhitespace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0 || c == 0x1680
            || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
            || c == 0x3000 || c == 0xFEFF;
    }

    /** String.prototype.trim(). */
    public static String jsTrim(String s) {
        int start = 0, end = s.length();
        while (start < end && isJsWhitespace(s.charAt(start))) start++;
        while (end > start && isJsWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** JS truthiness of a JSON value (Java null / missing = undefined). */
    public static boolean truthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return !node.asText().isEmpty();
        if (node.isNumber()) return node.asDouble() != 0 && !Double.isNaN(node.asDouble());
        return true;
    }

    /** JS === between two JSON values (objects/arrays are never equal: distinct references). */
    public static boolean strictEquals(JsonNode a, JsonNode b) {
        boolean aUndef = a == null || a.isMissingNode(), bUndef = b == null || b.isMissingNode();
        if (aUndef || bUndef) return aUndef && bUndef;
        if (a.isNull() || b.isNull()) return a.isNull() && b.isNull();
        if (a.isTextual() && b.isTextual()) return a.asText().equals(b.asText());
        if (a.isNumber() && b.isNumber()) return a.asDouble() == b.asDouble();
        if (a.isBoolean() && b.isBoolean()) return a.asBoolean() == b.asBoolean();
        return false;
    }
}
