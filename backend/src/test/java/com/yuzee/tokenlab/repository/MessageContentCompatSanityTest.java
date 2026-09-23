package com.yuzee.tokenlab.repository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain-JUnit check of the structured-answer chat-bubble label (no database needed). */
class MessageContentCompatSanityTest {

    @Test
    void userEventMapBecomesReadableLabel() {
        assertEquals("Security+ first", JdbcConversationRepository.userEventLabel(Map.of("type", "text_answer", "value", "Security+ first")));
        Map<String, Object> ranked = Map.of("userEvent", Map.of("interaction",
            Map.of("question_id", "q1", "ranked_option_ids", List.of("a", "b"))));
        assertEquals("a → b", JdbcConversationRepository.userEventLabel(ranked));
        Map<String, Object> selected = Map.of("interaction", Map.of("selected_option_ids", List.of("opt_1")));
        assertEquals("Selected option: opt_1", JdbcConversationRepository.userEventLabel(selected));
    }
}
