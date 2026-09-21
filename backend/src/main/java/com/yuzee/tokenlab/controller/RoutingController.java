package com.yuzee.tokenlab.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routing")
public class RoutingController {

    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        // Returns the micro-tool routing info — Angular uses this for skill suggestions
        return Map.of(
            "tools", List.of(
                Map.of("id", "COURSE_011", "label", "Certificate III courses", "category", "course"),
                Map.of("id", "COURSE_012", "label", "Certificate IV courses", "category", "course"),
                Map.of("id", "CORE_001", "label", "Core career guidance", "category", "core")
            ),
            "version", "bge-skills-v2"
        );
    }

    @PostMapping("/embedding")
    public ResponseEntity<?> embedding(@RequestBody Map<String, Object> body) {
        // Placeholder — BGE embedding runs client-side in the Angular app
        return ResponseEntity.ok(Map.of("message", "Embedding runs client-side"));
    }
}
