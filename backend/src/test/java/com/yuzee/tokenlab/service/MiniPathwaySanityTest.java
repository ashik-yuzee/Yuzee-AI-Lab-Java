package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.protocol.ProtocolValidator;
import com.yuzee.tokenlab.protocol.SecurityStateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context) for the mini-pathway port: the incremental
 * block-stream parser (streamBlocks.ts), the policy gate (policy.ts) and the structural report
 * review (reportReview.ts). Mirrors the style of PortedReviewServicesSanityTest.
 */
class MiniPathwaySanityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -- PathwayBlockStreamParser --------------------------------------------------------

    private static final String VALID_BLOCK =
        "{\"id\":\"overview\",\"type\":\"text\",\"level\":\"none\",\"variant\":\"default\",\"title\":\"\","
            + "\"text\":\"Hi\",\"items\":[],\"columns\":[],\"rows\":[]}";

    @Test
    void emitsACompleteBlockAsSoonAsItCloses() {
        PathwayBlockStreamParser parser = new PathwayBlockStreamParser();
        String json = "{\"content_blocks\":[" + VALID_BLOCK + "]}";
        List<JsonNode> blocks = parser.push(json);
        assertEquals(1, blocks.size());
        assertEquals("overview", blocks.get(0).path("id").asText());
    }

    @Test
    void neverEmitsABlockCutOffMidObject() {
        PathwayBlockStreamParser parser = new PathwayBlockStreamParser();
        // Stream stops mid-object: no closing brace for the block, and the array/root never close.
        String cutOff = "{\"content_blocks\":[{\"id\":\"overview\",\"type\":\"text\",\"level\":\"none\","
            + "\"variant\":\"default\",\"title\":\"\",\"text\":\"partial sentence that keeps going";
        List<JsonNode> blocks = parser.push(cutOff);
        assertTrue(blocks.isEmpty(), "a block cut off before its closing brace must never be emitted");

        // Feeding the closing brace afterwards (as if the rest of the stream still arrived) does
        // complete it -- proving the parser withheld it rather than silently dropping/corrupting it.
        List<JsonNode> more = parser.push("\",\"items\":[],\"columns\":[],\"rows\":[]}]}");
        assertEquals(1, more.size());
        assertEquals("overview", more.get(0).path("id").asText());
    }

    @Test
    void splitsAcrossChunkBoundariesAndDoesNotDuplicateIds() {
        PathwayBlockStreamParser parser = new PathwayBlockStreamParser();
        String whole = "{\"content_blocks\":[" + VALID_BLOCK + "," + VALID_BLOCK.replace("overview", "next") + "]}";
        List<JsonNode> all = new java.util.ArrayList<>();
        for (int i = 0; i < whole.length(); i += 7) {
            all.addAll(parser.push(whole.substring(i, Math.min(i + 7, whole.length()))));
        }
        assertEquals(2, all.size());
        // Re-pushing the same (already-seen) id must not re-emit it.
        List<JsonNode> again = parser.push("{\"content_blocks\":[" + VALID_BLOCK + "]}");
        assertTrue(again.isEmpty());
    }

    @Test
    void rejectsABlockThatFailsTheSchema() {
        PathwayBlockStreamParser parser = new PathwayBlockStreamParser();
        // "type" is not one of the allowed enum values.
        String bad = "{\"content_blocks\":[{\"id\":\"x\",\"type\":\"not-a-type\",\"level\":\"none\","
            + "\"variant\":\"default\",\"title\":\"\",\"text\":\"\",\"items\":[],\"columns\":[],\"rows\":[]}]}";
        assertTrue(parser.push(bad).isEmpty());
    }

    // -- PathwayPolicyService -------------------------------------------------------------

    private final PathwayPolicyService policy =
        new PathwayPolicyService(new SkillSuggestionService(
            new RoutingPolicyService(new BgeGateService()), new BgeGateService(),
            new ProtocolValidator(new SecurityStateService())));

    private static final String HINT = "{\"status\":\"selected\",\"score\":0.6,\"margin\":0.1}";
    private static final String NO_HINT = "{\"status\":\"abstained\",\"reason\":\"not-pathway\"}";
    private static final String LOW = "{\"current_mode\":\"%s\",\"state\":{\"user_confidence\":{\"score\":%s,\"band\":\"low\",\"evidence_strength\":\"some\"}}}";

    private Object action(String mode, String score, String userText, String hint, boolean helped) throws Exception {
        return policy.decide(mapper.readTree(LOW.formatted(mode, score)), userText, mapper.readTree(hint), helped).get("action");
    }

    @Test
    void decisionGateMatchesPolicyTs() throws Exception {
        assertEquals("automatic", action("A_CONVERSATION", "20", "I'm not sure what career to pick", HINT, false));
        assertEquals("offer", action("A_CONVERSATION", "20", "still not sure", HINT, true));
        assertEquals("none", action("A_CONVERSATION", "20", "what's the weather", NO_HINT, false));
        assertEquals("none", action("A_CONVERSATION", "20", "no pathway thanks", HINT, false));
        assertEquals("none", action("S_SERVICE_HANDOFF", "10", "help me decide", HINT, false));
        assertEquals("offer", action("A_CONVERSATION", "null", "help me choose a career", HINT, false));
    }

    @Test
    void originalHintContractDrivesTheDecision() throws Exception {
        JsonNode low = mapper.readTree("{\"state\":{\"user_confidence\":{\"score\":20,\"band\":\"low\",\"evidence_strength\":\"some\"}}}");
        JsonNode bge = mapper.readTree("{\"status\":\"selected\",\"score\":0.8,\"margin\":0.1,\"modelId\":\"Xenova/bge-small-en-v1.5\",\"profileVersion\":\"bge-pathway-v2\"}");
        JsonNode weak = mapper.readTree("{\"status\":\"selected\",\"score\":0.6,\"margin\":0.1,\"modelId\":\"Xenova/bge-small-en-v1.5\",\"profileVersion\":\"bge-pathway-v2\"}");
        assertEquals(Map.of("action", "automatic", "reason", "low-decision-confidence", "score", 20), policy.decide(low, "help me choose", bge, false));
        assertEquals("already-helped", policy.decide(low, "help me choose", bge, true).get("reason"));
        assertEquals("no-relevant-match", policy.decide(low, "help me choose", weak, false).get("reason"));
        assertTrue(MiniPathwayService.outdatedHelpClaim("HECS repayments start at 1% of income"));
        assertFalse(MiniPathwayService.outdatedHelpClaim("HECS repayments no longer start at 1% of income"));
    }

    // -- MiniPathwayService structural/content review (reportReview.ts port) -------------

    @Test
    void flagsMissingRequiredSections() throws Exception {
        JsonNode response = mapper.readTree("{\"content_blocks\":[{\"id\":\"overview\",\"type\":\"text\",\"text\":\"Hi\"}]}");
        List<String> issues = MiniPathwayService.reviewMiniPathwayReport(response);
        assertTrue(issues.stream().anyMatch(i -> i.contains("route-summary")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("route-comparison")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("experience-playbook")));
    }

    @Test
    void flagsUnsourcedMoneyAndGuarantees() throws Exception {
        JsonNode response = mapper.readTree(mapper.writeValueAsString(Map.of(
            "content_blocks", List.of(Map.of("id", "overview", "type", "text",
                "text", "This costs $500 and comes with a guaranteed job.")))));
        List<String> issues = MiniPathwayService.reviewMiniPathwayReport(response);
        assertTrue(issues.stream().anyMatch(i -> i.contains("monetary")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("guarantees")));
    }

    @Test
    void acceptsAFullyCompleteReport() throws Exception {
        JsonNode response = mapper.readTree("""
            {"content_blocks":[
              {"id":"overview","type":"text","text":"An orientation to the goal."},
              {"id":"route-summary","type":"table","rows":[{"id":"core","cells":[]}]},
              {"id":"core-timeline","type":"steps","items":[{"id":"s1","title":"Start"}]},
              {"id":"core-considerations","type":"text","text":"Some risks to weigh."},
              {"id":"route-comparison","type":"comparison","rows":[{"id":"r1","cells":[]}]},
              {"id":"experience-playbook","type":"steps","items":[
                {"id":"1","title":"a"},{"id":"2","title":"b"},{"id":"3","title":"c"},
                {"id":"4","title":"d"},{"id":"5","title":"e"},{"id":"6","title":"f"}
              ]}
            ]}
            """);
        assertTrue(MiniPathwayService.reviewMiniPathwayReport(response).isEmpty());
    }

    @Test
    void invariantsRejectAnActiveQuestionOrServiceAction() throws Exception {
        JsonNode blocked = mapper.readTree("""
            {"current_mode":"A_CONVERSATION","interaction":{"kind":"question"},
             "service_trigger":{"trigger_now":false,"actions":[]},
             "followups":{"enabled":false,"triggers":[]},
             "rmo_readiness":{"ready_to_generate":false},
             "content_blocks":[{"id":"overview","type":"text","text":"hi"}]}
            """);
        assertFalse(MiniPathwayService.validateMiniPathwayInvariants(blocked).isEmpty());

        JsonNode clean = mapper.readTree("""
            {"current_mode":"B_DELIVERY","interaction":{"kind":"none","recommended_actions":[]},
             "service_trigger":{"trigger_now":false,"actions":[]},
             "followups":{"enabled":false,"triggers":[]},
             "rmo_readiness":{"ready_to_generate":false},
             "content_blocks":[{"id":"overview","type":"text","text":"hi"}]}
            """);
        assertTrue(MiniPathwayService.validateMiniPathwayInvariants(clean).isEmpty());
    }

    // -- PathwayContextService ------------------------------------------------------------

    private final PathwayContextService contextService = new PathwayContextService();

    @Test
    void getPathwayContextReturnsNullWithNoBlocks() {
        assertNull(contextService.getPathwayContext(Map.of("response", Map.of("content_blocks", List.of())), "query", 5000));
    }

    @Test
    void getPathwayContextAlwaysIncludesCoreBlocks() {
        Map<String, Object> savedPathway = Map.of("response", Map.of("content_blocks", List.of(
            Map.of("id", "overview", "type", "text", "title", "", "text", "Orientation text about nursing."),
            Map.of("id", "unrelated-block", "type", "text", "title", "", "text", "Something about pottery classes.")
        )));
        String context = contextService.getPathwayContext(savedPathway, "nursing career", 5000);
        assertTrue(context.contains("overview"));
    }
}
