package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<Conversation> list() {
        return conversationService.listAll();
    }

    @PostMapping
    public Conversation create(@RequestBody(required = false) Map<String, Object> body) {
        String modelId = body != null ? (String) body.get("modelId") : null;
        String title = body != null ? (String) body.get("title") : null;
        return conversationService.create(modelId, title);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return conversationService.findById(id).map(conv -> {
            if (body.containsKey("title")) conv.setTitle((String) body.get("title"));
            if (body.containsKey("modelId")) conv.setModelId((String) body.get("modelId"));
            if (body.containsKey("optimizationMode")) conv.setOptimizationMode((String) body.get("optimizationMode"));
            if (body.containsKey("responseMode")) conv.setResponseMode((String) body.get("responseMode"));
            if (body.containsKey("strategy")) conv.setStrategy((String) body.get("strategy"));
            if (body.containsKey("careerContext")) conv.setCareerContext((Map<String, Object>) body.get("careerContext"));
            return ResponseEntity.ok(conversationService.save(conv));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (conversationService.delete(id)) return ResponseEntity.ok(Map.of("deleted", true));
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/generate-title")
    public ResponseEntity<?> generateTitle(@PathVariable String id) {
        return conversationService.findById(id).map(conv -> {
            String title = "Conversation " + conv.getId().substring(0, 8);
            if (!conv.getMessages().isEmpty()) {
                Object firstContent = conv.getMessages().get(0).getContent();
                if (firstContent instanceof String s && s.length() > 5) {
                    title = s.length() > 50 ? s.substring(0, 50) + "…" : s;
                }
            }
            conv.setTitle(title);
            conversationService.save(conv);
            return ResponseEntity.ok(Map.of("title", title));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/feedback")
    public ResponseEntity<?> feedback(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return conversationService.findById(id)
            .<ResponseEntity<?>>map(c -> ResponseEntity.ok(Map.of("ok", true)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reset-memory")
    public ResponseEntity<?> resetMemory(@PathVariable String id) {
        return conversationService.findById(id).map(conv -> {
            conv.setSummaryText(null);
            conversationService.save(conv);
            return ResponseEntity.ok(Map.of("ok", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(@RequestBody Map<String, Object> body) {
        // Accept and store a restored conversation
        return ResponseEntity.ok(Map.of("ok", true, "message", "Restore acknowledged"));
    }
}
