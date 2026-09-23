package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.HistoryTurn;
import com.yuzee.tokenlab.model.NeedHint;
import com.yuzee.tokenlab.model.TurnNeeds;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim;
import static com.yuzee.tokenlab.service.RoutingPolicyService.truthy;

/** Port of src/routing/turnNeeds.ts (server half: acceptNeedHint, assessTurnNeeds, needsInstruction, researchOffer, needsQuery). */
@Service
public class TurnNeedsService {

    private static final List<String> NEED_KINDS = List.of("answer", "clarify", "research");
    private static final List<String> KNOWN_ABSTAIN = List.of("incomplete-ranking", "uncertain", "cancelled", "input-length",
        "not-ready", "busy", "timeout", "unavailable", "inference-failed", "token-budget");
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final BgeGateService bgeGateService;

    public TurnNeedsService(BgeGateService bgeGateService) {
        this.bgeGateService = bgeGateService;
    }

    /** Converts original message objects {role, content, preflight, telemetry{preflight}} to NeedsHistory turns. */
    public static List<HistoryTurn> historyTurns(List<JsonNode> messages) {
        List<HistoryTurn> out = new ArrayList<>();
        if (messages == null) return out;
        for (JsonNode m : messages) {
            HistoryTurn turn = new HistoryTurn(m.path("role").asText(null), m.path("content").asText(""));
            JsonNode preflight = truthy(m.path("preflight")) ? m.path("preflight") : m.path("telemetry").path("preflight");
            if (preflight.isObject()) turn.setPreflight(MAPPER.convertValue(preflight, TurnNeeds.class));
            out.add(turn);
        }
        return out;
    }

    /** Kept for existing callers: conversationQuery.ts resolveShortQuery() over NeedsHistory turns. */
    public String resolveShortQuery(String request, List<HistoryTurn> history) {
        List<JsonNode> nodes = new ArrayList<>();
        if (history != null) for (HistoryTurn t : history) nodes.add(MAPPER.valueToTree(t));
        return RoutingPolicyService.resolveShortQuery(request, nodes);
    }

    // ---------------------------------------------------------------------
    // acceptNeedHint
    // ---------------------------------------------------------------------

    private NeedHint acceptNeedHint(JsonNode h) {
        JsonNode status = h == null ? null : h.path("status");
        if (status != null && status.isTextual() && "abstained".equals(status.asText())) {
            JsonNode reason = h.path("reason");
            return NeedHint.abstained(reason.isTextual() && KNOWN_ABSTAIN.contains(reason.asText()) ? reason.asText() : "not-provided");
        }
        JsonNode modelId = h == null ? null : h.path("modelId");
        JsonNode kind = h == null ? null : h.path("kind");
        boolean bge = modelId != null && modelId.isTextual() && BgeGateService.BGE_MODEL_ID.equals(modelId.asText());
        BgeGateService.Gate gate = bge ? bgeGateService.needGate(kind.isTextual() ? kind.asText() : null) : new BgeGateService.Gate(.5, .08, 0);
        JsonNode profileVersion = h == null ? null : h.path("profileVersion");
        if (truthy(modelId) && (!bge || !(profileVersion.isTextual() && BgeGateService.NEEDS_PROFILE_VERSION.equals(profileVersion.asText())))) return null;
        if (!truthy(h) || status == null || !status.isTextual() || !"selected".equals(status.asText())
            || !kind.isTextual() || !NEED_KINDS.contains(kind.asText())) return null;
        JsonNode score = h.path("score"), margin = h.path("margin");
        if (!score.isNumber() || !Double.isFinite(score.asDouble()) || score.asDouble() < gate.score || score.asDouble() > 1
            || !margin.isNumber() || !Double.isFinite(margin.asDouble()) || margin.asDouble() < gate.margin || margin.asDouble() > 2) return null;
        NeedHint accepted = new NeedHint();
        accepted.setStatus("selected");
        accepted.setKind(kind.asText());
        accepted.setScore(score.asDouble());
        accepted.setMargin(margin.asDouble());
        accepted.setReason("semantic-match");
        if (bge) {
            accepted.setModelId(BgeGateService.BGE_MODEL_ID);
            accepted.setProfileVersion(BgeGateService.NEEDS_PROFILE_VERSION);
        }
        return accepted;
    }

    // ---------------------------------------------------------------------
    // assessTurnNeeds
    // ---------------------------------------------------------------------

