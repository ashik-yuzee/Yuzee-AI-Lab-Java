package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of protocol/presentationDefaults.ts.
 * Repairs only presentation details whose meaning is defined by the application:
 * fills an empty-string question_id/question text on handoff forms, and forces any
 * "location" field to the australian_location input type with no options.
 */
public final class PresentationDefaults {

    /**
     * Mutates {@code response} in place (it must be backed by ObjectNode/ArrayNode,
     * i.e. the direct output of an ObjectMapper parse) and returns a human-readable
     * list of the changes that were applied.
     */
    public static List<String> applyPresentationDefaults(JsonNode response, String turnId) {
        List<String> changes = new ArrayList<>();
        if (response == null) return changes;

        JsonNode interactionNode = response.path("interaction");
        if (!(interactionNode instanceof ObjectNode)) return changes;
        ObjectNode q = (ObjectNode) interactionNode;

        String kind = q.path("kind").asText("");
        String inputType = q.path("input_type").asText("");
        JsonNode fieldsNode = q.path("fields");

        if ("handoff".equals(kind) && "fields".equals(inputType)
                && fieldsNode instanceof ArrayNode && fieldsNode.size() > 0) {

            if (q.path("question_id").isTextual() && q.path("question_id").asText().isEmpty()) {
                q.put("question_id", "request-details-" + turnId);
                changes.add("Assigned a question ID to the request form.");
            }
            if (q.path("question").isTextual() && q.path("question").asText().isEmpty()) {
                q.put("question", "Add the missing details for your draft.");
                changes.add("Added the request form heading.");
            }

            for (JsonNode fieldNode : fieldsNode) {
                if (!(fieldNode instanceof ObjectNode)) continue;
                ObjectNode field = (ObjectNode) fieldNode;
                String fieldId = field.path("id").asText("");
                String fieldInputType = field.path("input_type").isTextual() ? field.path("input_type").asText() : "";
                boolean isKnownLocationInputType = "text".equals(fieldInputType)
                        || "australian_location".equals(fieldInputType)
                        || "single_select".equals(fieldInputType);

                if ("location".equals(fieldId) && isKnownLocationInputType) {
                    JsonNode options = field.path("options");
                    if (!"australian_location".equals(fieldInputType) || !options.isArray() || options.size() > 0) {
                        field.put("input_type", "australian_location");
                        field.putArray("options");
                        changes.add("Location is a user-entered text field.");
                    }
                }
            }
        }

        return changes;
    }

    private PresentationDefaults() {
    }
}
