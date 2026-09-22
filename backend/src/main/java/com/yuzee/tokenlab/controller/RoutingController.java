package com.yuzee.tokenlab.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.MicroTool;
import com.yuzee.tokenlab.model.RouteClaimRequest;
import com.yuzee.tokenlab.model.RoutingDecision;
import com.yuzee.tokenlab.service.BgeGateService;
import com.yuzee.tokenlab.service.CloudflareRouterService;
import com.yuzee.tokenlab.service.RoutingPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server side of the routing split: the actual BGE similarity scoring runs in the Angular Web
 * Worker; this controller only re-validates whatever the worker claims (POST /validate), serves
 * the skill catalogue for the suggestion UI (GET /skills), and offers the optional Cloudflare
 * Workers AI router as a server-side fallback model (POST /llm).
 */
@RestController
@RequestMapping("/api/routing")
public class RoutingController {

    private final RoutingPolicyService routingPolicyService;
    private final BgeGateService bgeGateService;
    private final CloudflareRouterService cloudflareRouterService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RoutingController(RoutingPolicyService routingPolicyService, BgeGateService bgeGateService,
                              CloudflareRouterService cloudflareRouterService) {
        this.routingPolicyService = routingPolicyService;
        this.bgeGateService = bgeGateService;
        this.cloudflareRouterService = cloudflareRouterService;
    }

    /** Real list from config/microtools.json, for the Angular skill-suggestion UI. */
    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        List<Map<String, Object>> tools = routingPolicyService.getEligibleTools().stream()
            .map(RoutingController::toSkillSummary)
            .collect(Collectors.toList());
        return Map.of("tools", tools, "version", bgeGateService.getContentVersion());
    }

    private static Map<String, Object> toSkillSummary(MicroTool tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tool.getId());
        m.put("name", tool.getName());
        m.put("domain", tool.getDomain());
        m.put("purpose", tool.getPurpose());
        return m;
    }

    /**
     * Called by the Angular BGE Web Worker after it computes a similarity score client-side.
     * Never trusts the claim at face value -- see RoutingPolicyService#validateRouteSelection.
     * On a "selected" outcome, also returns the assembled prompt-text instruction for that tool
     * (scopedInstruction(), which itself folds in the learning-depth and skill-input contracts)
     * so the caller has everything it needs to inject into the next Gemini turn.
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody RouteClaimRequest claim) {
        String flow = (claim.getFlow() == null || claim.getFlow().isBlank()) ? "route" : claim.getFlow();
        RoutingDecision decision = routingPolicyService.validateRouteSelection(claim, flow);
        Map<String, Object> body = mapper.convertValue(decision, new TypeReference<Map<String, Object>>() {});
        if ("selected".equals(decision.getStatus())) {
            body.put("instruction", routingPolicyService.scopedInstruction(decision));
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Optional server-side router fallback via Cloudflare Workers AI, used when the user picks
     * "Llama 3.1-8b Fast" as the router model. Returns 503 with a clear message (not a generic
     * failure) when CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN are not configured.
     */
    @PostMapping("/llm")
    public ResponseEntity<Map<String, Object>> llm(@RequestBody Map<String, Object> body) {
        String task = body != null && body.get("task") != null ? body.get("task").toString() : null;
        String text = body != null && body.get("text") != null ? body.get("text").toString() : null;
        CloudflareRouterService.Result result = cloudflareRouterService.classify(task, text);
        return ResponseEntity.status(result.status).body(result.body);
    }
}