    private static final Pattern LABELLED_TARGET = jsRegex("(?:^|\\n)\\s*(?:course|qualification|option)\\s*:\\s*([^\\n?!.]{3,250})", true);
    private static final Pattern NAMED_TARGET = jsRegex(
        "\\b((?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)[\\w\\s'’&()-]{2,110}?\\s+(?:at|from)\\s+)([^\\n?.!,;]{2,140})", true);
    private static final Pattern PROVIDER_SPLIT = jsRegex(
        "\\s+(?:in\\s+20\\d\\d|for|cost|costs|fees|with|while|but|and I|because|please)\\b", true);
    private static final Pattern BOUNDARY = jsRegex(
        "\\b(stop|pause|cancel|no more|don['’]t search|do not search|no research|never mind|suicid\\w*|self.harm|emergency)\\b", true);
    private static final Pattern FRESH_TOPIC = jsRegex(
        "\\b(?:instead|different (?:course|topic)|new topic|forget (?:that|the course)|not (?:that|this) course)\\b", true);
    private static final Pattern LOOKUP = jsRegex(
        "\\b(fees?|tuition|entry requirements?|admission requirements?|application deadlines?|intake dates?|timetable|attendance requirements?|scholarships?|accreditation|placement requirements?)\\b", true);
    private static final Pattern CONCEPT = jsRegex(
        "\\b(?:what (?:is|are|does)|explain|meaning of|define|teach)\\b[\\s\\S]*\\b(?:mean|meaning|concept|in general|simply|simple terms)\\b|\\b(?:what is (?:an? )?(?:elective|prerequisite|scholarship|tuition fee|accreditation)|explain (?:electives|prioritisation|communication))\\b", true);
    private static final Pattern SELF_FIT = jsRegex(
        "\\b(?:fit|manage|juggle|balance|suit|manageable)\\b[\\s\\S]*\\b(?:work|family|children|childcare|study|schedule)\\b|\\b(?:work|family|children|childcare)\\b[\\s\\S]*\\b(?:fit|manage|juggle|balance|study)\\b", true);
    private static final Pattern SAVED_RESEARCH = jsRegex("Research reference: [a-f0-9-]{36}", false);
    private static final Pattern CORRECTION = jsRegex("^actually\\b|^correction\\b", true);
    private static final Pattern COMPARE = jsRegex("\\b(compare|versus)\\b", true);
    private static final Pattern CALCULATE = jsRegex("\\b(calculate|add|total|sum)\\b", true);
    private static final Pattern DIGIT = jsRegex("\\d", false);
    private static final Pattern TEACH_TERM = jsRegex(
        "^what (?:is|are) (?:an? |the )?(?:entry requirements?|tuition fees?|scholarships?|accreditation)[?.!\\s]*$", true);
    private static final Pattern SMALLTALK = jsRegex("^(?:hi|hello|thanks|thank you|yes|no|okay|not sure)[.!\\s]*$", true);
    private static final Pattern ABOUT_YUZEE = jsRegex("\\b(?:what (?:does yuzee|services)|who are you|what is yuzee)\\b", true);
    private static final Pattern REFERENT = jsRegex("\\b(this|that|it|same|the course|these units)\\b", true);
    private static final Pattern STUDY_YEAR = jsRegex("\\b20[2-9]\\d\\b", false);
    private static final Pattern ENROL = jsRegex("\\b(?:can I|when can I|how do I) enrol\\b", true);
    private static final Pattern CHECK_WORDS = jsRegex("\\b(check|look up|find|verify|current|latest|official)\\b", true);
    private static final Pattern COURSE_WORDS = jsRegex("\\b(course|units|syllabus|provider|university)\\b", true);
    private static final Pattern SEMANTIC_RESEARCH_WORDS = jsRegex("\\b(course|provider|university|enrol|enroll|admission)\\b", true);
    private static final Pattern FEES = jsRegex("fees?|tuition", true);
    private static final Pattern ENTRY = jsRegex("entry|admission", true);
    private static final Pattern ATTENDANCE = jsRegex("timetable|attendance", true);
    private static final Pattern CLARIFY_WORDS = jsRegex("\\b(study|work|family|children)\\b", true);
    private static final Pattern STUDY_HOURS = jsRegex(
        "\\b(?:study|studying)\\s+(?:for\\s+)?\\d+\\s*(?:hours?|hrs?)\\b|\\b(?:have|spare|available)\\s+(?:about\\s+)?\\d+\\s*(?:hours?|hrs?)\\b|\\b\\d+\\s*(?:hours?|hrs?)\\s+(?:a|per|each)\\s+week\\s+(?:for|to)\\s+study\\b", true);

    /** Only explicit user wording is copied. No assistant claims are promoted to scope. */
    private static String explicitTarget(String text) {
        Matcher labelled = LABELLED_TARGET.matcher(text);
        if (labelled.find()) return jsTrim(labelled.group(1));
        Matcher named = NAMED_TARGET.matcher(text);
        if (!named.find()) return "";
        Matcher split = PROVIDER_SPLIT.matcher(named.group(2));
        String provider = jsTrim(split.find() ? named.group(2).substring(0, split.start()) : named.group(2));
        return !provider.isEmpty() ? jsTrim(named.group(1) + provider) : "";
    }

