package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.ChatRequest;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.model.DetailRequest;
import com.yuzee.tokenlab.model.DetailResult;
import com.yuzee.tokenlab.model.ObjectiveSession;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import com.yuzee.tokenlab.protocol.SecurityStateService;
import com.yuzee.tokenlab.protocol.TrustedServiceActions;
import com.yuzee.tokenlab.service.ConversationLogService;
import com.yuzee.tokenlab.service.ConversationService;
import com.yuzee.tokenlab.service.DetailResearchService;
import com.yuzee.tokenlab.service.GeminiModelRegistry;
import com.yuzee.tokenlab.service.GeminiService;
import com.yuzee.tokenlab.service.MiniPathwayService;
import com.yuzee.tokenlab.service.ObjectiveService;
import com.yuzee.tokenlab.service.OalaService;
import com.yuzee.tokenlab.service.PathwayGenerationException;
import com.yuzee.tokenlab.service.ProviderRecoveryService;
import com.yuzee.tokenlab.service.AssembledRequest;
import com.yuzee.tokenlab.service.RequestAssemblerService;
import com.yuzee.tokenlab.service.ReviewFailure;
import com.yuzee.tokenlab.service.ReviewRetryService;
import com.yuzee.tokenlab.service.SystemPromptCacheManager;
import com.yuzee.tokenlab.service.SystemPromptService;
import com.yuzee.tokenlab.service.TeachingAnswerReviewService;
import com.yuzee.tokenlab.service.TokenService;
import com.yuzee.tokenlab.service.TurnNeedsService;
import com.yuzee.tokenlab.model.HistoryTurn;
import com.yuzee.tokenlab.model.TurnNeeds;
import com.yuzee.tokenlab.model.warehouse.WarehouseInput;
import com.yuzee.tokenlab.model.warehouse.WarehousePack;
import com.yuzee.tokenlab.service.warehouse.WarehouseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private static final int LOGGED_TEXT_MAX_CHARS = 2000;

    private final ConversationService conversationService;
    private final GeminiService geminiService;
    private final SystemPromptService systemPromptService;
    private final TokenService tokenService;
    private final RequestAssemblerService requestAssemblerService;
    private final ProtocolValidator protocolValidator;
    private final SecurityStateService securityStateService;
    private final SystemPromptCacheManager cacheManager;
    private final ProviderRecoveryService providerRecoveryService;
    private final ReviewRetryService reviewRetryService;
    private final TeachingAnswerReviewService teachingAnswerReviewService;
    private final GeminiModelRegistry modelRegistry;
    private final ConversationLogService conversationLogService;
    private final OalaService oalaService;
    private final MiniPathwayService miniPathwayService;
    private final DetailResearchService detailResearchService;
    private final ObjectiveService objectiveService;
    private final TurnNeedsService turnNeedsService;
    private final WarehouseService warehouseService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Track active streams so they can be cancelled
    private final Map<String, SseEmitter> activeEmitters = new HashMap<>();

    public ChatController(ConversationService conversationService,
                          GeminiService geminiService,
                          SystemPromptService systemPromptService,
                          TokenService tokenService,
                          RequestAssemblerService requestAssemblerService,
                          ProtocolValidator protocolValidator,
                          SecurityStateService securityStateService,
                          SystemPromptCacheManager cacheManager,
                          ProviderRecoveryService providerRecoveryService,
                          ReviewRetryService reviewRetryService,
                          TeachingAnswerReviewService teachingAnswerReviewService,
                          GeminiModelRegistry modelRegistry,
                          ConversationLogService conversationLogService,
                          OalaService oalaService,
                          MiniPathwayService miniPathwayService,
                          DetailResearchService detailResearchService,
                          ObjectiveService objectiveService,
                          TurnNeedsService turnNeedsService,
                          WarehouseService warehouseService) {
        this.conversationService = conversationService;
        this.geminiService = geminiService;
        this.systemPromptService = systemPromptService;
        this.tokenService = tokenService;
        this.requestAssemblerService = requestAssemblerService;
        this.protocolValidator = protocolValidator;
        this.securityStateService = securityStateService;
        this.cacheManager = cacheManager;
        this.providerRecoveryService = providerRecoveryService;
        this.reviewRetryService = reviewRetryService;
        this.teachingAnswerReviewService = teachingAnswerReviewService;
        this.modelRegistry = modelRegistry;
        this.conversationLogService = conversationLogService;
        this.oalaService = oalaService;
        this.miniPathwayService = miniPathwayService;
        this.detailResearchService = detailResearchService;
        this.objectiveService = objectiveService;
        this.turnNeedsService = turnNeedsService;
        this.warehouseService = warehouseService;
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String id, @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) {
            SseEmitter emitter = new SseEmitter();
            sendQuiet(emitter, Map.of("error", "Conversation not found"));
            emitter.complete();
            return emitter;
        }

        Conversation conv = convOpt.get();
        SseEmitter emitter = new SseEmitter(120_000L);
        activeEmitters.put(id, emitter);

        String modelId = request.getModelId() != null ? request.getModelId()
            : conv.getModelId() != null ? conv.getModelId() : GeminiModelRegistry.DEFAULT_MODEL_ID;

        Object currentUserInput = request.getMessage() != null ? request.getMessage() : request.getUserEvent();
        if (currentUserInput == null) {
            sendQuiet(emitter, Map.of("error", "No message or interaction event supplied"));
            emitter.complete();
            activeEmitters.remove(id);
            return emitter;
        }

        // Server-side re-validation of a structured answer against the interaction the server
        // actually last sent — never trust a client-echoed copy of the question.
        if (request.getUserEvent() != null && !request.getUserEvent().isEmpty()) {
            Map<String, Object> activeInteraction = lastActiveInteraction(conv);
            ProtocolValidator.UserEventValidationResult uev =
                protocolValidator.validateUserEventAgainstActiveInteraction(request.getUserEvent(), activeInteraction);
            if (!uev.valid) {
                sendQuiet(emitter, Map.of("error", String.join(" ", uev.errors)));
                emitter.complete();
                activeEmitters.remove(id);
                return emitter;
            }
        }

        String clientIp = httpRequest.getRemoteAddr();

        // The user message is added to conv.getMessages() inside runTurn, AFTER assembleRequest
        // runs — RequestAssemblerService's bypass classifier and ConversationMemoryService's
        // history assembly both read conv.getMessages() as "everything before this turn"; adding
        // it here first would make every turn look like a mid-conversation continuation (its own
        // just-added message defeats the greeting/farewell/idle bypass check) and would leak the
        // current turn into its own retained-history budget.
        executor.execute(() -> runTurn(id, conv, modelId, currentUserInput, emitter, clientIp));

        return emitter;
    }

    private void runTurn(String conversationId, Conversation conv, String modelId, Object currentUserInput,
                          SseEmitter emitter, String clientIp) {
        long startedAt = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        String errorCode = null;
        int promptTokens = 0, outputTokens = 0, cachedTokens = 0, thinkingTokens = 0;
        String finishReason = null;
        JsonNode finalParsed = null;
        String responseText = "";
        boolean validationFailed = false;
        com.yuzee.tokenlab.model.CompactionMetrics compactionMetrics = null;

        try {
            sendQuiet(emitter, Map.of("phase", "routing"));

            // @Oala addressed-mention handling: an exact FAQ match short-circuits like the
            // greeting/farewell bypass (below); anything else strips the mention from the text
            // actually sent to Gemini and appends the service-catalogue instruction so the model
            // answers as Oala for this turn only. The conversation history still records what the
            // user actually typed (see userMsg below), never the stripped/rewritten form.
            String oalaInstruction = null;
            Object effectiveInput = currentUserInput;
            String oalaBasicAnswer = null;
            if (currentUserInput instanceof String text && oalaService.addressesOala(text)) {
                String stripped = oalaService.stripOalaMention(text);
                Optional<String> basic = oalaService.basicAnswer(stripped);
                if (basic.isPresent()) {
                    oalaBasicAnswer = basic.get();
                } else {
                    effectiveInput = stripped;
                    oalaInstruction = oalaService.buildOalaInstruction();
                }
            }

            AssembledRequest assembled;
            if (oalaBasicAnswer != null) {
                assembled = new AssembledRequest();
                assembled.bypassResponseText = oalaBasicAnswer;
            } else {
                assembled = requestAssemblerService.assembleRequest(
                    conv, effectiveInput, systemPromptService.getPrompt(), modelId);
                if (oalaInstruction != null) {
                    assembled.systemInstruction = (assembled.systemInstruction == null ? "" : assembled.systemInstruction)
                        + "\n\n" + oalaInstruction;
                }
            }
            compactionMetrics = assembled.compactionMetrics;

            // Advisory-only research-need classification (TurnNeedsService), read by the frontend's
            // "explore more" panel. Never lets a classifier failure break the actual turn.
            TurnNeeds preflight = null;
            try {
                List<HistoryTurn> historyTurns = new ArrayList<>();
                for (ChatMessage m : conv.getMessages()) {
                    HistoryTurn ht = new HistoryTurn(m.getRole(),
                        m.getContent() instanceof String s ? s : "");
                    if ("assistant".equals(m.getRole())) ht.setPreflight(m.getTurnNeeds());
                    historyTurns.add(ht);
                }
                boolean structuredTurn = !(currentUserInput instanceof String);
                String turnText = currentUserInput instanceof String s ? s : "";
                preflight = turnNeedsService.assessTurnNeeds(turnText, historyTurns, structuredTurn, null, null);
            } catch (Exception ignored) {
                // classification is advisory only
            }

            // Warehouse enrichment (course/provider/career/local data) — advisory only, same as
            // the TurnNeeds preflight above: needsWarehouse() cheaply gates out trivial/off-topic
            // turns before any LLM planning call runs, and any failure here (including the
            // warehouse source database simply not being configured) must never break the turn.
            // Bounded with a timeout, not just try/catch: the very first call after a source-data
            // change can spend a long time rebuilding the on-disk FTS5 index (WarehouseIndexBuilder
            // .ensureReady() is synchronized and can take a while over a multi-GB source file), and
            // a slow enrichment must never make a user wait on their actual chat turn. The build
            // keeps running to completion on the executor thread even after we stop waiting on it,
            // so later turns benefit from a warm index.
            WarehousePack warehousePack = null;
            try {
                String turnText = currentUserInput instanceof String s ? s : "";
                StringBuilder recentContext = new StringBuilder();
                List<ChatMessage> priorMessages = conv.getMessages();
                for (int i = Math.max(0, priorMessages.size() - 4); i < priorMessages.size(); i++) {
                    Object c = priorMessages.get(i).getContent();
                    if (c instanceof String s2) recentContext.append(s2).append('\n');
                }
                WarehouseInput whInput = new WarehouseInput(turnText);
                whInput.setContext(recentContext.toString());
                warehousePack = executor.submit(() -> warehouseService.retrieve(whInput))
                    .get(4, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // enrichment is advisory only -- including a timeout while a first-time index build runs
            }

            // Persist the user's turn now — after classification/history assembly has already
            // run against the conversation's prior state, but before anything downstream can
            // fail, so a message is never lost even if the Gemini call itself errors out.
            ChatMessage userMsg = new ChatMessage();
            userMsg.setRole("user");
            userMsg.setContent(currentUserInput);
            conv.getMessages().add(userMsg);
            conversationService.save(conv);

            if (assembled.bypassResponseText != null) {
                finalParsed = buildBypassEnvelope(assembled.bypassResponseText);
                responseText = mapper.writeValueAsString(finalParsed);
            } else {
                sendQuiet(emitter, Map.of("phase", "receiving"));

                Optional<String> cacheName = cacheManager.getOrCreateCache(modelId, assembled.systemInstruction);
                String systemInstructionForCall = cacheName.isPresent() ? null : assembled.systemInstruction;

                StringBuilder fullText = new StringBuilder();
                boolean[] anyChunkEmitted = {false};
                Exception[] postStreamError = {null};

                GeminiService.StreamResult streamResult;
                try {
                    streamResult = providerRecoveryService.openWithRecovery(() -> {
                        fullText.setLength(0);
                        anyChunkEmitted[0] = false;
                        GeminiService.StreamResult[] doneHolder = new GeminiService.StreamResult[1];
                        Exception[] errHolder = new Exception[1];

                        geminiService.streamGenerateRich(
                            modelId, systemInstructionForCall, assembled.contents, assembled.generationConfigExtras,
                            cacheName.orElse(null),
                            chunk -> {
                                anyChunkEmitted[0] = true;
                                fullText.append(chunk);
                                sendQuiet(emitter, Map.of("chunk", chunk));
                            },
                            done -> doneHolder[0] = done,
                            err -> errHolder[0] = err
                        );

                        if (errHolder[0] != null) {
                            if (anyChunkEmitted[0]) {
                                // Already streamed partial content to the client — never retry a
                                // stream that has started (would duplicate output). Surface as a
                                // completed-but-broken result instead of throwing.
                                GeminiService.StreamResult partial = new GeminiService.StreamResult();
                                partial.text = fullText.toString();
                                partial.finishReason = "ERROR";
                                return partial;
                            }
                            throw errHolder[0];
                        }
                        return doneHolder[0];
                    }, () -> sendQuiet(emitter, Map.of("phase", "retrying")));
                } catch (Exception e) {
                    sendQuiet(emitter, Map.of("error", e.getMessage() != null ? e.getMessage() : "Gemini request failed"));
                    emitter.complete();
                    errorCode = "STREAM_FAILED: " + e.getMessage();
                    logAndCleanup(conversationId, clientIp, messageId, modelId, 0, 0, 0, 0, null, null, startedAt,
                        currentUserInput, "", errorCode);
                    return;
                }

                responseText = streamResult.text;
                promptTokens = streamResult.promptTokens;
                outputTokens = streamResult.outputTokens;
                cachedTokens = streamResult.cachedTokens;
                thinkingTokens = streamResult.thinkingTokens;
                finishReason = streamResult.finishReason;

                ProtocolValidator.ValidationResult vr = protocolValidator.validateProtocolResponse(responseText, "1.3");
                finalParsed = vr.parsed;
                validationFailed = !vr.isValid();

                if (vr.parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    securityStateService.normaliseSecurityFields(vr.parsed);
                    // No authoritative server-side security events are wired yet (rate-limit
                    // violations, content-filter flags) — breach count stays 0 until one is,
                    // matching the old app's own current behavior (authoritativeBreachDelta is
                    // 0 everywhere it's called there too).
                    SecurityStateService.NextSecurityState next = securityStateService.computeNextSecurityState(0);
                    securityStateService.applyServerSecurityState(vr.parsed, next.newBreachCount, next.newPenalty);
                }

                if (vr.isValid() && teachingAnswerReviewService.shouldReviewTeaching(vr.parsed)) {
                    sendQuiet(emitter, Map.of("phase", "reviewing"));
                    try {
                        JsonNode reviewed = runTeachingReview(modelId, vr.parsed);
                        finalParsed = reviewed;
                        responseText = mapper.writeValueAsString(reviewed);
                    } catch (ReviewFailure rf) {
                        // The original answer is still valid and schema-conformant — keep showing
                        // it rather than blocking the user on an optional quality pass. Recorded
                        // for operators via the turn log's errorCode.
                        errorCode = "TEACHING_REVIEW_FAILED: " + rf.getMessage();
                    }
                }
            }

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setId(messageId);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(responseText);
            if (finalParsed != null) {
                assistantMsg.setParsedResponse(mapper.convertValue(finalParsed, Map.class));
            }
            assistantMsg.setValidationFailed(validationFailed);
            assistantMsg.setTurnNeeds(preflight);
            Map<String, Object> usageMap = Map.of(
                "promptTokens", promptTokens,
                "outputTokens", outputTokens,
                "cachedTokens", cachedTokens,
                "thinkingTokens", thinkingTokens
            );
            assistantMsg.setTokenUsage(usageMap);
            conv.getMessages().add(assistantMsg);
            conversationService.save(conv);

            tokenService.recordTurn(modelId, promptTokens, outputTokens);
            double cost = modelRegistry.calcTurnCost(modelId, promptTokens, outputTokens, cachedTokens);
            logAndCleanup(conversationId, clientIp, messageId, modelId, promptTokens, cachedTokens, outputTokens,
                thinkingTokens, cost, finishReason, startedAt, currentUserInput, responseText, errorCode);

            Map<String, Object> finalPayload = new LinkedHashMap<>();
            finalPayload.put("done", true);
            finalPayload.put("messageId", messageId);
            finalPayload.put("parsedResponse", finalParsed != null ? finalParsed : Map.of());
            finalPayload.put("validationFailed", validationFailed);
            finalPayload.put("tokenUsage", usageMap);
            if (compactionMetrics != null) finalPayload.put("compaction", compactionMetrics);
            TurnNeeds offer = preflight != null ? turnNeedsService.researchOffer(preflight) : null;
            if (offer != null) finalPayload.put("researchOffer", offer);
            if (warehousePack != null && ("READY".equals(warehousePack.getStatus()) || "NO_MATCH".equals(warehousePack.getStatus()))) {
                finalPayload.put("warehouseData", warehousePack);
            }
            sendQuiet(emitter, finalPayload);
            emitter.complete();
        } catch (Exception e) {
            sendQuiet(emitter, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unexpected error"));
            emitter.complete();
        } finally {
            activeEmitters.remove(conversationId);
        }
    }

    private JsonNode runTeachingReview(String modelId, JsonNode originalResponse) throws ReviewFailure {
        return reviewRetryService.runReview(() -> {
            String prompt;
            try {
                prompt = "Candidate content_blocks to review. Return ONLY a JSON object of the exact shape "
                    + "{\"content_blocks\": [...]} containing the corrected blocks.\n\n"
                    + mapper.writeValueAsString(Map.of("content_blocks", originalResponse.path("content_blocks")));
            } catch (Exception e) {
                throw new IllegalStateException("Incomplete teaching review", e);
            }
            String reviewText = geminiService.generate(modelId, TeachingAnswerReviewService.TEACHING_REVIEW_INSTRUCTION, prompt);
            JsonNode reviewJson = mapper.readTree(reviewText);
            return teachingAnswerReviewService.applyReviewedBlocks(originalResponse, reviewJson);
        });
    }

    private void logAndCleanup(String conversationId, String clientIp, String messageId, String modelId,
                                int promptTokens, int cachedTokens, int outputTokens, int thinkingTokens,
                                Double costUsd, String finishReason, long startedAt,
                                Object userInput, String assistantOutput, String errorCode) {
        try {
            int uncachedInput = Math.max(0, promptTokens - cachedTokens);
            conversationLogService.logTurn(
                clientIp, conversationId, messageId, modelId,
                promptTokens, uncachedInput, cachedTokens, outputTokens, thinkingTokens,
                costUsd, (int) (System.currentTimeMillis() - startedAt),
                finishReason, false, false,
                truncate(stringify(userInput)), truncate(assistantOutput), errorCode
            );
        } catch (Exception ignored) {
            // Telemetry logging must never break the actual chat turn.
        }
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > LOGGED_TEXT_MAX_CHARS ? text.substring(0, LOGGED_TEXT_MAX_CHARS) : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastActiveInteraction(Conversation conv) {
        List<ChatMessage> messages = conv.getMessages();
        if (messages == null || messages.isEmpty()) return null;
        ChatMessage last = messages.get(messages.size() - 1);
        if (!"assistant".equals(last.getRole()) || last.getParsedResponse() == null) return null;
        Object interaction = last.getParsedResponse().get("interaction");
        return interaction instanceof Map ? (Map<String, Object>) interaction : null;
    }

    /**
     * Builds a minimal, schema-conformant v1.3 envelope for a locally-constructed bypass reply
     * (greeting/farewell/idle/rubbish chit-chat) — these never go through Gemini, so they are
     * trusted by construction and are not run through {@link ProtocolValidator}.
     */
    private JsonNode buildBypassEnvelope(String text) {
        com.fasterxml.jackson.databind.node.ObjectNode env = mapper.createObjectNode();
        env.put("schema_version", "1.3");
        env.put("current_mode", "A_CONVERSATION");
        env.put("response_intent", "GENERAL_DELIVERY");

        com.fasterxml.jackson.databind.node.ObjectNode block = mapper.createObjectNode();
        block.put("id", "b1");
        block.put("type", "text");
        block.put("level", "none");
        block.put("variant", "default");
        block.put("title", "");
        block.put("text", text);
        block.set("items", mapper.createArrayNode());
        block.set("columns", mapper.createArrayNode());
        block.set("rows", mapper.createArrayNode());
        env.set("content_blocks", mapper.createArrayNode().add(block));

        com.fasterxml.jackson.databind.node.ObjectNode interaction = mapper.createObjectNode();
        interaction.put("kind", "none");
        interaction.put("input_type", "none");
        interaction.put("question_id", "");
        interaction.put("question", "");
        interaction.set("options", mapper.createArrayNode());
        interaction.put("allow_other_input", false);
        interaction.put("other_input_label", "");
        interaction.set("fields", mapper.createArrayNode());
        interaction.set("recommended_actions", mapper.createArrayNode());
        env.set("interaction", interaction);

        com.fasterxml.jackson.databind.node.ObjectNode serviceTrigger = mapper.createObjectNode();
        serviceTrigger.put("service_intent_detected", false);
        serviceTrigger.put("primary_requested_service", "NONE");
        serviceTrigger.put("confidence", "LOW");
        serviceTrigger.put("reason", "");
        serviceTrigger.put("trigger_now", false);
        serviceTrigger.put("needs_more_clarity", false);
        serviceTrigger.set("actions", mapper.createArrayNode());
        env.set("service_trigger", serviceTrigger);

        com.fasterxml.jackson.databind.node.ObjectNode rmoReadiness = mapper.createObjectNode();
        rmoReadiness.put("readiness", "NOT_READY");
        rmoReadiness.put("ready_to_generate", false);
        rmoReadiness.set("missing_inputs", mapper.createArrayNode());
        rmoReadiness.put("verification_required", false);
        env.set("rmo_readiness", rmoReadiness);

        com.fasterxml.jackson.databind.node.ObjectNode progress = mapper.createObjectNode();
        progress.put("explained", true);
        progress.put("failed_attempts", 0);
        progress.put("loop_count_same_issue", 0);
        progress.put("security_breach_count", 0);
        progress.put("active_security_penalty", "");

        com.fasterxml.jackson.databind.node.ObjectNode userConfidence = mapper.createObjectNode();
        userConfidence.put("score", -1);
        userConfidence.put("band", "unknown");
        userConfidence.put("evidence_strength", "none");
        userConfidence.put("trend", "unknown");
        userConfidence.set("reason_codes", mapper.createArrayNode());

        com.fasterxml.jackson.databind.node.ObjectNode state = mapper.createObjectNode();
        state.put("active_response_mode", "Quick");
        state.put("effective_response_mode", "Quick");
        state.put("mode_source", "default");
        state.put("safety_override_applied", false);
        state.set("user_confidence", userConfidence);
        state.set("progress", progress);
        env.set("state", state);

        com.fasterxml.jackson.databind.node.ObjectNode followups = mapper.createObjectNode();
        followups.put("enabled", false);
        followups.put("cancel_on_user_message", true);
        followups.put("topic_lock", false);
        followups.put("topic_key", "");
        followups.set("triggers", mapper.createArrayNode());
        env.set("followups", followups);

        return env;
    }

    private void sendQuiet(SseEmitter emitter, Object payload) {
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(payload)));
        } catch (IOException e) {
            // Client disconnected mid-stream — nothing more to do for this turn.
        }
    }

    @GetMapping(value = "/{id}/mini-pathway", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getMiniPathways(@PathVariable String id) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(c -> ResponseEntity.ok(c.getMiniPathways()))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{id}/mini-pathway", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateMiniPathway(@PathVariable String id, @RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) {
            sendQuiet(emitter, Map.of("error", "Not found"));
            emitter.complete();
            return emitter;
        }

        Conversation conv = convOpt.get();
        String goal = str(body.get("goal"));
        String modelId = body.get("modelId") != null ? str(body.get("modelId")) : conv.getModelId();

        executor.execute(() -> {
            try {
                sendQuiet(emitter, Map.of("phase", "generating"));
                MiniPathwayService.PathwayGenerationResult result = miniPathwayService.generate(
                    conv, goal, modelId, block -> sendQuiet(emitter, Map.of("block", block)));

                Map<String, Object> pathway = new LinkedHashMap<>();
                pathway.put("id", UUID.randomUUID().toString());
                pathway.put("goal", goal);
                pathway.put("report", mapper.convertValue(result.report, Map.class));
                pathway.put("createdAt", System.currentTimeMillis());
                conv.getMiniPathways().add(pathway);
                conversationService.save(conv);

                sendQuiet(emitter, Map.of("done", true, "pathway", pathway));
                emitter.complete();
            } catch (PathwayGenerationException e) {
                sendQuiet(emitter, Map.of("error", e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                sendQuiet(emitter, Map.of("error", e.getMessage() != null ? e.getMessage() : "Mini pathway generation failed"));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<?> getDetails(@PathVariable String id) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(c -> ResponseEntity.ok(c.getDetails()))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{id}/details", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateDetails(@PathVariable String id, @RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) {
            sendQuiet(emitter, Map.of("error", "Not found"));
            emitter.complete();
            return emitter;
        }
        Conversation conv = convOpt.get();

        DetailRequest request;
        try {
            request = DetailRequest.parse(body);
        } catch (Exception e) {
            sendQuiet(emitter, Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid research request"));
            emitter.complete();
            return emitter;
        }

        executor.execute(() -> {
            try {
                DetailResult result = detailResearchService.research(conv, request,
                    phase -> sendQuiet(emitter, Map.of("phase", phase)));
                conversationService.save(conv);
                sendQuiet(emitter, Map.of("done", true, "details", result));
                emitter.complete();
            } catch (Exception e) {
                sendQuiet(emitter, Map.of("error", e.getMessage() != null ? e.getMessage() : "Research failed"));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/{id}/objectives")
    public ResponseEntity<?> getObjectives(@PathVariable String id) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(c -> ResponseEntity.ok(objectiveService.list(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/objectives/{operation}")
    public ResponseEntity<?> objectiveOperation(@PathVariable String id,
                                                @PathVariable String operation,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) return ResponseEntity.notFound().build();
        Conversation conv = convOpt.get();
        Map<String, Object> b = body != null ? body : Map.of();
        String modelId = b.get("modelId") != null ? str(b.get("modelId")) : conv.getModelId();
        String sessionId = str(b.get("sessionId"));

        try {
            Object result = switch (operation) {
                case "start" -> objectiveService.start(conv, str(b.get("toolId")), modelId);
                case "answer" -> objectiveService.advance(conv, sessionId, asMap(b.get("answer")), modelId);
                case "correct" -> objectiveService.correct(conv, sessionId, asMap(b.get("correction")), modelId);
                case "dismiss" -> {
                    objectiveService.dismiss(conv, sessionId);
                    yield Map.of("ok", true);
                }
                case "handoff" -> objectiveService.handoff(conv, sessionId);
                default -> throw new IllegalArgumentException("Unknown objectives operation: " + operation);
            };
            conversationService.save(conv);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Simulation-only service-action execution, matching the old app's TRUSTED_SERVICE_ACTIONS
     * registry: every entry currently has isConnectedInLab=false, so this always reports the
     * action as not connected rather than pretending to have executed something real.
     */
    @PostMapping("/{id}/actions/{actionId}/execute")
    public ResponseEntity<?> executeAction(@PathVariable String id, @PathVariable String actionId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        if (conversationService.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        TrustedServiceActions.TrustedServiceAction action = TrustedServiceActions.TRUSTED_SERVICE_ACTIONS.get(actionId);
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("executed", false, "message", "Unknown or untrusted action."));
        }
        if (!action.isConnectedInLab) {
            return ResponseEntity.ok(Map.of("executed", false,
                "message", action.title + " is not connected in this preview. Guidance and preparation are available; live execution is not."));
        }
        return ResponseEntity.ok(Map.of("executed", true, "message", "Action completed."));
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
