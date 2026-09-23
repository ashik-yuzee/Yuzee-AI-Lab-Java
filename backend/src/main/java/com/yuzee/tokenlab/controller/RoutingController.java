package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.service.CloudflareRouterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Port of the original's POST /api/routing/llm: the Cloudflare Workers AI router used when the
 * user picks it as the router model. (BGE scoring runs in the Angular Web Worker and the server
 * re-validates the client's route inside POST /messages, as in the original.)
 */
@RestController
@RequestMapping("/api/routing")
public class RoutingController {

    private final CloudflareRouterService cloudflareRouterService;

    public RoutingController(CloudflareRouterService cloudflareRouterService) {
        this.cloudflareRouterService = cloudflareRouterService;
    }

    /**
     * Optional server-side router fallback via Cloudflare Workers AI, used when the user picks
     * "Llama 3.1-8b Fast" as the router model. Returns 503 with a clear message (not a generic
     * failure) when CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN are not configured.
     */
    @PostMapping("/llm")
    public ResponseEntity<Map<String, Object>> llm(@RequestBody(required = false) Map<String, Object> body) {
        String task = body != null && body.get("task") instanceof String t ? t : null;
        String text = body != null && body.get("text") instanceof String t ? t : null;
        CloudflareRouterService.Result result = cloudflareRouterService.classify(task, text);
        return ResponseEntity.status(result.status).body(result.body);
    }
}
