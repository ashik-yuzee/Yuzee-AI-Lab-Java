package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Plain-JUnit sanity check (no Spring context) for the ported protocol/validator.ts logic.
 * Primarily exists to prove the two schema resources actually load off the classpath
 * without throwing, and that a minimal well-formed envelope round-trips as valid.
 */
class ProtocolValidatorSanityTest {

    private static final String MINIMAL_V13 = """
        {
          "schema_version": "1.3",
          "current_mode": "A_CONVERSATION",
          "response_intent": "GENERAL_DELIVERY",
          "content_blocks": [
            {"id":"b1","type":"text","level":"none","variant":"default","title":"","text":"Hello","items":[],"columns":[],"rows":[]}
          ],
          "interaction": {
            "kind":"none","input_type":"none","question_id":"","question":"","options":[],
            "allow_other_input":false,"other_input_label":"","fields":[],"recommended_actions":[]
          },
          "service_trigger": {
            "service_intent_detected":false,"primary_requested_service":"NONE","confidence":"LOW",
            "reason":"","trigger_now":false,"needs_more_clarity":false,"actions":[]
          },
          "rmo_readiness": {
            "readiness":"NOT_READY","ready_to_generate":false,"missing_inputs":[],"verification_required":false
          },
          "state": {
            "active_response_mode":"Standard","effective_response_mode":"Standard","mode_source":"default",
            "safety_override_applied":false,
            "user_confidence":{"score":-1,"band":"unknown","evidence_strength":"none","trend":"unknown","reason_codes":[]},
            "progress":{"explained":false,"failed_attempts":0,"loop_count_same_issue":0,"security_breach_count":0,"active_security_penalty":""}
          },
          "followups": {
            "enabled":false,"cancel_on_user_message":false,"topic_lock":false,"topic_key":"","triggers":[]
          }
        }
        """;

    @Test
    void schemaResourcesLoadWithoutThrowing() {
        assertDoesNotThrow(() -> new ProtocolValidator(new SecurityStateService()));
    }

    @Test
    void minimalEnvelopePassesSchemaAndSemanticValidation() {
        ProtocolValidator validator = new ProtocolValidator(new SecurityStateService());
        ProtocolValidator.ValidationResult result = validator.validateProtocolResponse(MINIMAL_V13, "1.3");
        assertTrue(result.schemaValid, "schema errors: " + result.errors);
        assertTrue(result.semanticValid, "semantic errors: " + result.errors);
        assertTrue(result.isValid());
    }

    @Test
    void malformedJsonIsReportedNotThrown() {
        ProtocolValidator validator = new ProtocolValidator(new SecurityStateService());
        ProtocolValidator.ValidationResult result = validator.validateProtocolResponse("{not json", "1.3");
        assertFalse(result.isValid());
        assertFalse(result.errors.isEmpty());
    }

    @Test
    void rankedSelectOutsideThreeToSixOptionsFailsSemanticCheck() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(MINIMAL_V13);
        var interaction = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("interaction");
        interaction.put("kind", "question");
        interaction.put("input_type", "ranked_select");
        interaction.put("question_id", "q1");
        interaction.put("question", "Rank these");
        var options = interaction.putArray("options");
        for (int i = 0; i < 2; i++) {
            var opt = options.addObject();
            opt.put("id", "o" + i);
            opt.put("label", "Option " + i);
            opt.put("description", "");
            opt.put("value", "o" + i);
        }

        ProtocolValidator validator = new ProtocolValidator(new SecurityStateService());
        ProtocolValidator.ValidationResult result = validator.validateProtocolResponse(mapper.writeValueAsString(node), "1.3");
        assertTrue(result.schemaValid, "schema errors: " + result.errors);
        assertFalse(result.semanticValid);
        assertTrue(result.errors.stream().anyMatch(e -> e.contains("ranked_select")));
    }

    @Test
    void deriveSecurityPenaltyThresholds() {
        SecurityStateService svc = new SecurityStateService();
        assertEquals("", svc.deriveSecurityPenalty(0));
        assertEquals("10_min_timeout", svc.deriveSecurityPenalty(1));
        assertEquals("10_min_timeout", svc.deriveSecurityPenalty(2));
        assertEquals("24_hr_ban", svc.deriveSecurityPenalty(3));
    }

    @Test
    void presentationDefaultsFillsHandoffQuestionIdAndForcesLocationField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(MINIMAL_V13);
        var interaction = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("interaction");
        interaction.put("kind", "handoff");
        interaction.put("input_type", "fields");
        var fields = interaction.putArray("fields");
        var locationField = fields.addObject();
        locationField.put("id", "location");
        locationField.put("label", "Location");
        locationField.put("input_type", "single_select");
        locationField.put("required", true);
        var opts = locationField.putArray("options");
        opts.addObject().put("id", "x");

        List<String> changes = PresentationDefaults.applyPresentationDefaults(node, "turn-1");

        assertFalse(changes.isEmpty());
        assertEquals("request-details-turn-1", interaction.get("question_id").asText());
        assertEquals("australian_location", locationField.get("input_type").asText());
        assertEquals(0, locationField.get("options").size());
    }
}
