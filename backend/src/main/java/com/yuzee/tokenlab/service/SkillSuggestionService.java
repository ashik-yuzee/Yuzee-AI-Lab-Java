package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.Candidate;
import com.yuzee.tokenlab.model.MicroTool;
import com.yuzee.tokenlab.model.RoutingDecision;
import com.yuzee.tokenlab.model.SkillOffer;
import com.yuzee.tokenlab.model.SkillReview;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.yuzee.tokenlab.service.RoutingPolicyService.abstain;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim;
import static com.yuzee.tokenlab.service.RoutingPolicyService.strictEquals;
import static com.yuzee.tokenlab.service.RoutingPolicyService.truthy;

/** Port of src/routing/skillSuggestions.ts and src/routing/skillContinuation.ts. */
@Service
public class SkillSuggestionService {

    private static final Map<String, String> LABELS = Map.of(
        "COURSE_011", "Understand my study costs",
        "COURSE_012", "Explore study and placement commitments",
        "CORE_010", "Check the supporting evidence",
        "CAREER_004", "Plan my next career step");

    private static final Pattern ALLOW_SKILL_REVIEW_BLOCK = jsRegex(
        "\\b(stop|pause|cancel|no (?:more |extra )?(?:questions|suggestions|follow.up)|do not suggest|don[’']t suggest|suicid\\w*|self.harm|emergency)\\b", true);
    private static final Pattern REVIEW_INTENT_BLOCK = jsRegex("SAFETY|SECURITY|CRITICAL_CLARIFICATION", false);
    private static final Pattern TOPIC_MENU_QUESTION = jsRegex(
        "(?:which (?:aspect|topic|area)|what would you like to (?:explore|focus|learn)|focus on next|explore next)", true);
    private static final Pattern SAFETY_SECURITY = jsRegex("SAFETY|SECURITY", false);
    private static final Pattern ANSWER_BOUNDARY = jsRegex(
        "\\b(stop|cancel|pause|instead|actually|change (?:topic|direction)|new (?:topic|question)|forget|ignore|suicid\\w*|self.harm|emergency)\\b", true);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RoutingPolicyService routingPolicyService;
    private final BgeGateService bgeGateService;
    private final ProtocolValidator protocolValidator;

    public SkillSuggestionService(RoutingPolicyService routingPolicyService, BgeGateService bgeGateService,
                                  ProtocolValidator protocolValidator) {
        this.routingPolicyService = routingPolicyService;
        this.bgeGateService = bgeGateService;
        this.protocolValidator = protocolValidator;
    }

    public static SkillReview noSkills(String reason) { return SkillReview.noSkills(reason); }

    public String skillMessage(String toolId) {
        MicroTool t = routingPolicyService.findEligibleTool(toolId);
        return t != null ? "Help me explore " + t.getName().toLowerCase(Locale.ROOT)
            + " in more detail, using our conversation so far. Explain what matters for my situation and what still needs checking." : "";
    }

    /** Each section must have a clear match. Different sections may support different skills. */
    public SkillReview selectSkillOffers(List<List<Candidate>> rankings, String modelId) {
        String model = modelId == null ? RoutingPolicyService.DEFAULT_ROUTER_MODEL : modelId;
        Map<String, SkillOffer> matches = new LinkedHashMap<>();
        for (List<Candidate> ranking : rankings) {
            RoutingDecision d = routingPolicyService.chooseRoute(ranking, model, "suggestion");
            List<Candidate> unique = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            ranking.stream()
                .filter(c -> routingPolicyService.isEligibleTool(c.getToolId()) && Double.isFinite(c.getScore()) && c.getScore() >= -1 && c.getScore() <= 1)
                .sorted(Comparator.comparingDouble(Candidate::getScore).reversed())
                .forEach(c -> { if (seen.add(c.getToolId())) unique.add(c); });
            // An optional menu can offer two strong related matches. Automatic topic routing still abstains.
            List<Candidate> choices = "selected".equals(d.getStatus()) ? List.of(new Candidate(d.getToolId(), d.getScore()))
                : !BgeGateService.BGE_MODEL_ID.equals(model) && unique.size() >= 3 && unique.get(1).getScore() >= .60
                    && unique.get(1).getScore() - unique.get(2).getScore() >= .10 ? unique.subList(0, 2) : List.of();
            for (Candidate c : choices) {
                MicroTool t = routingPolicyService.findEligibleTool(c.getToolId());
                if (matches.containsKey(t.getId()) && matches.get(t.getId()).getScore() >= c.getScore()) continue;
                matches.put(t.getId(), new SkillOffer(t.getId(), LABELS.getOrDefault(t.getId(), t.getName()), t.getPurpose(), c.getScore()));
            }
        }
        List<SkillOffer> offers = matches.values().stream()
            .sorted(Comparator.comparingDouble(SkillOffer::getScore).reversed())
            .limit(3)
            .collect(Collectors.toList());
        return !offers.isEmpty() ? new SkillReview("ready", offers, "minilm-response-review") : noSkills("no-clear-match");
    }

