package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.ChatRequest;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.model.DetailRequest;
import com.yuzee.tokenlab.model.DetailResult;
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
import com.yuzee.tokenlab.service.SharedSettingsService;
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

    /** Fires the 120s AbortController timeout of the mini-pathway/details SSE routes. */
    private static final java.util.concurrent.ScheduledExecutorService ROUTE_TIMERS =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "route-timeout"); t.setDaemon(true); return t; });

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
    private final SharedSettingsService sharedSettingsService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();


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
                          WarehouseService warehouseService,
                          SharedSettingsService sharedSettingsService) {
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
        this.sharedSettingsService = sharedSettingsService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.yuzee.tokenlab.service.ChatTurnService chatTurnService;

    /** server.ts POST /api/conversations/:id/messages -- see ChatTurnService (JSON errors before the stream, named SSE events after). */
    @PostMapping("/{id}/messages")
    public org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter chat(
            @PathVariable String id, @RequestBody ChatRequest request, HttpServletRequest httpRequest,
            jakarta.servlet.http.HttpServletResponse httpResponse) throws IOException {
        return chatTurnService.handle(id, request, httpRequest, httpResponse);
    }

    /** server.ts SSE route plumbing: `data: {json}\n\n` frames; the AbortController trips on client close or the 120s timer. */
    private final class RouteStream {
        // ponytail: Spring's own timeout is only a safety net; the route's 120s timer is what aborts the work.
        final SseEmitter emitter = new SseEmitter(180_000L);
        final java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.ScheduledFuture<?> timer =
            ROUTE_TIMERS.schedule(() -> aborted.set(true), 120, java.util.concurrent.TimeUnit.SECONDS);
        private volatile boolean ended;

        RouteStream(jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-cache");
            emitter.onCompletion(() -> { if (!ended) aborted.set(true); });
            emitter.onTimeout(() -> aborted.set(true));
            emitter.onError(e -> aborted.set(true));
        }

        synchronized void emit(Object value) {
            try {
                emitter.send(SseEmitter.event().data(" " + mapper.writeValueAsString(value)));
            } catch (Exception e) {
                aborted.set(true); // client went away
            }
        }

        void end() {
            ended = true;
            timer.cancel(false);
            emitter.complete();
        }
    }

    /** res.status(status).json({error}) before any stream starts. */
    private SseEmitter routeError(jakarta.servlet.http.HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", error)));
        return null;
    }

    /** An SSE event object with server.ts's key order. */
    private static Map<String, Object> sseEvent(Object... keyValues) {
        Map<String, Object> event = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) event.put((String) keyValues[i], keyValues[i + 1]);
        return event;
    }

    @GetMapping("/{id}/mini-pathway")
    public ResponseEntity<?> getMiniPathways(@PathVariable String id) {
        Optional<Conversation> conv = conversationService.findById(id);
        if (conv.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Conversation not found."));
        try {
            return ResponseEntity.ok(miniPathwayService.list(conv.get()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Saved mini pathways could not be loaded."));
        }
    }

    @PostMapping("/{id}/mini-pathway")
    public SseEmitter generateMiniPathway(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                          jakarta.servlet.http.HttpServletResponse response) throws IOException {
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) return routeError(response, 404, "Conversation not found.");
        RouteStream sse = new RouteStream(response);
        executor.execute(() -> {
            try {
                Map<String, Object> result = miniPathwayService.generate(convOpt.get(), body, sse.aborted,
                    stage -> sse.emit(sseEvent("type", "progress", "stage", stage)), sse::emit);
                sse.emit(sseEvent("type", "result", "result", result));
            } catch (PathwayGenerationException e) {
                sse.emit(sseEvent("type", "error", "error", e.getMessage(), "status", e.getStatus()));
            } catch (Exception e) {
                sse.emit(sseEvent("type", "error", "error", "The mini pathway could not be prepared.", "status", 500));
            } finally {
                sse.end();
            }
        });
        return sse.emitter;
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<?> getDetails(@PathVariable String id) {
        Optional<Conversation> conv = conversationService.findById(id);
        if (conv.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Conversation not found."));
        try {
            return ResponseEntity.ok(detailResearchService.list(conv.get()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Saved details could not be loaded."));
        }
    }

    /** server.ts: only these research errors are shown as-is; raw provider errors can contain request credentials. */
    private static final java.util.regex.Pattern SAFE_RESEARCH_ERROR = java.util.regex.Pattern.compile(
        "^(The detail answer|The answer|The partial answer|An answer without|The search result|Research is not connected)");

    @PostMapping("/{id}/details")
    public SseEmitter generateDetails(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                      jakarta.servlet.http.HttpServletResponse response) throws IOException {
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) return routeError(response, 404, "Conversation not found.");
        Conversation conv = convOpt.get();
        DetailRequest request;
        try {
            request = DetailRequest.parse(body);
        } catch (IllegalArgumentException e) {
            return routeError(response, 400, e.getMessage());
        }
        ChatMessage parent = conv.getMessages().stream()
            .filter(m -> request.getParentMessageId().equals(m.getId()) && "assistant".equals(m.getRole()))
            .findFirst().orElse(null);
        if (parent == null || parent.getParsedResponse() == null
            || !protocolValidator.validateProtocolResponse(mapper.writeValueAsString(parent.getParsedResponse()), "1.3").isValid()) {
            return routeError(response, 400, "Choose a completed, valid answer to explore.");
        }
        if (!detailResearchService.tryStart(id)) return routeError(response, 409, "A search is already running in this conversation.");
        RouteStream sse = new RouteStream(response);
        executor.execute(() -> {
            try {
                Map<String, Object> result = detailResearchService.research(conv, request, sse.aborted,
                    stage -> sse.emit(sseEvent("type", "progress", "stage", stage)));
                sse.emit(sseEvent("type", "result", "result", result));
            } catch (Exception error) {
                String message = error.getMessage() == null ? "" : error.getMessage();
                String safe = SAFE_RESEARCH_ERROR.matcher(message).find() ? message
                    : sse.aborted.get() ? "The search stopped before it finished. Your question has been kept."
                    : "We could not finish this search. Your question has been kept. Please try again.";
                sse.emit(sseEvent("type", "error", "error", safe));
            } finally {
                detailResearchService.finish(id);
                sse.end();
            }
        });
        return sse.emitter;
    }

    @GetMapping("/{id}/objectives")
    public ResponseEntity<?> getObjectives(@PathVariable String id) {
        if (conversationService.findById(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Conversation not found."));
        try {
            return ResponseEntity.ok(objectiveService.list(id));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Workspaces could not be loaded."));
        }
    }

    /** server.ts POST /api/conversations/:id/objectives/:operation. Workspaces persist in ObjectiveService's file store. */
    @PostMapping("/{id}/objectives/{operation}")
    public ResponseEntity<?> objectiveOperation(@PathVariable String id,
                                                @PathVariable String operation,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Optional<Conversation> convOpt = conversationService.findById(id);
        if (convOpt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Conversation not found."));
        Conversation conv = convOpt.get();
        Map<String, Object> b = body != null ? body : Map.of();
        String sessionId = str(b.get("sessionId"));
        // server.ts: const timer=setTimeout(()=>abort.abort(),100000) -- the signal reaches every model and warehouse call.
        // ponytail: no res.on('close') abort -- the servlet cannot see a disconnect before it writes the reply
        java.util.concurrent.CompletableFuture<Object> abort = new java.util.concurrent.CompletableFuture<>();
        abort.completeOnTimeout(null, 100, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Object result = objectiveService.withRouteSignal(abort, () -> switch (operation) {
                case "select" -> objectiveService.select(conv, b, () -> conversationService.findById(id)
                    .map(c -> c.getMessages() != null && !c.getMessages().isEmpty()
                        && Objects.equals(c.getMessages().get(c.getMessages().size() - 1).getId(), b.get("sourceMessageId")))
                    .orElse(false));
                case "start" -> objectiveService.start(conv, b);
                case "dismiss" -> objectiveService.dismiss(id, b.get("sourceMessageId"));
                case "course-focus" -> objectiveService.focusCourses(conv, sessionId, b);
                case "correct" -> objectiveService.correct(conv, sessionId, b);
                case "answer" -> objectiveService.advance(conv, sessionId, b);
                default -> null;
            });
            if (result == null) return ResponseEntity.status(404).body(Map.of("error", "Unknown workspace action."));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(422).body(Map.of("error",
                e.getMessage() != null ? e.getMessage() : "Workspace request failed."));
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
        if (conversationService.findById(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
        TrustedServiceActions.TrustedServiceAction action = TrustedServiceActions.TRUSTED_SERVICE_ACTIONS.get(actionId);
        if (action == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("executed", false);
            err.put("error", "Action \"" + actionId + "\" is not registered in the trusted server action registry.");
            return ResponseEntity.badRequest().body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("executed", false);
        out.put("connected", false);
        out.put("actionId", actionId);
        if (!action.isConnectedInLab) {
            out.put("title", action.title);
            out.put("message", "This action is not connected in Token Lab. (Reference validation only; no live external service called).");
            return ResponseEntity.ok(out);
        }
        // Connectivity metadata alone is not evidence of an executed provider operation.
        out.put("message", "Request submission is not connected. Nothing has been sent.");
        return ResponseEntity.status(501).body(out);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