    private static boolean pendingNeedsCourse(TurnNeeds pending) {
        return pending != null && "clarify".equals(pending.getAction())
            && pending.getMissing() != null && pending.getMissing().contains("course-and-provider");
    }

    private static List<HistoryTurn> lastUserTurns(List<HistoryTurn> history) {
        List<HistoryTurn> users = new ArrayList<>();
        for (HistoryTurn t : history) if ("user".equals(t.getRole())) users.add(t);
        return new ArrayList<>(users.subList(Math.max(0, users.size() - 3), users.size()));
    }

    private static String content(HistoryTurn t) { return t.getContent() == null ? "" : t.getContent(); }

    /** Inspect each submitted turn before generation. Never turns similarity into evidence. */
    public TurnNeeds assessTurnNeeds(String text, List<HistoryTurn> history, boolean structured, String location, NeedHint hint) {
        return assessTurnNeeds(text, history, structured, location, hint == null ? null : (JsonNode) MAPPER.valueToTree(hint));
    }

    /** assessTurnNeeds() taking the untrusted client hint (req.body.needsAssessment) as raw JSON. */
    public TurnNeeds assessTurnNeeds(String inputText, List<HistoryTurn> inputHistory, boolean structured, String location, JsonNode rawHint) {
        String text = OalaService.parseOalaMention(inputText).message();
        NeedHint hint = acceptNeedHint(rawHint);
        TurnNeeds result = new TurnNeeds();
        result.setQuestion(text);
        result.setClassifier(hint != null ? hint : NeedHint.abstained(structured ? "quiz-answer" : "not-provided"));
        result.getScope().setLocation(location != null ? location.substring(0, Math.min(150, location.length())) : "");
        List<HistoryTurn> history = inputHistory == null ? List.of() : inputHistory;
        if (structured) {
            HistoryTurn last = history.isEmpty() ? null : history.get(history.size() - 1);
            TurnNeeds pending = last != null && "assistant".equals(last.getRole()) ? last.getPreflight() : null;
            // The server validates the active interaction first. Completing our specific
            // course clarification may unlock the offer; unrelated Quiz events keep their controller.
            if (pendingNeedsCourse(pending) && !explicitTarget(text).isEmpty())
                return assessTurnNeeds(inputText, inputHistory, false, location, (JsonNode) null);
            result.setReason("quiz-answer");
            return result;
        }
        if (BOUNDARY.matcher(text).find() || SAVED_RESEARCH.matcher(text).find()) { result.setReason("respect-boundary-or-saved-research"); return result; }
        if (jsTrim(text).isEmpty() || text.length() > 1800) { result.setReason("defer-complex-input-to-counsellor"); return result; }
        if (CORRECTION.matcher(text).find() && explicitTarget(text).isEmpty()) { result.setReason("user-correction"); return result; }
        if (text.chars().filter(c -> c == '?').count() > 1 || COMPARE.matcher(text).find()) { result.setReason("multi-option-needs-counsellor"); return result; }
        if (CALCULATE.matcher(text).find() && DIGIT.matcher(text).find()) { result.setReason("use-supplied-numbers"); return result; }
        if (TEACH_TERM.matcher(text).find()) { result.setReason("teach-without-search"); return result; }
        if (SMALLTALK.matcher(text).find() || ABOUT_YUZEE.matcher(text).find()) return result;
        HistoryTurn previous = history.isEmpty() ? null : history.get(history.size() - 1);
        TurnNeeds pending = previous != null && "assistant".equals(previous.getRole()) ? previous.getPreflight() : null;
        boolean continuation = pendingNeedsCourse(pending) && !FRESH_TOPIC.matcher(text).find() && !explicitTarget(text).isEmpty();
        String question = continuation ? pending.getQuestion() : text;
        String ownTarget = explicitTarget(text);
        // Only carry a previous target for an explicit referent, not into an unrelated topic.
        boolean referent = REFERENT.matcher(question).find() || continuation;
        String previousTarget = "", previousTargetText = "";
        if (referent && !FRESH_TOPIC.matcher(text).find()) {
            List<HistoryTurn> recent = lastUserTurns(history);
            Collections.reverse(recent);
            for (HistoryTurn message : recent) {
                if (FRESH_TOPIC.matcher(content(message)).find()) break;
                previousTarget = explicitTarget(content(message));
                if (!previousTarget.isEmpty()) { previousTargetText = content(message); break; }
            }
        }
        result.getScope().setTarget(!ownTarget.isEmpty() ? ownTarget : previousTarget);
        Matcher ownYear = STUDY_YEAR.matcher(text), previousYear = STUDY_YEAR.matcher(previousTargetText);
        String pendingYear = continuation && pending.getScope() != null ? pending.getScope().getStudyYear() : null;
        result.getScope().setStudyYear(ownYear.find() ? ownYear.group()
            : pendingYear != null && !pendingYear.isEmpty() ? pendingYear
            : previousYear.find() ? previousYear.group() : "");
        result.setQuestion(continuation ? question + "\nCourse: " + ownTarget : text);
        if (CONCEPT.matcher(text).find()) { result.setReason("teach-without-search"); return result; }
        boolean needsResearch = LOOKUP.matcher(question).find()
            || (ENROL.matcher(question).find() && !result.getScope().getTarget().isEmpty())
            || (CHECK_WORDS.matcher(question).find() && COURSE_WORDS.matcher(question).find());
        boolean semanticResearch = hint != null && "research".equals(hint.getKind()) && SEMANTIC_RESEARCH_WORDS.matcher(text).find();
        if (needsResearch || semanticResearch) {
            result.setBasis(needsResearch ? "rules" : "minilm");
            if (result.getScope().getTarget().isEmpty()) {
                result.setAction("clarify");
                result.setReason("research-needs-scope");
                result.setMissing(List.of("course-and-provider"));
                return result;
            }
            result.setAction("research");
            result.setReason("specific-information-needs-sources");
            result.setResearch(new TurnNeeds.Research(
                FEES.matcher(question).find() ? "Check course fees" : ENTRY.matcher(question).find() ? "Check entry requirements"
                    : ATTENDANCE.matcher(question).find() ? "Check attendance details" : "Check official course details",
                "Look up relevant sources for this question. Review the details before searching."));
            return result;
        }
        if (SELF_FIT.matcher(text).find() || (hint != null && "clarify".equals(hint.getKind()) && CLARIFY_WORDS.matcher(text).find())) {
            List<String> userContext = new ArrayList<>();
            for (HistoryTurn t : lastUserTurns(history)) userContext.add(content(t));
            userContext.add(text);
            if (!STUDY_HOURS.matcher(String.join("\n", userContext)).find()) {
                result.setAction("clarify");
                result.setReason("personal-fit-needs-availability");
                result.setMissing(List.of("available-study-time"));
                result.setBasis(SELF_FIT.matcher(text).find() ? "rules" : "minilm");
            }
        }
        return result;
    }