    /** The user selects an allowlisted skill, not arbitrary browser-supplied prompt text. */
    public RoutingDecision acceptSkillChoice(JsonNode c, List<JsonNode> messages, String text, boolean structured) {
        JsonNode last = messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (structured || !truthy(c) || !truthy(last) || !"assistant".equals(textOf(last.path("role"))) || truthy(last.path("error"))
            || truthy(last.path("streamStopped")) || isFalse(last.path("schemaValid")) || isFalse(last.path("semanticValid"))
            || !strictEquals(last.path("id"), c.path("sourceMessageId")))
            return abstain("stale-skill-offer");
        String toolId = textOf(c.path("toolId"));
        if (!routingPolicyService.isEligibleNode(c.path("toolId")) || !skillMessage(toolId).equals(text)) return abstain("invalid-skill-choice");
        RoutingDecision d = new RoutingDecision();
        d.setStatus("selected");
        d.setToolId(toolId);
        d.setReason("user-selected-skill");
        d.setVersion("minilm-response-review-v1");
        return d;
    }

    /** Split by measured tokens, keeping every character. Never silently truncate model input. */
    public List<String> embeddingSections(String text, Predicate<String> fits) {
        if (jsTrim(text).isEmpty()) return new ArrayList<>();
        if (fits.test(text)) return new ArrayList<>(List.of(text));
        if (text.length() < 2) throw new IllegalStateException("Unencodable section");
        int middle = text.length() / 2;
        int space = text.lastIndexOf(' ', middle);
        if (space > middle / 2.0) middle = space + 1;
        List<String> out = embeddingSections(text.substring(0, middle), fits);
        out.addAll(embeddingSections(text.substring(middle), fits));
        return out;
    }

    public boolean allowSkillReview(String userText) {
        return !ALLOW_SKILL_REVIEW_BLOCK.matcher(userText == null ? "" : userText).find();
    }

    /** Review completed answers, including counselling questions. Safety responses
     * and active service intake still take priority. */
    public boolean canReviewResponseSkills(JsonNode response, String userText) {
        if (!truthy(response) || !allowSkillReview(userText)) return false;
        String kind = textOf(response.path("interaction").path("kind"));
        if (!"none".equals(kind) && !"question".equals(kind)) return false;
        if ("S_SERVICE_HANDOFF".equals(textOf(response.path("current_mode")))) return false;
        if (truthy(response.path("service_trigger").path("trigger_now"))) return false;
        if (truthy(response.path("state").path("safety_override_applied"))) return false;
        if (REVIEW_INTENT_BLOCK.matcher(orEmpty(response.path("response_intent"))).find()) return false;
        for (JsonNode b : response.path("content_blocks")) {
            JsonNode text = b.path("text");
            if ((text.isTextual() && !jsTrim(text.asText()).isEmpty()) || jsLength(b.path("items")) > 0 || jsLength(b.path("rows")) > 0) return true;
        }
        return false;
    }

    /** Navigation menus offer tasks; intake/eligibility/preferences questions remain Quiz answers. */
    public boolean isTopicMenu(JsonNode interaction) {
        if (interaction == null) return false;
        return "question".equals(textOf(interaction.path("kind"))) && "single_select".equals(textOf(interaction.path("input_type")))
            && TOPIC_MENU_QUESTION.matcher(orEmpty(interaction.path("question"))).find()
            && interaction.path("options").isArray() && interaction.path("options").size() > 0;
    }

