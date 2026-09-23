package com.yuzee.tokenlab.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Plain-JUnit checks for server.ts parity details that need no Gemini call. */
class ApiParitySanityTest {

    @Test
    void cacheHitRatioIsAPercentageLikeServerTs() {
        TokenService tokens = new TokenService(new GeminiModelRegistry());
        tokens.recordChatTurn(0, 3000, 2000, 100, 0, 1000, 2100, 3000);
        assertEquals(33.3, (double) tokens.getSessionStats().get("cacheHitRatio"), 1e-9);
    }

    @Test
    void userEventBlockMatchesJsonStringifyWithTwoSpaceIndent() throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"ui\":{},\"interaction\":{\"question_id\":\"q1\",\"selected_option_ids\":[\"a\",\"b\"],\"fields\":{}}}");
        assertEquals("""
            {
              "ui": {},
              "interaction": {
                "question_id": "q1",
                "selected_option_ids": [
                  "a",
                  "b"
                ],
                "fields": {}
              }
            }""", RequestAssemblerService.stringifyIndented(node, "  ", "").replace("\r\n", "\n"));
    }

    @Test
    void whiteboardGenerateRejectsTranscriptWithoutUserOrAssistantTurns() {
        PathwayWhiteboardService whiteboard = new PathwayWhiteboardService(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            whiteboard.generate(List.of(Map.of("role", "system", "content", "x")), "structured", Map.of(), null));
        assertEquals("No messages", e.getMessage());
    }
}