    public String needsInstruction(TurnNeeds plan) {
        String direction = "clarify".equals(plan.getAction())
            ? "Give any useful general answer, then ask only the smallest unresolved question in the existing interaction schema. Missing candidate: "
                + String.join(", ", plan.getMissing()) + ". Check full conversation first: if already answered or irrelevant do not ask again. Do not guess the user's constraints. Do not show a research form yet. A fee clarification needs the course and provider, not an unsolicited explanation of government funding rules."
            : "research".equals(plan.getAction())
                ? "This specific request may need current source evidence. Explain what is known and what needs checking. The interface can offer an optional scoped lookup. No lookup has happened yet: do not claim to have searched, confirmed fees, or verified a provider. Unknown publication status is not proof that rates are not yet published. Do not supply specific subsidy bands, funding eligibility, mandatory extra charges or provider requirements from memory, including in option descriptions. Ask only a necessary fee-category question using plain labels without asserting eligibility. Do not repeat a generic intake or tell the user to fill a second generic form."
                : "Answer the actual question with useful depth. Do not add a generic research invitation or ask what the user wants to know after they already told you. Ask a follow-up only if genuinely necessary.";
        return "TURN_NEEDS_GUIDANCE (server-owned, advisory; preserve main prompt and canonical JSON):\n" + direction
            + "\nThe classifier is fallible; prioritise the actual request and retained context. Similarity is not evidence. Financial rules, fee figures, funding eligibility and repayment calculations require dated relevant evidence; without it give a conditional conceptual explanation and identify the missing source. Never quote internal routing labels. Research remains a separate explicit user action.";
    }

    public TurnNeeds researchOffer(TurnNeeds plan) {
        return plan != null && "turn-needs-v1".equals(plan.getVersion()) && "research".equals(plan.getAction())
            && plan.getResearch() != null && plan.getScope() != null && plan.getScope().getTarget() != null
            && !plan.getScope().getTarget().isEmpty() ? plan : null;
    }

    /** Add only a resolved user-stated course, not a transcript or inferred personal profile. */
    public String needsQuery(String text, List<HistoryTurn> history) {
        String current = OalaService.parseOalaMention(text).message();
        TurnNeeds.Scope scope = assessTurnNeeds(current, history, false, null, (JsonNode) null).getScope();
        if (scope.getTarget().isEmpty() || current.contains(scope.getTarget())) return current;
        String query = "Current question: " + current + "\nUser-stated course: " + scope.getTarget();
        return query.length() <= 1800 ? query : current;
    }
}
