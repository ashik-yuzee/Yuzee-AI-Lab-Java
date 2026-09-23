package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import com.yuzee.tokenlab.repository.LocalJsonStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of miniPathway/service.ts's {@code MiniPathwayService} (plus reportReview.ts and the
 * policy.ts source/episode helpers). Runs are stored on {@code Conversation.miniPathways} in the
 * exact MiniPathwayRun shape the original writes to data/mini-pathways.json: a {@code running} run
 * is saved first and later marked {@code complete} or {@code error}.
 */
@Service
public class MiniPathwayService {

    public static final String MINI_PATHWAY_VERSION = "mini-pathway-v2-report-contract";
    private static final int MAX_OUTPUT_TOKENS = 24000;
    private static final Pattern MONEY_PATTERN = Pattern.compile(
        "[$£€]\\s*\\d|\\b(?:AUD|USD|GBP|EUR)\\s*\\d|\\b\\d[\\d,.]*\\s*(?:dollars|pounds|euros)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern GUARANTEE_PATTERN = Pattern.compile(
        "\\b(?:zero tuition|no tuition|zero out.of.pocket|full wage|guaranteed (?:job|employment|placement))\\b",
        Pattern.CASE_INSENSITIVE);
    // HelpEvidence.ts concernsHelp()/outdatedHelpClaim()
    private static final Pattern CONCERNS_HELP = Pattern.compile(
        "\\b(?:HECS|HELP (?:loan|debt|repayment)|student loan repayment)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTDATED_HELP = Pattern.compile(
        "\\b1\\s*%\\s*(?:to|–|-)\\s*10\\s*%|(?:start|begin)s?\\s+at\\s+1\\s*%|lowest threshold tier", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_CAVEAT = Pattern.compile(
        "(?:outdated|no longer|not current|old system|previous system)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TIMELINE_OR_STEPS = Set.of("table", "steps");
    private static final Set<String> TABLE_OR_COMPARISON = Set.of("table", "comparison");
    private static final DateTimeFormatter ISO_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final GeminiService geminiService;
    private final ProtocolValidator protocolValidator;
    private final PathwayPolicyService policy;
    private final ConversationService conversations;
    /** `new LocalConversationStore('data/mini-pathways.json')` (relative to the working directory). */
    private final LocalJsonStore store = new LocalJsonStore(java.nio.file.Path.of("data", "mini-pathways.json"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    private String prompt;
    private String systemInstruction;

    private TokenService tokenService;

    @Autowired(required = false)
    void setTokenService(TokenService tokenService) { this.tokenService = tokenService; }

    public MiniPathwayService(GeminiService geminiService, ProtocolValidator protocolValidator,
                              PathwayPolicyService policy, ConversationService conversations) {
        this.geminiService = geminiService;
        this.protocolValidator = protocolValidator;
        this.policy = policy;
        this.conversations = conversations;
    }

    @PostConstruct
    void init() {
        prompt = readClasspathText("prompts/mini-pathway-prompt.md");
        String override = readClasspathText("prompts/mini-pathway-override.md").replaceFirst("\\r?\\n$", "");
        String schemaJson;
        try {
            schemaJson = mapper.readTree(readClasspathText("prompts/response-schema-v1.3.json")).toString(); // JSON.stringify(schema)
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        systemInstruction = prompt + "\n\n" + override.replace("__CANONICAL_SCHEMA_JSON__", schemaJson);
    }

    private static String readClasspathText(String location) {
        try (InputStream is = new ClassPathResource(location).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load classpath resource: " + location, e);
        }
    }

    // -----------------------------------------------------------------
    // Store (service.ts list(); LocalConversationStore save())
    // -----------------------------------------------------------------

    /** service.ts list(): a running run with no active generation is reported as stopped. */
    public List<Map<String, Object>> list(Conversation conv) {
        return list(conv.getId());
    }

    private List<Map<String, Object>> list(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : store.list()) {
            if (!id.equals(r.get("conversationId"))) continue;
            if ("running".equals(r.get("status")) && !active.contains(id)) {
                Map<String, Object> copy = new LinkedHashMap<>(r);
                copy.put("status", "error");
                copy.put("error", "The previous pathway stopped. You can try again.");
                out.add(copy);
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** service.ts remove(): delete every run of the conversation from data/mini-pathways.json. */
    public void remove(String conversationId) {
        for (Map<String, Object> r : list(conversationId)) store.delete(String.valueOf(r.get("id")));
    }

    /** this.store.save(run). */
    private void saveRun(Map<String, Object> run) {
        store.save(run);
    }

    private boolean isCurrent(String conversationId, String sourceId) {
        return conversations.findById(conversationId).map(c -> {
            List<ChatMessage> m = c.getMessages();
            return !m.isEmpty() && sourceId.equals(m.get(m.size() - 1).getId());
        }).orElse(false);
    }

    // -----------------------------------------------------------------
    // policy.ts helpers that need the protocol validator
    // -----------------------------------------------------------------

    /** responsePresentation.ts acceptedResponse(): the protocol-accepted v1.3 envelope, else null. */
    public JsonNode acceptedResponse(Object value) {
        if (value == null) return null;
        try {
            String raw = value instanceof String s ? s : mapper.writeValueAsString(value);
            if (!protocolValidator.validateProtocolResponse(raw, "1.3").isValid()) return null;
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode messageResponse(ChatMessage m) {
        return acceptedResponse(m.getParsedResponse() != null ? m.getParsedResponse() : m.getContent());
    }

    /** policy.ts currentPathwaySource(). */
    private JsonNode currentPathwaySource(List<ChatMessage> messages, String sourceId) {
        ChatMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last == null || !sourceId.equals(last.getId()) || !"assistant".equals(last.getRole())
            || Boolean.TRUE.equals(last.getStreamStopped()) || Boolean.TRUE.equals(last.getValidationFailed())) return null;
        return messageResponse(last);
    }

    /** policy.ts alreadyHelpedInLowEpisode(). */
    private boolean alreadyHelpedInLowEpisode(List<ChatMessage> messages, List<Map<String, Object>> runs) {
        int index = -1;
        for (int i = 0; i < messages.size(); i++) {
            String id = messages.get(i).getId();
            if (runs.stream().anyMatch(r -> id.equals(r.get("sourceMessageId")))) index = i;
        }
        if (index < 0) return false;
        for (ChatMessage m : messages.subList(index + 1, messages.size())) {
            if (!"assistant".equals(m.getRole())) continue;
            JsonNode r = messageResponse(m);
            Integer score = PathwayPolicyService.pathwayScore(r);
            if ((score == null ? -1 : score) >= PathwayPolicyService.MINI_PATHWAY_THRESHOLD) return false;
            if (r != null) {
                for (JsonNode code : r.path("state").path("user_confidence").path("reason_codes")) {
                    if ("NEW_TOPIC_RESET".equals(code.asText())) return false;
                }
            }
        }
        return true;
    }

    private String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        try {
            return mapper.writeValueAsString(content);
        } catch (IOException e) {
            return String.valueOf(content);
        }
    }

    /** sha256 of the prompt file, as service.ts's run.promptHash. */
    private String promptHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // -----------------------------------------------------------------
    // service.ts generate()
    // -----------------------------------------------------------------

    /**
     * @param aborted  set when the client disconnects or the 120s route timeout fires (AbortSignal).
     * @param progress receives service.ts progress stages.
     * @param draft    receives {type:'reset'} and {type:'block',block} draft events.
     */
    public Map<String, Object> generate(Conversation conv, Map<String, Object> request, AtomicBoolean aborted,
                                        Consumer<String> progress, Consumer<Map<String, Object>> draft) {
        if (request == null || !("automatic".equals(request.get("mode")) || "manual".equals(request.get("mode")))
            || !(request.get("sourceMessageId") instanceof String sourceId)
            || (request.containsKey("location") && !(request.get("location") instanceof String loc && loc.length() <= 150))) {
            throw new PathwayGenerationException("Invalid mini pathway request.");
        }
        String convId = conv.getId();
        List<ChatMessage> messages = conv.getMessages();
        JsonNode source = currentPathwaySource(messages, sourceId);
        if (source == null) throw new PathwayGenerationException("This answer has changed. Please use the latest response.", 409);
        String userText = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) { userText = contentText(messages.get(i).getContent()); break; }
        }
        List<Map<String, Object>> saved = list(conv);
        JsonNode hint = mapper.valueToTree(request.get("hint"));
        Map<String, Object> decision = policy.decide(source, userText, hint, alreadyHelpedInLowEpisode(messages, saved));
        if ("none".equals(decision.get("action"))) throw new PathwayGenerationException("A mini pathway is not relevant to this response.");
        for (Map<String, Object> r : saved) {
            if (sourceId.equals(r.get("sourceMessageId")) && MINI_PATHWAY_VERSION.equals(r.get("version")) && "complete".equals(r.get("status"))) return r;
        }
        if ("automatic".equals(request.get("mode")) && !"automatic".equals(decision.get("action"))) {
            throw new PathwayGenerationException("This pathway is optional. Choose it when you want to explore further.", 409);
        }
        if (active.contains(convId)) throw new PathwayGenerationException("A mini pathway is already being prepared.", 409);
        if (!geminiService.isConfigured()) throw new PathwayGenerationException("Mini Pathway is not connected to Gemini.", 503);
        if (aborted.get() || !isCurrent(convId, sourceId)) throw new PathwayGenerationException("This pathway was stopped.", 409);
        if (!active.add(convId)) throw new PathwayGenerationException("A mini pathway is already being prepared.", 409);
        String model = conv.getModelId() != null ? conv.getModelId() : GeminiModelRegistry.DEFAULT_MODEL_ID;
        Map<String, Object> run = null;
        Map<String, Integer> usage = new LinkedHashMap<>();
        try {
            // Keep complete messages; do not silently cut a user's constraints mid-sentence.
            List<ChatMessage> turns = messages.stream().filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole())).toList();
            ArrayNode history = mapper.createArrayNode();
            for (ChatMessage m : turns.subList(Math.max(0, turns.size() - 16), turns.size())) {
                history.addObject().put("role", m.getRole()).put("content", contentText(m.getContent()));
            }
            if (history.toString().length() > 90000) {
                throw new PathwayGenerationException("There is too much detail for this mini pathway. Continue with a focused question in the chat.");
            }
            usage.put("inputTokens", 0);
            usage.put("outputTokens", 0);
            usage.put("thinkingTokens", 0);
            run = new LinkedHashMap<>();
            run.put("id", UUID.randomUUID().toString());
            run.put("conversationId", convId);
            run.put("sourceMessageId", sourceId);
            run.put("version", MINI_PATHWAY_VERSION);
            run.put("status", "running");
            run.put("mode", request.get("mode"));
            run.put("createdAt", ISO_MILLIS.format(Instant.now()));
            run.put("model", model);
            run.put("promptHash", promptHash());
            run.put("decision", decision);
            if (request.containsKey("hint")) run.put("hint", request.get("hint"));
            run.put("usage", usage);
            saveRun(run);

            ObjectNode contents = mapper.createObjectNode();
            contents.put("task", "Create a mini pathway to support this counselling conversation. Focus on the unresolved decision and practical options, not a definitive choice for the user.");
            contents.set("conversation", history);
            contents.set("source_response", source);
            ObjectNode runtime = contents.putObject("runtime_metadata");
            runtime.put("is_first_interaction", false);
            runtime.putNull("pending_gate");
            JsonNode confidence = source.path("state").path("user_confidence");
            if (!confidence.isMissingNode()) runtime.set("prior_user_confidence", confidence);
            runtime.put("user_entered_location", request.get("location") instanceof String l ? l : "");
            runtime.putArray("verified_service_action_ids");
            runtime.put("side_panel", true);
            JsonNode mainQuestion = source.path("interaction").path("question");
            if (!mainQuestion.isMissingNode()) runtime.set("main_question", mainQuestion);

            JsonNode response = null;
            List<String> reviewIssues = List.of();
            for (int attempt = 0; attempt < 2; attempt++) {
                if (aborted.get() || !isCurrent(convId, sourceId)) throw new PathwayGenerationException("This pathway was stopped.", 409);
                progress.accept(attempt > 0 ? "Checking the pathway format" : "Exploring possible routes");
                draft.accept(Map.of("type", "reset"));
                String userContent = contents.toString();
                if (attempt > 0) {
                    ObjectNode retry = mapper.createObjectNode();
                    retry.set("original_request", contents);
                    ArrayNode issues = retry.putArray("validation_issues");
                    reviewIssues.forEach(issues::add);
                    retry.put("task", "Regenerate the complete report, correcting every validation issue. Preserve the full report depth and canonical schema. Do not include another question or service action.");
                    userContent = retry.toString();
                }
                String text = stream(model, userContent, aborted, () -> isCurrent(convId, sourceId), progress, draft, usage);
                if (aborted.get() || !isCurrent(convId, sourceId)) throw new PathwayGenerationException("This pathway was stopped.", 409);
                progress.accept("Checking the complete pathway");
                try {
                    JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                    if (parsed == null || parsed.isMissingNode()) throw new IOException("empty");
                    ProtocolValidator.ValidationResult validation = protocolValidator.validateProtocolResponse(text, "1.3");
                    if (!validation.isValid()) {
                        reviewIssues = validation.errors;
                    } else {
                        if (!validateMiniPathwayInvariants(parsed).isEmpty()) throw new IllegalStateException("invalid output");
                        response = parsed;
                        reviewIssues = reviewMiniPathwayReport(parsed);
                    }
                    run.put("qualityIssues", reviewIssues);
                    if (reviewIssues.isEmpty()) break;
                } catch (Exception e) {
                    reviewIssues = List.of("Return complete valid canonical JSON without questions, service actions, or unsupported outcome claims.");
                    run.put("qualityIssues", reviewIssues);
                }
                if (attempt > 0) throw new PathwayGenerationException("The pathway needs more complete or better-supported detail. Please try again.", 502);
            }
            if (aborted.get() || !isCurrent(convId, sourceId)) throw new PathwayGenerationException("This pathway was stopped.", 409);
            run.put("response", response);
            run.put("status", "complete");
            saveRun(run);
            progress.accept("Ready");
            return run;
        } catch (RuntimeException error) {
            if (run != null) {
                run.put("status", "error");
                run.put("error", error instanceof PathwayGenerationException ? error.getMessage()
                    : aborted.get() ? "The mini pathway was stopped." : "We could not prepare the mini pathway. Please try again.");
                saveRun(run);
            }
            throw error instanceof PathwayGenerationException p ? p
                : new PathwayGenerationException(run != null ? (String) run.get("error") : "We could not prepare the mini pathway. Please try again.", 502);
        } finally {
            active.remove(convId);
            // server.ts onUsage: appendTokenLog for any run that consumed input tokens.
            if (run != null && tokenService != null && run.get("usage") instanceof Map<?, ?> u
                && u.get("inputTokens") instanceof Integer in && in > 0) {
                int out = (Integer) u.get("outputTokens") + (Integer) u.get("thinkingTokens");
                // {ts, endpoint, model, conversationId, inputTokens, outputTokens}: conversationId before the counts
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ts", System.currentTimeMillis());
                entry.put("endpoint", "/api/mini-pathway");
                entry.put("model", run.get("model"));
                entry.put("conversationId", convId);
                entry.put("inputTokens", in);
                entry.put("outputTokens", out);
                tokenService.appendTokenLog(entry);
            }
        }
    }

    /** One streamed Gemini attempt; returns the full text or throws a PathwayGenerationException. */
    private String stream(String model, String userContent, AtomicBoolean aborted, java.util.function.BooleanSupplier current,
                          Consumer<String> progress, Consumer<Map<String, Object>> draft, Map<String, Integer> usage) {
        ArrayNode contents = mapper.createArrayNode();
        contents.addObject().put("role", "user").putArray("parts").addObject().put("text", userContent);
        ObjectNode extras = mapper.createObjectNode();
        extras.put("responseMimeType", "application/json");
        extras.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        PathwayBlockStreamParser blocks = new PathwayBlockStreamParser();
        StringBuilder text = new StringBuilder();
        boolean[] received = {false}, stopped = {false};
        List<Exception> errors = new ArrayList<>();
        GeminiService.StreamResult[] done = new GeminiService.StreamResult[1];
        geminiService.streamGenerateRich(model, systemInstruction, contents, extras, null, chunk -> {
            // service.ts checks signal/isCurrent() per chunk. GeminiService swallows exceptions thrown per chunk and
            // cannot be cancelled, so a stopped run ignores the rest of the stream and throws the same 409 after it.
            if (stopped[0] || aborted.get()) return;
            if (!current.getAsBoolean()) { stopped[0] = true; return; }
            if (!received[0]) { progress.accept("Your pathway is taking shape"); received[0] = true; }
            text.append(chunk);
            for (JsonNode block : blocks.push(chunk)) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "block");
                event.put("block", block);
                draft.accept(event);
            }
        }, r -> done[0] = r, errors::add);
        // Gemini reports cumulative usage, not the cost of each chunk.
        if (done[0] != null) {
            usage.merge("inputTokens", done[0].promptTokens, Integer::sum);
            usage.merge("outputTokens", done[0].outputTokens, Integer::sum);
            usage.merge("thinkingTokens", done[0].thinkingTokens, Integer::sum);
        }
        if (stopped[0] && !aborted.get()) throw new PathwayGenerationException("This pathway was stopped.", 409);
        if (!errors.isEmpty()) {
            if (errors.get(0) instanceof PathwayGenerationException p) throw p;
            throw new IllegalStateException("Gemini stream failed", errors.get(0));
        }
        // An aborted provider stream rejects with a non-PathwayError ("The mini pathway was stopped.", 502).
        if (aborted.get()) throw new IllegalStateException("aborted");
        if (done[0] == null || !"STOP".equals(done[0].finishReason)) {
            throw new PathwayGenerationException("The mini pathway was incomplete. Please try again.", 502);
        }
        return text.toString();
    }

    // -----------------------------------------------------------------
    // service.ts validateMiniPathwayOutput() beyond base protocol validity. Any issue here is
    // thrown inside generate()'s parse try-block, so it surfaces as the generic review issue.
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
        if (boundaryViolation) issues.add("The mini pathway included an unexpected question or action. Please try again.");

        boolean hasVisibleContent = false;
        for (JsonNode block : response.path("content_blocks")) {
            if (!block.path("text").asText("").isEmpty() || block.path("items").size() > 0 || block.path("rows").size() > 0) {
                hasVisibleContent = true;
                break;
            }
        }
        if (!hasVisibleContent || outdatedHelpClaim(response.path("content_blocks").toString())) {
            issues.add("The mini pathway needs another check. Please try again.");
        }
        return issues;
    }

    /** HelpEvidence.ts outdatedHelpClaim(). */
    static boolean outdatedHelpClaim(String text) {
        if (!CONCERNS_HELP.matcher(text).find()) return false;
        Matcher m = OUTDATED_HELP.matcher(text);
        while (m.find()) {
            String around = text.substring(Math.max(0, m.start() - 100), Math.min(text.length(), m.end() + 50));
            if (!HELP_CAVEAT.matcher(around).find()) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // reportReview.ts reviewMiniPathwayReport()
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