    public String selectedTopic(JsonNode interaction, JsonNode event) {
        if (!isTopicMenu(interaction)) return "";
        JsonNode nested = event == null ? null : event.path("userEvent").path("interaction");
        JsonNode selection = truthy(nested) ? nested : event == null ? null : event.path("interaction");
        JsonNode ids = selection == null ? null : selection.path("selected_option_ids");
        if (ids == null || jsLength(ids) != 1 || truthy(selection.path("self_input"))) return "";
        JsonNode first = ids.isArray() ? ids.get(0) : MAPPER.getNodeFactory().textNode(ids.asText().substring(0, 1));
        for (JsonNode option : interaction.path("options")) {
            if (strictEquals(option.path("id"), first)) {
                return joinTruthy(option.path("label"), option.path("description"));
            }
        }
        return "";
    }

    public RoutingDecision acceptTopicRoute(JsonNode value, JsonNode interaction, JsonNode event) {
        if (selectedTopic(interaction, event).isEmpty()) return abstain("not-topic-selection");
        RoutingDecision decision = routingPolicyService.validateRouteSelection(value, "topic");
        if (!"selected".equals(decision.getStatus())) return abstain("uncertain-topic");
        decision.setReason("user-selected-topic");
        decision.setVersion("minilm-topic-v2");
        return decision;
    }

    /** Keep each heading and its explanation together; user context is joined with the response topic. */
    public String suggestionText(JsonNode response, List<String> userMessages) {
        String topic = "";
        for (JsonNode b : response.path("content_blocks")) {
            if (truthy(b.path("title"))) { topic = b.path("title").asText(); break; }
        }
        List<String> sections = new ArrayList<>();
        if (!userMessages.isEmpty()) sections.add("Conversation context. " + String.join(". ", userMessages) + ". Current topic: " + topic);
        for (JsonNode b : response.path("content_blocks")) {
            String heading = truthy(b.path("title")) ? b.path("title").asText() : topic;
            List<String> items = new ArrayList<>();
            for (JsonNode i : b.path("items")) items.add(joinTruthy(i.path("title"), i.path("text"), i.path("value")));
            if (truthy(b.path("text"))) sections.add(joinTruthy(MAPPER.getNodeFactory().textNode(heading), b.path("text")));
            for (String item : items) sections.add(joinTruthy(MAPPER.getNodeFactory().textNode(heading), MAPPER.getNodeFactory().textNode(item)));
            if (!truthy(b.path("text")) && items.isEmpty() && !"heading".equals(textOf(b.path("type")))) sections.add(b.toString());
        }
        for (JsonNode a : response.path("interaction").path("recommended_actions"))
            sections.add(jsString(a.path("label")) + ". " + jsString(a.path("message")));
        return String.join("\n\n", sections);
    }

    // ---------------------------------------------------------------------
    // continueSkillQuestion -- port of skillContinuation.ts
    // ---------------------------------------------------------------------

    /** Carry only an allowlisted skill across an explicit answer to its current question.
     * Free-text conversation, stale questions and topic menus keep ordinary routing. */
    public RoutingDecision continueSkillQuestion(List<JsonNode> messages, JsonNode active, JsonNode event) {
        JsonNode last = messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (!truthy(event) || !truthy(last) || !"assistant".equals(textOf(last.path("role"))) || truthy(last.path("error"))
            || truthy(last.path("streamStopped")) || isFalse(last.path("schemaValid")) || isFalse(last.path("semanticValid"))
            || isFalse(last.path("telemetry").path("validation").path("protocolAccepted")))
            return abstain("no-skill-question");
        JsonNode response = last.path("structuredResponse");
        JsonNode activeQuestionId = active == null ? null : active.path("question_id");
        if (!truthy(response) || active == null || !"question".equals(textOf(active.path("kind"))) || isTopicMenu(active)
            || !strictEquals(response.path("interaction").path("question_id"), activeQuestionId) || !truthy(activeQuestionId))
            return abstain("no-skill-question");
        if (truthy(response.path("state").path("safety_override_applied")) || truthy(response.path("service_trigger").path("trigger_now"))
            || "S_SERVICE_HANDOFF".equals(textOf(response.path("current_mode"))) || SAFETY_SECURITY.matcher(orEmpty(response.path("response_intent"))).find())
            return abstain("question-boundary");
        JsonNode answer = truthy(event.path("interaction")) ? event.path("interaction")
            : truthy(event.path("userEvent").path("interaction")) ? event.path("userEvent").path("interaction")
            : truthy(event.path("type")) ? mappedAnswer(event) : null;
        if (!strictEquals(answer == null ? null : answer.path("question_id"), activeQuestionId) || truthy(answer.path("action_id"))
            || !protocolValidator.validateUserEventAgainstActiveInteraction(event, active).valid)
            return abstain("not-current-answer");
        JsonNode selfInput = answer.path("self_input");
        boolean hasAnswer = (selfInput.isTextual() && !jsTrim(selfInput.asText()).isEmpty())
            || jsLength(answer.path("selected_option_ids")) > 0 || jsLength(answer.path("ranked_option_ids")) > 0
            || keyCount(answer.path("fields")) > 0;
        if (!hasAnswer) return abstain("empty-answer");
        // Free-entry corrections and new directions belong to the counsellor, not the old skill.
        if (ANSWER_BOUNDARY.matcher(truthy(selfInput) ? selfInput.asText() : "").find()) return abstain("answer-boundary");
        JsonNode previous = last.path("telemetry").path("routing");
        if (!"selected".equals(textOf(previous.path("status"))) || !routingPolicyService.isEligibleNode(previous.path("toolId")))
            return abstain("no-skill-owner");
        RoutingDecision d = new RoutingDecision();
        d.setStatus("selected");
        d.setToolId(previous.path("toolId").asText());
        d.setReason("skill-question-continuation");
        d.setVersion("skill-input-v1");
        return d;
    }

