package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.HistoryTurn;
import com.yuzee.tokenlab.model.NeedHint;
import com.yuzee.tokenlab.model.TurnNeeds;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of src/routing/turnNeeds.ts (assessTurnNeeds/needsInstruction/researchOffer/needsQuery)
 * folded together with src/routing/conversationQuery.ts (resolveShortQuery), exactly as the
 * task groups them -- both are tiny and used by the same caller.
 *
 * assessTurnNeeds() never lets client-side similarity stand in for evidence: the rule-based
 * checks always run, and an optional BGE hint is only used to catch a rules-miss, gated through
 * the same score/margin thresholds as everything else in this package.
 */
@Service
public class TurnNeedsService {

    private static final Set<String> NEED_KINDS = Set.of("answer", "clarify", "research");

    private final BgeGateService bgeGateService;

    public TurnNeedsService(BgeGateService bgeGateService) {
        this.bgeGateService = bgeGateService;
    }

    // ---------------------------------------------------------------------
    // conversationQuery.ts -- resolveShortQuery()
    // ---------------------------------------------------------------------

    private static final Pattern RESET = Pattern.compile(
        "\\b(?:new topic|different topic|forget that|instead|not that course|cancel|stop)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT = Pattern.compile(
        "\\b(?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)\\b[^\\n.!?;]{2,180}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFICATION_MARKER = Pattern.compile(
        "\\b(?:Bachelor|Master|Diploma|Certificate)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WS = Pattern.compile("\\s+");

    /** Only explicit user subjects can resolve a reference. Assistant prose is never copied as fact. */
    public String resolveShortQuery(String request, List<HistoryTurn> history) {
        String s = request == null ? "" : request.trim();
        if (s.length() > 160 || WS.split(s).length > 12 || history == null || history.isEmpty()) return null;

        String intent;
        if (matchesWhole(s, "^(?:what about (?:the )?costs?|(?:course )?(?:costs?|fees)|how much)[?.!\\s]*$"))
            intent = "Explain course tuition, funding and additional costs";
        else if (matchesWhole(s, "^(?:what jobs|what about jobs)[?.!\\s]*$"))
            intent = "Explain career outcomes and jobs after this course";
        else if (matchesWhole(s, "^(?:quality|course quality)[?.!\\s]*$"))
            intent = "Explain how to evaluate this course and provider quality";
        else if (matchesWhole(s, "^(?:compare (?:them|these|those)|compare (?:the )?two)[?.!\\s]*$"))
            intent = "Compare these two courses for the user";
        else if (matchesWhole(s, "^(?:does (?:that|this) qualify me)[?.!\\s]*$"))
            intent = "Explain eligibility and qualification requirements; identify missing evidence";
        else if (matchesWhole(s, "^(?:go deeper|tell me more|more details|explain (?:it|that|this)|why)[?.!\\s]*$"))
            intent = "Explain the current course topic in more detail, with examples";
        else
            return null;

        List<HistoryTurn> userTurns = new ArrayList<>();
        for (HistoryTurn t : history) if ("user".equals(t.getRole())) userTurns.add(t);
        int from = Math.max(0, userTurns.size() - 3);
        List<HistoryTurn> lastThree = new ArrayList<>(userTurns.subList(from, userTurns.size()));
        Collections.reverse(lastThree);

        boolean comparing = intent.startsWith("Compare");
        for (HistoryTurn turn : lastThree) {
            String content = (turn.getContent() == null ? "" : turn.getContent()).replaceFirst("(?i)^@oala\\s*", "").trim();
            if (RESET.matcher(content).find()) return null;
            if (content.length() > 700) return null; // Do not truncate away a correction in a long turn.
            List<String> matches = new ArrayList<>();
            Matcher m = SUBJECT.matcher(content);
            while (m.find()) matches.add(m.group().trim());
            if (matches.isEmpty()) {
                if (WS.split(content).length >= 4) return null;
                continue;
            }
            // Each qualification marker counts as an object, even if joined by "and".
            int count = 0;
            Matcher qm = QUALIFICATION_MARKER.matcher(content);
            while (qm.find()) count++;
            if ((comparing && count != 2) || (!comparing && count != 1)) return null;
            return intent + ".\nUser's request: " + s + "\nUser-provided subject and constraints: " + content;
        }
        return null;
    }

    private static boolean matchesWhole(String s, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(s).matches();
    }

    // ---------------------------------------------------------------------
    // turnNeeds.ts -- assessTurnNeeds() and friends
    // ---------------------------------------------------------------------

    private static final Pattern BOUNDARY = Pattern.compile(
        "\\b(stop|pause|cancel|no more|don['’]t search|do not search|no research|never mind|suicid\\w*|self.harm|emergency)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SAVED_RESEARCH = Pattern.compile("Research reference: [a-f0-9-]{36}");
    private static final Pattern FRESH_TOPIC = Pattern.compile(
        "\\b(?:instead|different (?:course|topic)|new topic|forget (?:that|the course)|not (?:that|this) course)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKUP = Pattern.compile(
        "\\b(fees?|tuition|entry requirements?|admission requirements?|application deadlines?|intake dates?|"
            + "timetable|attendance requirements?|scholarships?|accreditation|placement requirements?)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCEPT = Pattern.compile(
        "\\b(?:what (?:is|are|does)|explain|meaning of|define|teach)\\b[\\s\\S]*\\b(?:mean|meaning|concept|in general|"
            + "simply|simple terms)\\b|\\b(?:what is (?:an? )?(?:elective|prerequisite|scholarship|tuition fee|"
            + "accreditation)|explain (?:electives|prioritisation|communication))\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_FIT = Pattern.compile(
        "\\b(?:fit|manage|juggle|balance|suit|manageable)\\b[\\s\\S]*\\b(?:work|family|children|childcare|study|"
            + "schedule)\\b|\\b(?:work|family|children|childcare)\\b[\\s\\S]*\\b(?:fit|manage|juggle|balance|study)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTUALLY_OR_CORRECTION = Pattern.compile("^actually\\b|^correction\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_OR_COMPARE = Pattern.compile("\\b(compare|versus)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALCULATE = Pattern.compile("\\b(calculate|add|total|sum)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final Pattern TEACH_WITHOUT_SEARCH = Pattern.compile(
        "^what (?:is|are) (?:an? |the )?(?:entry requirements?|tuition fees?|scholarships?|accreditation)[?.!\\s]*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SMALLTALK = Pattern.compile(
        "^(?:hi|hello|thanks|thank you|yes|no|okay|not sure)[.!\\s]*$|"
            + "\\b(?:what (?:does yuzee|services)|who are you|what is yuzee)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENT = Pattern.compile("\\b(this|that|it|same|the course|these units)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENROL = Pattern.compile("\\b(?:can I|when can I|how do I) enrol\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK_WORDS = Pattern.compile("\\b(check|look up|find|verify|current|latest|official)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSE_WORDS = Pattern.compile("\\b(course|units|syllabus|provider|university)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSE_ONLY = Pattern.compile("\\b(course|provider|university|enrol|enroll|admission)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STUDY_HOURS_STATED = Pattern.compile(
        "\\b(?:study|studying)\\s+(?:for\\s+)?\\d+\\s*(?:hours?|hrs?)\\b|"
            + "\\b(?:have|spare|available)\\s+(?:about\\s+)?\\d+\\s*(?:hours?|hrs?)\\b|"
            + "\\b\\d+\\s*(?:hours?|hrs?)\\s+(?:a|per|each)\\s+week\\s+(?:for|to)\\s+study\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_FIT_CONTEXT = Pattern.compile("\\b(study|work|family|children)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEES_OR_TUITION = Pattern.compile("fees?|tuition", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_OR_ADMISSION = Pattern.compile("entry|admission", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMETABLE_OR_ATTENDANCE = Pattern.compile("timetable|attendance", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELLED_TARGET = Pattern.compile(
        "(?:^|\\n)\\s*(?:course|qualification|option)\\s*:\\s*([^\\n?!.]{3,250})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_TARGET = Pattern.compile(
        "\\b((?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)[\\w\\s'’&()-]{2,110}?\\s+"
            + "(?:at|from)\\s+)([^\\n?.!,;]{2,140})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVIDER_SPLIT = Pattern.compile(
        "\\s+(?:in\\s+20\\d\\d|for|cost|costs|fees|with|while|but|and I|because|please)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STUDY_YEAR = Pattern.compile("\\b20[2-9]\\d\\b");

    /** Only explicit user wording is copied. No assistant claims are promoted to scope. */
    private static String explicitTarget(String text) {
        if (text == null) return "";
        Matcher labelled = LABELLED_TARGET.matcher(text);
        if (labelled.find()) return labelled.group(1).trim();
        Matcher named = NAMED_TARGET.matcher(text);
        if (!named.find()) return "";
        String provider = PROVIDER_SPLIT.split(named.group(2), 2)[0].trim();
        return provider.isEmpty() ? "" : (named.group(1) + provider).trim();
    }

    /** Inspect each submitted turn before generation. Never turns similarity into evidence. */
    public TurnNeeds assessTurnNeeds(String rawText, List<HistoryTurn> history, boolean structured, String location, NeedHint rawHint) {
        String text = rawText == null ? "" : rawText;
        List<HistoryTurn> hist = history == null ? List.of() : history;
        NeedHint hint = acceptNeedHint(rawHint);

        TurnNeeds result = new TurnNeeds();
        result.setQuestion(text);
        result.getScope().setLocation(location == null ? "" : location.substring(0, Math.min(150, location.length())));
        result.setClassifier(hint != null ? hint : NeedHint.abstained(structured ? "quiz-answer" : "not-provided"));

        if (structured) {
            HistoryTurn last = hist.isEmpty() ? null : hist.get(hist.size() - 1);
            TurnNeeds pending = (last != null && "assistant".equals(last.getRole())) ? last.getPreflight() : null;
            // The server validates the active interaction first. Completing our specific course clarification
            // may unlock the offer; unrelated Quiz events keep their controller.
            if (pending != null && "clarify".equals(pending.getAction()) && pending.getMissing().contains("course-and-provider")
                && !explicitTarget(text).isEmpty()) {
                return assessTurnNeeds(text, hist, false, location, null);
            }
            result.setReason("quiz-answer");
            return result;
        }

        if (BOUNDARY.matcher(text).find() || SAVED_RESEARCH.matcher(text).find()) {
            result.setReason("respect-boundary-or-saved-research");
            return result;
        }
        if (text.trim().isEmpty() || text.length() > 1800) {
            result.setReason("defer-complex-input-to-counsellor");
            return result;
        }
        if (ACTUALLY_OR_CORRECTION.matcher(text).find() && explicitTarget(text).isEmpty()) {
            result.setReason("user-correction");
            return result;
        }
        long questionMarks = text.chars().filter(c -> c == '?').count();
        if (questionMarks > 1 || MULTI_OR_COMPARE.matcher(text).find()) {
            result.setReason("multi-option-needs-counsellor");
            return result;
        }
        if (CALCULATE.matcher(text).find() && HAS_DIGIT.matcher(text).find()) {
            result.setReason("use-supplied-numbers");
            return result;
        }
        if (TEACH_WITHOUT_SEARCH.matcher(text).matches()) {
            result.setReason("teach-without-search");
            return result;
        }
        if (SMALLTALK.matcher(text).find()) {
            return result;
        }

        HistoryTurn previous = hist.isEmpty() ? null : hist.get(hist.size() - 1);
        TurnNeeds pending = (previous != null && "assistant".equals(previous.getRole())) ? previous.getPreflight() : null;
        boolean continuation = pending != null && "clarify".equals(pending.getAction())
            && pending.getMissing().contains("course-and-provider") && !FRESH_TOPIC.matcher(text).find()
            && !explicitTarget(text).isEmpty();
        String question = continuation ? pending.getQuestion() : text;
        String ownTarget = explicitTarget(text);
        // Only carry a previous target for an explicit referent, not into an unrelated topic.
        boolean referent = REFERENT.matcher(question).find() || continuation;
        String previousTarget = "", previousTargetText = "";
        if (referent && !FRESH_TOPIC.matcher(text).find()) {
            List<HistoryTurn> userTurns = new ArrayList<>();
            for (HistoryTurn t : hist) if ("user".equals(t.getRole())) userTurns.add(t);
            int from = Math.max(0, userTurns.size() - 3);
            List<HistoryTurn> lastThree = new ArrayList<>(userTurns.subList(from, userTurns.size()));
            Collections.reverse(lastThree);
            for (HistoryTurn message : lastThree) {
                if (FRESH_TOPIC.matcher(message.getContent()).find()) break;
                previousTarget = explicitTarget(message.getContent());
                if (!previousTarget.isEmpty()) {
                    previousTargetText = message.getContent();
                    break;
                }
            }
        }
        result.getScope().setTarget(!ownTarget.isEmpty() ? ownTarget : previousTarget);
        Matcher yearInText = STUDY_YEAR.matcher(text);
        Matcher yearInPrev = STUDY_YEAR.matcher(previousTargetText);
        String studyYear = yearInText.find() ? yearInText.group()
            : (continuation ? pending.getScope().getStudyYear() : "");
        if (studyYear.isEmpty() && yearInPrev.find()) studyYear = yearInPrev.group();
        result.getScope().setStudyYear(studyYear == null ? "" : studyYear);
        result.setQuestion(continuation ? question + "\nCourse: " + ownTarget : text);

        if (CONCEPT.matcher(text).find()) {
            result.setReason("teach-without-search");
            return result;
        }

        boolean needsResearch = LOOKUP.matcher(question).find()
            || (ENROL.matcher(question).find() && !result.getScope().getTarget().isEmpty())
            || (CHECK_WORDS.matcher(question).find() && COURSE_WORDS.matcher(question).find());
        boolean semanticResearch = hint != null && "research".equals(hint.getKind()) && COURSE_ONLY.matcher(text).find();

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
            String title = FEES_OR_TUITION.matcher(question).find() ? "Check course fees"
                : ENTRY_OR_ADMISSION.matcher(question).find() ? "Check entry requirements"
                : TIMETABLE_OR_ATTENDANCE.matcher(question).find() ? "Check attendance details"
                : "Check official course details";
            result.setResearch(new TurnNeeds.Research(title,
                "Look up relevant sources for this question. Review the details before searching."));
            return result;
        }

        if (SELF_FIT.matcher(text).find() || (hint != null && "clarify".equals(hint.getKind()) && SELF_FIT_CONTEXT.matcher(text).find())) {
            String userContext = String.join("\n", collectRecentUserContent(hist, 3)) + "\n" + text;
            if (!STUDY_HOURS_STATED.matcher(userContext).find()) {
                result.setAction("clarify");
                result.setReason("personal-fit-needs-availability");
                result.setMissing(List.of("available-study-time"));
                result.setBasis(SELF_FIT.matcher(text).find() ? "rules" : "minilm");
            }
        }
        return result;
    }

    private static List<String> collectRecentUserContent(List<HistoryTurn> hist, int n) {
        List<String> userTexts = new ArrayList<>();
        for (HistoryTurn t : hist) if ("user".equals(t.getRole())) userTexts.add(t.getContent());
        int from = Math.max(0, userTexts.size() - n);
        return userTexts.subList(from, userTexts.size());
    }

    public String needsInstruction(TurnNeeds plan) {
        String direction;
        if ("clarify".equals(plan.getAction())) {
            direction = "Give any useful general answer, then ask only the smallest unresolved question in the "
                + "existing interaction schema. Missing candidate: " + String.join(", ", plan.getMissing()) + ". "
                + "Check full conversation first: if already answered or irrelevant do not ask again. Do not guess "
                + "the user's constraints. Do not show a research form yet. A fee clarification needs the course "
                + "and provider, not an unsolicited explanation of government funding rules.";
        } else if ("research".equals(plan.getAction())) {
            direction = "This specific request may need current source evidence. Explain what is known and what "
                + "needs checking. The interface can offer an optional scoped lookup. No lookup has happened yet: "
                + "do not claim to have searched, confirmed fees, or verified a provider. Unknown publication "
                + "status is not proof that rates are not yet published. Do not supply specific subsidy bands, "
                + "funding eligibility, mandatory extra charges or provider requirements from memory, including in "
                + "option descriptions. Ask only a necessary fee-category question using plain labels without "
                + "asserting eligibility. Do not repeat a generic intake or tell the user to fill a second generic "
                + "form.";
        } else {
            direction = "Answer the actual question with useful depth. Do not add a generic research invitation or "
                + "ask what the user wants to know after they already told you. Ask a follow-up only if genuinely "
                + "necessary.";
        }
        return "TURN_NEEDS_GUIDANCE (server-owned, advisory; preserve main prompt and canonical JSON):\n" + direction
            + "\nThe classifier is fallible; prioritise the actual request and retained context. Similarity is not "
            + "evidence. Financial rules, fee figures, funding eligibility and repayment calculations require dated "
            + "relevant evidence; without it give a conditional conceptual explanation and identify the missing "
            + "source. Never quote internal routing labels. Research remains a separate explicit user action.";
    }

    public TurnNeeds researchOffer(TurnNeeds plan) {
        if (plan == null || !"turn-needs-v1".equals(plan.getVersion()) || !"research".equals(plan.getAction())
            || plan.getResearch() == null || plan.getScope().getTarget().isEmpty()) {
            return null;
        }
        return plan;
    }

    /** Add only a resolved user-stated course, not a transcript or inferred personal profile. */
    public String needsQuery(String text, List<HistoryTurn> history) {
        String current = text == null ? "" : text;
        TurnNeeds plan = assessTurnNeeds(current, history, false, null, null);
        String target = plan.getScope().getTarget();
        if (target.isEmpty() || current.contains(target)) return current;
        String query = "Current question: " + current + "\nUser-stated course: " + target;
        return query.length() <= 1800 ? query : current;
    }

    // ---------------------------------------------------------------------
    // acceptNeedHint -- port of turnNeeds.ts's acceptNeedHint()
    // ---------------------------------------------------------------------

    private NeedHint acceptNeedHint(NeedHint h) {
        if (h == null) return null;
        if ("abstained".equals(h.getStatus())) {
            Set<String> known = Set.of("incomplete-ranking", "uncertain", "cancelled", "input-length", "not-ready",
                "busy", "timeout", "unavailable", "inference-failed", "token-budget");
            return NeedHint.abstained(known.contains(h.getReason()) ? h.getReason() : "not-provided");
        }
        boolean bge = BgeGateService.BGE_MODEL_ID.equals(h.getModelId());
        BgeGateService.Gate gate = bge ? bgeGateService.needGate(h.getKind()) : new BgeGateService.Gate(0.5, 0.08, 0);
        if (h.getModelId() != null && (!bge || !BgeGateService.NEEDS_PROFILE_VERSION.equals(h.getProfileVersion()))) {
            return null;
        }
        if (!"selected".equals(h.getStatus()) || !NEED_KINDS.contains(h.getKind())
            || h.getScore() == null || !Double.isFinite(h.getScore()) || h.getScore() < gate.score || h.getScore() > 1
            || h.getMargin() == null || !Double.isFinite(h.getMargin()) || h.getMargin() < gate.margin || h.getMargin() > 2) {
            return null;
        }
        NeedHint accepted = new NeedHint();
        accepted.setStatus("selected");
        accepted.setKind(h.getKind());
        accepted.setScore(h.getScore());
        accepted.setMargin(h.getMargin());
        accepted.setReason("semantic-match");
        if (bge) {
            accepted.setModelId(BgeGateService.BGE_MODEL_ID);
            accepted.setProfileVersion(BgeGateService.NEEDS_PROFILE_VERSION);
        }
        return accepted;
    }
}
