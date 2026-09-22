package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Java port of miniPathway/service.ts's {@code MiniPathwayService}. Generates an AI career-route
 * mini pathway report: streams Gemini output through {@link PathwayBlockStreamParser} so the
 * caller can render each content block as it completes, validates the finished report against the
 * base Yuzee Response Protocol schema ({@link ProtocolValidator}) plus this feature's own
 * B_DELIVERY/no-interaction invariants and {@link #reviewMiniPathwayReport structural completeness
 * checks} (ported from reportReview.ts), and retries once -- regenerating with the concrete
 * validation issues fed back to Gemini -- via {@link ReviewRetryService}.
 *
 * // ponytail: the old app's stream/isCurrent cancellation plumbing (AbortSignal, isCurrent(),
 * // an in-memory "one active run per conversation" guard, and a persisted MiniPathwayRun history
 * // record) is not reproduced here -- this port's entry point is a single synchronous generate()
 * // call with no cancellation surface. Add a cancellation token if the SSE endpoint that wires
 * // this in needs to stop a run early; Conversation.miniPathways already exists for whatever
 * // history record the wiring engineer chooses to persist.
 */
@Service
public class MiniPathwayService {

    private static final int MAX_OUTPUT_TOKENS = 24000;
    private static final Pattern MONEY_PATTERN = Pattern.compile(
        "[$£€]\\s*\\d|\\b(?:AUD|USD|GBP|EUR)\\s*\\d|\\b\\d[\\d,.]*\\s*(?:dollars|pounds|euros)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern GUARANTEE_PATTERN = Pattern.compile(
        "\\b(?:zero tuition|no tuition|zero out.of.pocket|full wage|guaranteed (?:job|employment|placement))\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> TIMELINE_OR_STEPS = Set.of("table", "steps");
    private static final Set<String> TABLE_OR_COMPARISON = Set.of("table", "comparison");

    private final GeminiService geminiService;
    private final ProtocolValidator protocolValidator;
    private final ReviewRetryService reviewRetryService;
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectMapper mapper = new ObjectMapper();

    private String systemInstruction;

    public MiniPathwayService(GeminiService geminiService, ProtocolValidator protocolValidator,
                               ReviewRetryService reviewRetryService, ConversationMemoryService conversationMemoryService) {
        this.geminiService = geminiService;
        this.protocolValidator = protocolValidator;
        this.reviewRetryService = reviewRetryService;
        this.conversationMemoryService = conversationMemoryService;
    }

    @PostConstruct
    void init() {
        String prompt = readClasspathText("prompts/mini-pathway-prompt.md");
        String overrideTemplate = readClasspathText("prompts/mini-pathway-override.md");
        String schemaJson = readClasspathText("prompts/response-schema-v1.3.json");
        String override = overrideTemplate.replace("__CANONICAL_SCHEMA_JSON__", schemaJson);
        this.systemInstruction = prompt + "\n\n" + override;
    }

    private static String readClasspathText(String location) {
        try (InputStream is = new ClassPathResource(location).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load classpath resource: " + location, e);
        }
    }

    /** Result of {@link #generate}: the accepted report, plus any (empty-on-success) issues noted along the way. */
    public static class PathwayGenerationResult {
        public JsonNode report;
        public List<String> validationErrors = List.of();
    }

    /**
     * Generates a mini pathway report for {@code goalOrPrompt}, streaming each validated content
     * block to {@code onBlock} as it completes. Retries once, feeding the concrete validation
     * issues back to Gemini, before failing with {@link PathwayGenerationException}.
     *
     * @param conv        the conversation providing recent history for context (may be null/empty).
     * @param goalOrPrompt the learner's career goal or question driving the report; required.
     * @param modelId     the Gemini model id to use; falls back to {@link GeminiModelRegistry#DEFAULT_MODEL_ID}.
     * @param onBlock     called with each validated content block as it streams in; may be null.
     */
    public PathwayGenerationResult generate(Conversation conv, String goalOrPrompt, String modelId,
                                             Consumer<JsonNode> onBlock) {
        if (goalOrPrompt == null || goalOrPrompt.isBlank()) {
            throw new IllegalArgumentException("A career goal or question is needed to generate a mini pathway.");
        }
        String model = (modelId == null || modelId.isBlank()) ? GeminiModelRegistry.DEFAULT_MODEL_ID : modelId;
        Consumer<JsonNode> safeOnBlock = onBlock != null ? onBlock : b -> { };

        ObjectNode taskPayload = mapper.createObjectNode();
        taskPayload.put("task", "Create a mini pathway report to support this learner's career goal. "
            + "Focus on realistic routes and practical next steps, not a single definitive choice.");
        taskPayload.put("goal", goalOrPrompt);
        taskPayload.set("conversation", recentHistory(conv));

        AtomicReference<List<String>> issuesRef = new AtomicReference<>(List.of());
        try {
            JsonNode report = reviewRetryService.runReview(() -> attemptOnce(model, taskPayload, issuesRef, safeOnBlock));
            PathwayGenerationResult result = new PathwayGenerationResult();
            result.report = report;
            result.validationErrors = List.of();
            return result;
        } catch (ReviewFailure failure) {
            List<ReviewFailureCode> codes = failure.getFailures();
            ReviewFailureCode last = codes.isEmpty() ? ReviewFailureCode.UNKNOWN : codes.get(codes.size() - 1);
            throw new PathwayGenerationException(ReviewRetryService.reviewFailureMessage(last), failure);
        }
    }

    // -----------------------------------------------------------------
    // One generate-and-validate attempt (called once, or twice via ReviewRetryService)
    // -----------------------------------------------------------------

    private JsonNode attemptOnce(String model, JsonNode taskPayload, AtomicReference<List<String>> issuesRef,
                                  Consumer<JsonNode> onBlock) throws Exception {
        List<String> priorIssues = issuesRef.get();
        ArrayNode contents = priorIssues.isEmpty() ? buildContents(taskPayload) : buildRetryContents(taskPayload, priorIssues);

        ObjectNode extras = mapper.createObjectNode();
        extras.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        extras.put("responseMimeType", "application/json");

        PathwayBlockStreamParser parser = new PathwayBlockStreamParser();
        StringBuilder fullText = new StringBuilder();
        List<Exception> errors = new ArrayList<>();
        GeminiService.StreamResult[] doneHolder = new GeminiService.StreamResult[1];

        geminiService.streamGenerateRich(model, systemInstruction, contents, extras, null,
            chunk -> {
                fullText.append(chunk);
                for (JsonNode block : parser.push(chunk)) onBlock.accept(block);
            },
            r -> doneHolder[0] = r,
            errors::add);

        if (!errors.isEmpty()) {
            throw errors.get(0);
        }
        GeminiService.StreamResult result = doneHolder[0];
        if (result == null || !"STOP".equals(result.finishReason)) {
            // Not fed back as a review issue -- an incomplete stream is not something a corrective
            // reprompt fixes, so this fails immediately with no retry (matches service.ts).
            throw new IllegalStateException("The mini pathway was incomplete. Please try again.");
        }

        String rawText = fullText.toString();
        JsonNode parsed = mapper.readTree(rawText); // JsonProcessingException here is retryable (INVALID_RESPONSE)

        ProtocolValidator.ValidationResult validation = protocolValidator.validateProtocolResponse(rawText, "1.3");
        List<String> issues = new ArrayList<>();
        if (!validation.isValid()) {
            issues.addAll(validation.errors);
        } else {
            issues.addAll(validateMiniPathwayInvariants(parsed));
            issues.addAll(reviewMiniPathwayReport(parsed));
        }
        if (!issues.isEmpty()) {
            issuesRef.set(issues);
            throw new IllegalStateException("Incomplete pathway review");
        }
        issuesRef.set(List.of());
        return parsed;
    }

    private ArrayNode buildContents(JsonNode payload) {
        ArrayNode contents = mapper.createArrayNode();
        ObjectNode turn = mapper.createObjectNode();
        turn.put("role", "user");
        ArrayNode parts = mapper.createArrayNode();
        parts.add(mapper.createObjectNode().put("text", payload.toString()));
        turn.set("parts", parts);
        contents.add(turn);
        return contents;
    }

    private ArrayNode buildRetryContents(JsonNode originalPayload, List<String> issues) {
        ObjectNode retry = mapper.createObjectNode();
        retry.set("original_request", originalPayload);
        ArrayNode issuesArr = retry.putArray("validation_issues");
        for (String issue : issues) issuesArr.add(issue);
        retry.put("task", "Regenerate the complete report, correcting every validation issue. "
            + "Preserve the full report depth and canonical schema. Do not include another question or service action.");
        return buildContents(retry);
    }

    /** Last 16 user/assistant turns, assistant content compacted the same way chat history is. */
    private ArrayNode recentHistory(Conversation conv) {
        ArrayNode arr = mapper.createArrayNode();
        if (conv == null || conv.getMessages() == null || conv.getMessages().isEmpty()) return arr;
        List<ChatMessage> messages = conv.getMessages();
        int from = Math.max(0, messages.size() - 16);
        for (ChatMessage m : messages.subList(from, messages.size())) {
            String role = m.getRole();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = "assistant".equals(role)
                ? conversationMemoryService.formatAssistantMessageForContext(m.getContent())
                : String.valueOf(m.getContent());
            ObjectNode entry = mapper.createObjectNode();
            entry.put("role", role);
            entry.put("content", content);
            arr.add(entry);
        }
        return arr;
    }

    // -----------------------------------------------------------------
    // validateMiniPathwayOutput's extra invariants beyond base protocol validity
    // (ported from service.ts#validateMiniPathwayOutput, minus outdatedHelpClaim() -- that check
    // lives in HelpEvidence.ts, which is out of scope for this port).
    // -----------------------------------------------------------------

    static List<String> validateMiniPathwayInvariants(JsonNode response) {
        List<String> issues = new ArrayList<>();
        boolean boundaryViolation =
            !"B_DELIVERY".equals(response.path("current_mode").asText(""))
                || !"none".equals(response.path("interaction").path("kind").asText(""))
                || response.path("interaction").path("recommended_actions").size() > 0
                || response.path("service_trigger").path("trigger_now").asBoolean(false)
                || response.path("service_trigger").path("actions").size() > 0
                || response.path("followups").path("enabled").asBoolean(false)
                || response.path("followups").path("triggers").size() > 0
                || response.path("rmo_readiness").path("ready_to_generate").asBoolean(false);
        if (boundaryViolation) {
            issues.add("The mini pathway must return current_mode=B_DELIVERY with interaction.kind=none and no "
                + "active question, recommended actions, service trigger, followups or handoff-readiness flag.");
        }

        boolean hasVisibleContent = false;
        for (JsonNode block : response.path("content_blocks")) {
            if (!block.path("text").asText("").isEmpty() || block.path("items").size() > 0 || block.path("rows").size() > 0) {
                hasVisibleContent = true;
                break;
            }
        }
        if (!hasVisibleContent) {
            issues.add("The mini pathway needs another check: no content block carries visible text, items or rows.");
        }
        return issues;
    }

    // -----------------------------------------------------------------
    // Port of reportReview.ts#reviewMiniPathwayReport -- structural completeness + content checks.
    // Package-private (not private) so the sanity test can exercise it directly.
    // -----------------------------------------------------------------

    static List<String> reviewMiniPathwayReport(JsonNode response) {
        JsonNode blocks = response.path("content_blocks");
        List<String> issues = new ArrayList<>();
        String allBlocksText = blocks.toString();

        if (MONEY_PATTERN.matcher(allBlocksText).find()) {
            issues.add("Remove unsourced monetary amounts. Keep cost factors and clearly state what must be "
                + "checked. No fee evidence was fetched.");
        }
        if (GUARANTEE_PATTERN.matcher(allBlocksText).find()) {
            issues.add("Remove unverified free-tuition, wage or employment guarantees; describe conditional "
                + "arrangements and checks instead.");
        }

        JsonNode overview = findBlockById(blocks, "overview");
        if (overview == null || overview.path("text").asText("").trim().isEmpty()) {
            issues.add("Include overview: a plain-language orientation to the goal and known constraints.");
        }

        JsonNode routeSummary = findBlockByIdAndType(blocks, "route-summary", Set.of("table"));
        if (routeSummary == null || routeSummary.path("rows").isEmpty()) {
            issues.add("Include route-summary as a table with one row per useful route and a unique row ID.");
        }
        if (routeSummary != null) {
            for (JsonNode route : routeSummary.path("rows")) {
                String routeId = route.path("id").asText("");
                if (routeId.isEmpty()) {
                    issues.add("Each route-summary row needs a unique route ID.");
                    continue;
                }
                JsonNode timeline = findBlockByIdAndType(blocks, routeId + "-timeline", TIMELINE_OR_STEPS);
                if (timeline == null || !(timeline.path("rows").size() > 0 || timeline.path("items").size() > 0)) {
                    issues.add("Include " + routeId + "-timeline: the complete chronological stages, sub-steps, "
                        + "purpose and starting point for this route, not a shared generic timeline.");
                }
                JsonNode considerations = findBlockById(blocks, routeId + "-considerations");
                boolean hasConsiderations = considerations != null
                    && (!considerations.path("text").asText("").trim().isEmpty() || considerations.path("items").size() > 0);
                if (!hasConsiderations) {
                    issues.add("Include " + routeId + "-considerations: accessibility, practical risks, trade-offs "
                        + "and how to reduce them.");
                }
            }
        }

        JsonNode comparison = findBlockByIdAndType(blocks, "route-comparison", TABLE_OR_COMPARISON);
        if (comparison == null || comparison.path("rows").isEmpty()) {
            issues.add("Include route-comparison: time, cost, risk, foundational knowledge and flexibility, with "
                + "unknowns identified.");
        }

        JsonNode playbook = findBlockByIdAndType(blocks, "experience-playbook", Set.of("steps"));
        if (playbook == null || playbook.path("items").size() != 6) {
            issues.add("Include experience-playbook as a six-item steps block: target experience, evidence, "
                + "preparation, readiness, experience goals, and follow-on strategy. Tailor it to the current goal, "
                + "including exploratory activities when the career is undecided.");
        }
        return issues;
    }

    private static JsonNode findBlockById(JsonNode blocks, String id) {
        for (JsonNode b : blocks) if (id.equals(b.path("id").asText(""))) return b;
        return null;
    }

    private static JsonNode findBlockByIdAndType(JsonNode blocks, String id, Set<String> types) {
        for (JsonNode b : blocks) {
            if (id.equals(b.path("id").asText("")) && types.contains(b.path("type").asText(""))) return b;
        }
        return null;
    }
}