    /** {question_id:event.interaction_id, action_id, self_input:event.value||event.self_input, selected_option_ids, ranked_option_ids, fields}. */
    private static JsonNode mappedAnswer(JsonNode event) {
        ObjectNode a = MAPPER.createObjectNode();
        putDefined(a, "question_id", event.path("interaction_id"));
        putDefined(a, "action_id", event.path("action_id"));
        putDefined(a, "self_input", truthy(event.path("value")) ? event.path("value") : event.path("self_input"));
        putDefined(a, "selected_option_ids", truthy(event.path("option_id"))
            ? MAPPER.createArrayNode().add(event.path("option_id")) : event.path("selected_option_ids"));
        putDefined(a, "ranked_option_ids", truthy(event.path("ranked_ids")) ? event.path("ranked_ids") : event.path("ranked_option_ids"));
        putDefined(a, "fields", event.path("fields"));
        return a;
    }

    // ---------------------------------------------------------------------
    // small JS-semantics helpers
    // ---------------------------------------------------------------------

    private static void putDefined(ObjectNode target, String key, JsonNode value) {
        if (!value.isMissingNode()) target.set(key, value);
    }

    private static boolean isFalse(JsonNode node) { return node.isBoolean() && !node.asBoolean(); }

    /** String value for === comparisons against string literals (non-strings never match). */
    private static String textOf(JsonNode node) { return node != null && node.isTextual() ? node.asText() : null; }

    /** value||'' coerced to a string for RegExp.test(). */
    private static String orEmpty(JsonNode node) { return truthy(node) ? node.asText() : ""; }

    /** value?.length for arrays and strings (0 otherwise). */
    private static int jsLength(JsonNode node) {
        return node.isArray() ? node.size() : node.isTextual() ? node.asText().length() : 0;
    }

    /** Object.keys(value||{}).length. */
    private static int keyCount(JsonNode node) {
        return node.isObject() || node.isArray() ? node.size() : node.isTextual() ? node.asText().length() : 0;
    }

    /** [..].filter(Boolean).join('. '). */
    private static String joinTruthy(JsonNode... parts) {
        List<String> present = new ArrayList<>();
        for (JsonNode p : parts) if (truthy(p)) present.add(jsString(p));
        return String.join(". ", present);
    }

    /** Template-literal string conversion of a JSON value. */
    private static String jsString(JsonNode node) {
        if (node == null || node.isMissingNode()) return "undefined";
        if (node.isNull()) return "null";
        if (node.isNumber()) {
            double v = node.asDouble();
            return v == Math.rint(v) && Math.abs(v) < 1e21 ? Long.toString((long) v) : Double.toString(v);
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode n : node) parts.add(n.isNull() ? "" : jsString(n));
            return String.join(",", parts);
        }
        if (node.isObject()) return "[object Object]";
        return node.asText();
    }
}
