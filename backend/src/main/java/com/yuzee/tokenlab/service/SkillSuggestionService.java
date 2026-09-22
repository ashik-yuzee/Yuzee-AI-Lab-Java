package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzee.tokenlab.model.Candidate;
import com.yuzee.tokenlab.model.MicroTool;
import com.yuzee.tokenlab.model.RouteClaimRequest;
import com.yuzee.tokenlab.model.RoutingDecision;
import com.yuzee.tokenlab.model.SkillChoice;
import com.yuzee.tokenlab.model.SkillOffer;
import com.yuzee.tokenlab.model.SkillReview;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Port of skillSuggestions.ts (post-response skill offers + topic-menu detection) folded
 * together with skillContinuation.ts's continueSkillQuestion(), exactly as the task groups them.
 *
 * ponytail: selectSkillOffers() ports the legacy MiniLM score/margin threshold (policy.ts's
 * MIN_SCORE/MIN_MARGIN, or BgeGateService's "suggestion" gate for the BGE model) faithfully, but
 * drops the BGE out-of-scope domain-margin ranking from bgeMatching.ts/bgeDomain.ts -- that is
 * embedding-index machinery the task explicitly scoped out (suggestionIndex.ts's "you don't need
 * the embedding-index part"). Candidates passed in are assumed already restricted to eligible
 * tool ids. Revisit if BGE-mode suggestions need the full domain-margin gate too.
 */
@Service
public class SkillSuggestionService {

    private static final String SKILL_REVIEW_VERSION_SELECTED = "minilm-response-review-v1";
    private static final String TOPIC_VERSION_SELECTED = "minilm-topic-v2";
    private static final String CONTINUATION_VERSION_SELECTED = "skill-input-v1";

    private static final Map<String, String> LABELS = Map.of(
        "COURSE_011", "Understand my study costs",
        "COURSE_012", "Explore study and placement commitments",
        "CORE_010", "Check the supporting evidence",
        "CAREER_004", "Plan my next career step"
    );

    private static final Pattern ALLOW_SKILL_REVIEW_BLOCK = Pattern.compile(
        "\\b(stop|pause|cancel|no (?:more |extra )?(?:questions|suggestions|follow.up)|do not suggest|"
            + "don['’]t suggest|suicid\\w*|self.harm|emergency)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPIC_MENU_QUESTION = Pattern.compile(
        "(?:which (?:aspect|topic|area)|what would you like to (?:explore|focus|learn)|focus on next|explore next)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFETY_SECURITY = Pattern.compile("SAFETY|SECURITY");
    private static final Pattern ANSWER_BOUNDARY = Pattern.compile(
        "\\b(stop|cancel|pause|instead|actually|change (?:topic|direction)|new (?:topic|question)|forget|ignore|"
            + "suicid\\w*|self.harm|emergency)\\b", Pattern.CASE_INSENSITIVE);

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

    private String skillMessage(String toolId) {
        MicroTool t = routingPolicyService.findEligibleTool(toolId);
        return t == null ? "" : "Help me explore " + t.getName().toLowerCase()
            + " in more detail, using our conversation so far. Explain what matters for my situation and what still needs checking.";
    }

    private List<Candidate> uniqueEligibleSorted(List<Candidate> ranking) {
        if (ranking == null) return List.of();
        List<Candidate> filtered = ranking.stream()
            .filter(c -> c != null && routingPolicyService.isEligibleTool(c.getToolId())
                && Double.isFinite(c.getScore()) && c.getScore() >= -1 && c.getScore() <= 1)
            .sorted(Comparator.comparingDouble(Candidate::getScore).reversed())
            .collect(Collectors.toList());
        List<Candidate> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : filtered) if (seen.add(c.getToolId())) unique.add(c);
        return unique;
    }

    /** Each section must have a clear match. Different sections may support different skills. */
    public SkillReview selectSkillOffers(List<List<Candidate>> rankings, String modelId) {
        Map<String, SkillOffer> matches = new LinkedHashMap<>();
        boolean isBge = BgeGateService.BGE_MODEL_ID.equals(modelId);
        for (List<Candidate> ranking : rankings == null ? List.<List<Candidate>>of() : rankings) {
            List<Candidate> unique = uniqueEligibleSorted(ranking);
            List<Candidate> choices = new ArrayList<>();
            if (unique.size() >= 2) {
                Candidate first = unique.get(0), second = unique.get(1);
                double margin = first.getScore() - second.getScore();
                BgeGateService.Gate gate = isBge ? bgeGateService.gate("suggestion")
                    : new BgeGateService.Gate(RoutingPolicyService.MIN_SCORE, RoutingPolicyService.MIN_MARGIN, 0);
                if (first.getScore() >= gate.score && margin >= gate.margin) {
                    choices.add(first);
                } else if (!isBge && unique.size() >= 3
                    && unique.get(1).getScore() >= 0.60 && unique.get(1).getScore() - unique.get(2).getScore() >= 0.10) {
                    // An optional menu can offer two strong related matches. Automatic topic routing still abstains.
                    choices.add(unique.get(0));
                    choices.add(unique.get(1));
                }
            }
            for (Candidate c : choices) {
                MicroTool tool = routingPolicyService.findEligibleTool(c.getToolId());
                if (tool == null) continue;
                SkillOffer existing = matches.get(tool.getId());
                if (existing != null && existing.getScore() >= c.getScore()) continue;
                matches.put(tool.getId(), new SkillOffer(tool.getId(), LABELS.getOrDefault(tool.getId(), tool.getName()),
                    tool.getPurpose(), c.getScore()));
            }
        }
        List<SkillOffer> offers = matches.values().stream()
            .sorted(Comparator.comparingDouble(SkillOffer::getScore).reversed())
            .limit(3)
            .collect(Collectors.toList());
        return offers.isEmpty() ? SkillReview.noSkills("no-clear-match") : new SkillReview("ready", offers, "minilm-response-review");
    }

    /** The user selects an allowlisted skill, not arbitrary browser-supplied prompt text. */
    public RoutingDecision acceptSkillChoice(SkillChoice choice, List<JsonNode> messages, String text, boolean structured) {
        JsonNode last = (messages == null || messages.isEmpty()) ? null : messages.get(messages.size() - 1);
        if (structured || choice == null || last == null || !"assistant".equals(last.path("role").asText())
            || truthy(last.path("error")) || last.path("streamStopped").asBoolean(false)
            || isExplicitFalse(last.path("schemaValid")) || isExplicitFalse(last.path("semanticValid"))
            || !Objects.equals(textOrNull(last.path("id")), choice.getSourceMessageId())) {
            return RoutingDecision.abstain("stale-skill-offer", RoutingPolicyService.ROUTER_VERSION);
        }
        if (!routingPolicyService.isEligibleTool(choice.getToolId()) || !Objects.equals(text, skillMessage(choice.getToolId()))) {
            return RoutingDecision.abstain("invalid-skill-choice", RoutingPolicyService.ROUTER_VERSION);
        }
        RoutingDecision d = new RoutingDecision();
        d.setStatus("selected");
        d.setToolId(choice.getToolId());
        d.setReason("user-selected-skill");
        d.setVersion(SKILL_REVIEW_VERSION_SELECTED);
        return d;
    }

    /** Split by measured tokens, keeping every character. Never silently truncate model input. */
    public List<String> embeddingSections(String text, java.util.function.Predicate<String> fits) {
        List<String> out = new ArrayList<>();
        splitToFit(text, fits, out);
        return out;
    }

    private void splitToFit(String text, java.util.function.Predicate<String> fits, List<String> out) {
        if (text == null || text.trim().isEmpty()) return;
        if (fits.test(text)) { out.add(text); return; }
        if (text.length() < 2) throw new IllegalStateException("Unencodable section");
        int middle = text.length() / 2;
        int space = text.lastIndexOf(' ', middle);
        if (space > middle / 2) middle = space + 1;
        splitToFit(text.substring(0, middle), fits, out);
        splitToFit(text.substring(middle), fits, out);
    }

    public boolean allowSkillReview(String userText) {
        return !ALLOW_SKILL_REVIEW_BLOCK.matcher(userText == null ? "" : userText).find();
    }

    /**
     * Review completed answers, including counselling questions. Safety responses and active
     * service intake still take priority.
     */
    public boolean canReviewResponseSkills(JsonNode response, String userText) {
        if (response == null || response.isNull() || response.isMissingNode()) return false;
        if (!allowSkillReview(userText)) return false;
        String kind = response.path("interaction").path("kind").asText("");
        if (!("none".equals(kind) || "question".equals(kind))) return false;
        if ("S_SERVICE_HANDOFF".equals(response.path("current_mode").asText())) return false;
        if (response.path("service_trigger").path("trigger_now").asBoolean(false)) return false;
        if (response.path("state").path("safety_override_applied").asBoolean(false)) return false;
        if (SAFETY_SECURITY.matcher(response.path("response_intent").asText("")).find()) return false;
        for (JsonNode block : response.path("content_blocks")) {
            String blockText = block.path("text").asText("");
            if (!blockText.trim().isEmpty() || block.path("items").size() > 0 || block.path("rows").size() > 0) return true;
        }
        return false;
    }

    /** Navigation menus offer tasks; intake/eligibility/preferences questions remain Quiz answers. */
    public boolean isTopicMenu(JsonNode interaction) {
        if (interaction == null || interaction.isNull() || interaction.isMissingNode()) return false;
        if (!"question".equals(interaction.path("kind").asText())) return false;
        if (!"single_select".equals(interaction.path("input_type").asText())) return false;
        if (!TOPIC_MENU_QUESTION.matcher(interaction.path("question").asText("")).find()) return false;
        JsonNode options = interaction.path("options");
        return options.isArray() && options.size() > 0;
    }

    public String selectedTopic(JsonNode interaction, JsonNode event) {
        if (!isTopicMenu(interaction)) return "";
        JsonNode selection = event.path("userEvent").path("interaction");
        if (selection.isMissingNode() || selection.isNull()) selection = event.path("interaction");
        JsonNode selectedIds = selection.path("selected_option_ids");
        if (!selectedIds.isArray() || selectedIds.size() != 1) return "";
        if (truthy(selection.path("self_input"))) return "";
        String selectedId = selectedIds.get(0).asText();
        for (JsonNode option : interaction.path("options")) {
            if (selectedId.equals(option.path("id").asText())) {
                String label = option.path("label").asText("");
                String description = option.path("description").asText("");
                return joinNonBlank(label, description);
            }
        }
        return "";
    }

    public RoutingDecision acceptTopicRoute(RouteClaimRequest value, JsonNode interaction, JsonNode event) {
        if (selectedTopic(interaction, event).isEmpty()) {
            return RoutingDecision.abstain("not-topic-selection", RoutingPolicyService.ROUTER_VERSION);
        }
        RoutingDecision decision = routingPolicyService.validateRouteSelection(value, "topic");
        if (!"selected".equals(decision.getStatus())) {
            return RoutingDecision.abstain("uncertain-topic", RoutingPolicyService.ROUTER_VERSION);
        }
        decision.setReason("user-selected-topic");
        decision.setVersion(TOPIC_VERSION_SELECTED);
        return decision;
    }

    /** Keep each heading and its explanation together; user context is joined with the response topic. */
    public String suggestionText(JsonNode response, List<String> userMessages) {
        String topic = "";
        for (JsonNode block : response.path("content_blocks")) {
            if (!block.path("title").asText("").isEmpty()) { topic = block.path("title").asText(); break; }
        }
        List<String> sections = new ArrayList<>();
        if (userMessages != null && !userMessages.isEmpty()) {
            sections.add("Conversation context. " + String.join(". ", userMessages) + ". Current topic: " + topic);
        }
        for (JsonNode block : response.path("content_blocks")) {
            String heading = !block.path("title").asText("").isEmpty() ? block.path("title").asText() : topic;
            List<String> items = new ArrayList<>();
            for (JsonNode item : block.path("items")) {
                items.add(joinNonBlank(item.path("title").asText(""), item.path("text").asText(""), item.path("value").asText("")));
            }
            String blockText = block.path("text").asText("");
            if (!blockText.isEmpty()) sections.add(joinNonBlank(heading, blockText));
            for (String item : items) sections.add(joinNonBlank(heading, item));
            if (blockText.isEmpty() && items.isEmpty() && !"heading".equals(block.path("type").asText())) {
                sections.add(block.toString());
            }
        }
        for (JsonNode action : response.path("interaction").path("recommended_actions")) {
            sections.add(action.path("label").asText("") + ". " + action.path("message").asText(""));
        }
        return String.join("\n\n", sections);
    }

    // ---------------------------------------------------------------------
    // continueSkillQuestion -- port of skillContinuation.ts
    // ---------------------------------------------------------------------

    private static final class Answer {
        String questionId;
        String actionId;
        String selfInput;
        List<String> selectedOptionIds;
        List<String> rankedOptionIds;
        JsonNode fields;
    }

    private static Answer extractAnswer(JsonNode event) {
        JsonNode interaction = event.path("interaction");
        if (interaction.isMissingNode() || interaction.isNull()) {
            interaction = event.path("userEvent").path("interaction");
        }
        if (!interaction.isMissingNode() && !interaction.isNull()) {
            Answer a = new Answer();
            a.questionId = textOrNull(interaction.path("question_id"));
            a.actionId = textOrNull(interaction.path("action_id"));
            a.selfInput = textOrNull(interaction.path("self_input"));
            a.selectedOptionIds = toStringList(interaction.path("selected_option_ids"));
            a.rankedOptionIds = toStringList(interaction.path("ranked_option_ids"));
            a.fields = interaction.path("fields");
            return a;
        }
        if (truthy(event.path("type"))) {
            Answer a = new Answer();
            a.questionId = textOrNull(event.path("interaction_id"));
            a.actionId = textOrNull(event.path("action_id"));
            a.selfInput = firstNonEmpty(textOrNull(event.path("value")), textOrNull(event.path("self_input")));
            JsonNode optionId = event.path("option_id");
            if (!optionId.isMissingNode() && !optionId.isNull()) {
                a.selectedOptionIds = List.of(optionId.asText());
            } else {
                a.selectedOptionIds = toStringList(event.path("selected_option_ids"));
            }
            List<String> rankedIds = toStringList(event.path("ranked_ids"));
            a.rankedOptionIds = rankedIds != null ? rankedIds : toStringList(event.path("ranked_option_ids"));
            a.fields = event.path("fields");
            return a;
        }
        return null;
    }

    /** Carry only an allowlisted skill across an explicit answer to its current question.
     * Free-text conversation, stale questions and topic menus keep ordinary routing. */
    public RoutingDecision continueSkillQuestion(List<JsonNode> messages, JsonNode active, JsonNode event) {
        JsonNode last = (messages == null || messages.isEmpty()) ? null : messages.get(messages.size() - 1);
        if (event == null || event.isNull() || last == null || !"assistant".equals(last.path("role").asText())
            || truthy(last.path("error")) || last.path("streamStopped").asBoolean(false)
            || isExplicitFalse(last.path("schemaValid")) || isExplicitFalse(last.path("semanticValid"))
            || isExplicitFalse(last.path("telemetry").path("validation").path("protocolAccepted"))) {
            return RoutingDecision.abstain("no-skill-question", RoutingPolicyService.ROUTER_VERSION);
        }
        JsonNode response = last.path("structuredResponse");
        String activeQuestionId = active == null ? null : textOrNull(active.path("question_id"));
        if (response.isMissingNode() || response.isNull()
            || active == null || !"question".equals(active.path("kind").asText())
            || isTopicMenu(active)
            || !Objects.equals(textOrNull(response.path("interaction").path("question_id")), activeQuestionId)
            || activeQuestionId == null || activeQuestionId.isEmpty()) {
            return RoutingDecision.abstain("no-skill-question", RoutingPolicyService.ROUTER_VERSION);
        }
        boolean safetyOverride = response.path("state").path("safety_override_applied").asBoolean(false);
        boolean serviceTrigger = response.path("service_trigger").path("trigger_now").asBoolean(false);
        boolean handoff = "S_SERVICE_HANDOFF".equals(response.path("current_mode").asText());
        if (safetyOverride || serviceTrigger || handoff || SAFETY_SECURITY.matcher(response.path("response_intent").asText("")).find()) {
            return RoutingDecision.abstain("question-boundary", RoutingPolicyService.ROUTER_VERSION);
        }
        Answer answer = extractAnswer(event);
        ProtocolValidator.UserEventValidationResult validation = protocolValidator.validateUserEventAgainstActiveInteraction(event, active);
        if (answer == null || !Objects.equals(answer.questionId, activeQuestionId) || answer.actionId != null || !validation.valid) {
            return RoutingDecision.abstain("not-current-answer", RoutingPolicyService.ROUTER_VERSION);
        }
        boolean hasAnswer = (answer.selfInput != null && !answer.selfInput.trim().isEmpty())
            || (answer.selectedOptionIds != null && !answer.selectedOptionIds.isEmpty())
            || (answer.rankedOptionIds != null && !answer.rankedOptionIds.isEmpty())
            || (answer.fields != null && answer.fields.isObject() && answer.fields.size() > 0);
        if (!hasAnswer) return RoutingDecision.abstain("empty-answer", RoutingPolicyService.ROUTER_VERSION);
        // Free-entry corrections and new directions belong to the counsellor, not the old skill.
        if (ANSWER_BOUNDARY.matcher(answer.selfInput == null ? "" : answer.selfInput).find()) {
            return RoutingDecision.abstain("answer-boundary", RoutingPolicyService.ROUTER_VERSION);
        }
        JsonNode previous = last.path("telemetry").path("routing");
        String previousToolId = textOrNull(previous.path("toolId"));
        if (!"selected".equals(previous.path("status").asText()) || !routingPolicyService.isEligibleTool(previousToolId)) {
            return RoutingDecision.abstain("no-skill-owner", RoutingPolicyService.ROUTER_VERSION);
        }
        RoutingDecision d = new RoutingDecision();
        d.setStatus("selected");
        d.setToolId(previousToolId);
        d.setReason("skill-question-continuation");
        d.setVersion(CONTINUATION_VERSION_SELECTED);
        return d;
    }

    // ---------------------------------------------------------------------
    // small JsonNode helpers
    // ---------------------------------------------------------------------

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return !node.asText().isEmpty();
        if (node.isNumber()) return node.asDouble() != 0;
        return true;
    }

    private static boolean isExplicitFalse(JsonNode node) {
        return node != null && node.isBoolean() && !node.asBoolean();
    }

    private static String textOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText();
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static List<String> toStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode n : arrayNode) out.add(n.asText());
        return out;
    }

    private static String joinNonBlank(String... parts) {
        List<String> present = new ArrayList<>();
        for (String p : parts) if (p != null && !p.trim().isEmpty()) present.add(p);
        return String.join(". ", present);
    }
}
