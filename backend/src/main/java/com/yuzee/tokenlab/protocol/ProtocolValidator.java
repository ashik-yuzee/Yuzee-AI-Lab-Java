package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of src/protocol/validator.ts.
 *
 * Layer 1: JSON Schema conformance (networknt json-schema-validator, draft-07)
 * against response-schema-v1.3.json / response-schema-v1.4.json.
 * Layer 2: Semantic & invariant rules from the Yuzee prompt contract.
 * Layer 3: Trusted service action registry check (see TrustedServiceActions).
 *
 * Also ports validateUserEventAgainstActiveInteraction() and
 * validateInteractionFields() for server-side re-validation of a client-submitted
 * UserEvent answer against the last interaction the server actually sent.
 */
@Service
public class ProtocolValidator {

    private final ObjectMapper objectMapper;
    private final SecurityStateService securityStateService;
    private final JsonSchema schemaV13;
    private final JsonSchema schemaV14;

    public ProtocolValidator(SecurityStateService securityStateService) {
        this.objectMapper = new ObjectMapper();
        this.securityStateService = securityStateService;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schemaV13 = loadSchema(factory, "prompts/response-schema-v1.3.json");
        this.schemaV14 = loadSchema(factory, "prompts/response-schema-v1.4.json");
    }

    private static JsonSchema loadSchema(JsonSchemaFactory factory, String classpathLocation) {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return factory.getSchema(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JSON schema resource: " + classpathLocation, e);
        }
    }

    // -------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------

    public static class ValidationResult {
        public boolean schemaValid;
        public boolean semanticValid;
        public List<String> errors = new ArrayList<>();
        public JsonNode parsed;

        public boolean isValid() {
            return schemaValid && semanticValid;
        }
    }

    public static class UserEventValidationResult {
        public boolean valid;
        public List<String> errors = new ArrayList<>();
    }

    public static class InteractionFieldsResult {
        public boolean valid;
        public List<String> errors = new ArrayList<>();
        public Map<String, String> fieldErrors = new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------
    // Layer 1 + 2 + 3: full protocol response validation
    // -------------------------------------------------------------------

    public ValidationResult validateProtocolResponse(String rawResponseText, String schemaVersion) {
        ValidationResult result = new ValidationResult();

        JsonNode json;
        try {
            json = objectMapper.readTree(rawResponseText);
        } catch (Exception e) {
            result.errors.add("Response is not valid JSON: " + e.getMessage());
            return result;
        }
        if (json == null || !json.isObject()) {
            result.errors.add("Response is not a valid JSON object");
            return result;
        }
        normalizeGeminiEmptyEnumNulls(json);
        result.parsed = json;

        JsonSchema schema;
        String expectedVersion;
        if ("1.4".equals(schemaVersion)) {
            schema = schemaV14;
            expectedVersion = "1.4";
        } else if ("1.3".equals(schemaVersion)) {
            schema = schemaV13;
            expectedVersion = "1.3";
        } else {
            result.errors.add("Unsupported schema_version requested: \"" + schemaVersion + "\"");
            return result;
        }

        List<String> schemaErrors = new ArrayList<>();
        for (ValidationMessage m : schema.validate(json)) {
            schemaErrors.add("[Schema] " + m.getMessage());
        }
        String actualVersion = json.path("schema_version").asText("");
        if (!expectedVersion.equals(actualVersion)) {
            schemaErrors.add("Invalid schema_version: expected \"" + expectedVersion + "\", received \"" + actualVersion + "\"");
        }
        if (!json.path("content_blocks").isArray()) schemaErrors.add("content_blocks must be an array");
        if (!json.path("interaction").isObject()) schemaErrors.add("interaction object is required in envelope");
        if (!json.path("service_trigger").isObject()) schemaErrors.add("service_trigger object is required in envelope");
        if (!json.path("state").isObject()) schemaErrors.add("state object is required in envelope");
        if (!json.path("followups").isObject()) schemaErrors.add("followups object is required in envelope");

        result.schemaValid = schemaErrors.isEmpty();
        if (!result.schemaValid) {
            result.errors.addAll(schemaErrors);
            return result;
        }

        List<String> semanticErrors = new ArrayList<>(validateDisplayInvariants(json));
        List<String> warnings = new ArrayList<>();

        // Rule #10: first content block must be plain text, level=none, title empty.
        JsonNode contentBlocks = json.path("content_blocks");
        if (contentBlocks.isArray() && contentBlocks.size() > 0) {
            JsonNode first = contentBlocks.get(0);
            String type = first.path("type").asText("");
            if (!"text".equals(type)) {
                semanticErrors.add("[Rule #10] First content block must be type=\"text\". Received: \"" + type + "\"");
            }
            String level = first.path("level").asText("");
            if (!level.isEmpty() && !"none".equals(level)) {
                semanticErrors.add("[Rule #10] First content block level must be \"none\". Received: \"" + level + "\"");
            }
            String title = first.path("title").asText("");
            if (!title.trim().isEmpty()) {
                semanticErrors.add("[Rule #10] First content block title must be empty string. Received: \"" + title + "\"");
            }
        } else {
            semanticErrors.add("content_blocks must be a non-empty array");
        }

        // Interaction invariants.
        JsonNode interaction = json.path("interaction");
        if (interaction.isObject()) {
            String kind = interaction.path("kind").asText("");
            String inputType = interaction.path("input_type").asText("");
            JsonNode options = interaction.path("options");
            JsonNode recommendedActions = interaction.path("recommended_actions");

            if ("ranked_select".equals(inputType)) {
                int n = options.isArray() ? options.size() : 0;
                if (n < 3 || n > 6) {
                    semanticErrors.add("[Invariant] ranked_select interaction requires between 3 and 6 options. Received: " + n);
                }
            }
            if (("question".equals(kind) || "handoff".equals(kind)) && recommendedActions.isArray() && recommendedActions.size() > 0) {
                semanticErrors.add("[Invariant] recommended_actions must be empty [] when interaction.kind is \"" + kind + "\". Received: " + recommendedActions.size() + " actions");
            }
            if ("handoff".equals(kind)) {
                JsonNode missingInputs = json.path("rmo_readiness").path("missing_inputs");
                Set<String> fieldIds = new HashSet<>();
                for (JsonNode f : interaction.path("fields")) fieldIds.add(f.path("id").asText(""));
                if (missingInputs.isArray()) {
                    for (JsonNode m : missingInputs) {
                        String mv = m.asText("");
                        if (!fieldIds.contains(mv)) {
                            warnings.add("[Handoff] rmo_readiness.missing_input \"" + mv + "\" not present in interaction.fields");
                        }
                    }
                }
            }
        }

        // Security penalty invariant — breach count is server-authoritative (SecurityStateService);
        // a mismatch here only ever indicates the model's own text disagreed with server state, so
        // it is logged as a warning, not treated as a validation failure (content still renders).
        JsonNode progress = json.path("state").path("progress");
        if (progress.isObject()) {
            String penalty = progress.path("active_security_penalty").asText("");
            int breach = progress.path("security_breach_count").asInt(0);
            String expectedPenalty = securityStateService.deriveSecurityPenalty(breach);
            if (!penalty.equals(expectedPenalty)) {
                warnings.add("[Invariant] active_security_penalty=\"" + penalty + "\" is inconsistent with security_breach_count=" + breach
                        + " (expected \"" + expectedPenalty + "\")");
            }
        }

        // User confidence state invariants.
        JsonNode uc = json.path("state").path("user_confidence");
        if (uc.isObject()) {
            JsonNode scoreNode = uc.path("score");
            String band = uc.path("band").asText("");
            if (scoreNode.isInt()) {
                int score = scoreNode.asInt();
                if (score == -1) {
                    if (!"unknown".equals(band)) {
                        semanticErrors.add("[Confidence] When score is -1, band must be \"unknown\". Received: \"" + band + "\"");
                    }
                    String evidence = uc.path("evidence_strength").asText("");
                    if (!"none".equals(evidence)) {
                        semanticErrors.add("[Confidence] When score is -1, evidence_strength must be \"none\". Received: \"" + evidence + "\"");
                    }
                } else if (score >= 0 && score <= 100) {
                    if (score <= 39 && !"low".equals(band)) {
                        semanticErrors.add("[Confidence] Score " + score + " (0-39) requires band=\"low\". Received: \"" + band + "\"");
                    } else if (score >= 40 && score <= 69 && !"medium".equals(band)) {
                        semanticErrors.add("[Confidence] Score " + score + " (40-69) requires band=\"medium\". Received: \"" + band + "\"");
                    } else if (score >= 70 && !"high".equals(band)) {
                        semanticErrors.add("[Confidence] Score " + score + " (70-100) requires band=\"high\". Received: \"" + band + "\"");
                    }
                } else {
                    semanticErrors.add("[Confidence] score must be -1 or integer 0..100. Received: " + score);
                }
            } else {
                semanticErrors.add("[Confidence] score must be -1 or integer 0..100. Received: " + scoreNode);
            }
        }

        // v1.4-only semantic rules for the new typed block data shapes.
        if ("1.4".equals(schemaVersion)) {
            semanticErrors.addAll(validateV14BlockRules(contentBlocks, warnings));
        }

        // Layer 3: trusted service action registry check (warning only, matches validator.ts).
        JsonNode actions = json.path("service_trigger").path("actions");
        if (actions.isArray()) {
            for (JsonNode act : actions) {
                String actId = act.path("action_id").asText("");
                if (actId.isEmpty()) actId = act.path("id").asText("");
                if (!actId.isEmpty() && !TrustedServiceActions.isTrusted(actId)) {
                    warnings.add("[Security] Service action_id \"" + actId + "\" is untrusted/unregistered in server registry");
                }
            }
        }

        result.semanticValid = semanticErrors.isEmpty();
        result.errors.addAll(semanticErrors);
        for (String w : warnings) result.errors.add("[warning] " + w);
        return result;
    }

    private List<String> validateV14BlockRules(JsonNode contentBlocks, List<String> warnings) {
        List<String> errors = new ArrayList<>();
        if (!contentBlocks.isArray()) return errors;

        Set<String> timelineStatuses = Set.of("completed", "current", "upcoming", "blocked", "paused", "unknown");
        Set<String> chartTypes = Set.of("bar", "line", "donut", "funnel");
        Set<String> numericValueTypes = Set.of("number", "percentage", "rating");

        for (JsonNode block : contentBlocks) {
            String type = block.path("type").asText("");
            String blockId = block.path("id").asText("");
            JsonNode d = block.path("data");

            if ("flow".equals(type)) {
                Set<String> nodeIds = new HashSet<>();
                Set<String> seen = new HashSet<>();
                for (JsonNode n : d.path("nodes")) {
                    String id = n.path("id").asText("");
                    nodeIds.add(id);
                    if (!seen.add(id)) errors.add("[flow] Duplicate node id \"" + id + "\" in block \"" + blockId + "\"");
                }
                for (JsonNode e : d.path("edges")) {
                    String from = e.path("from").asText("");
                    String to = e.path("to").asText("");
                    if (!from.isEmpty() && !nodeIds.contains(from)) warnings.add("[flow] Edge \"from\" id \"" + from + "\" does not reference a known node");
                    if (!to.isEmpty() && !nodeIds.contains(to)) warnings.add("[flow] Edge \"to\" id \"" + to + "\" does not reference a known node");
                }
            }

            if ("pathway_map".equals(type)) {
                Set<String> seenLanes = new HashSet<>();
                boolean dupLane = false;
                for (JsonNode lane : d.path("lanes")) {
                    String laneId = lane.path("id").asText("");
                    if (!seenLanes.add(laneId)) dupLane = true;
                    Set<String> seenSteps = new HashSet<>();
                    boolean dupStep = false;
                    for (JsonNode s : lane.path("steps")) {
                        if (!seenSteps.add(s.path("id").asText(""))) dupStep = true;
                    }
                    if (dupStep) errors.add("[pathway_map] Duplicate step ids in lane \"" + laneId + "\"");
                }
                if (dupLane) errors.add("[pathway_map] Duplicate lane ids in block \"" + blockId + "\"");
            }

            if ("timeline".equals(type)) {
                Set<String> seenMs = new HashSet<>();
                boolean dupMs = false;
                for (JsonNode m : d.path("milestones")) {
                    if (!seenMs.add(m.path("id").asText(""))) dupMs = true;
                    String status = m.path("status").asText("");
                    if (!status.isEmpty() && !timelineStatuses.contains(status)) {
                        errors.add("[timeline] Invalid milestone status \"" + status + "\" in block \"" + blockId + "\"");
                    }
                }
                if (dupMs) errors.add("[timeline] Duplicate milestone ids in block \"" + blockId + "\"");
            }

            if ("scorecard".equals(type)) {
                for (JsonNode m : d.path("metrics")) {
                    String metricId = m.path("id").asText("");
                    String valueType = m.path("value_type").asText("");
                    if (numericValueTypes.contains(valueType)) {
                        if (!m.path("value").isNumber()) {
                            errors.add("[scorecard] Metric \"" + metricId + "\" value must be a number when value_type=\"" + valueType + "\"");
                        }
                        JsonNode max = m.path("max");
                        if (!max.isMissingNode() && !max.isNull() && !max.isNumber()) {
                            errors.add("[scorecard] Metric \"" + metricId + "\" max must be a number");
                        }
                    }
                }
            }

            if ("chart".equals(type)) {
                int catLen = d.path("categories").isArray() ? d.path("categories").size() : 0;
                String chartType = d.path("chart_type").asText("");
                if (!chartType.isEmpty() && !chartTypes.contains(chartType)) {
                    errors.add("[chart] chart_type \"" + chartType + "\" not in allowed set [" + String.join(",", chartTypes) + "]");
                }
                for (JsonNode s : d.path("series")) {
                    String seriesId = s.path("id").asText("");
                    JsonNode values = s.path("values");
                    if (values.isArray()) {
                        if (values.size() != catLen) {
                            errors.add("[chart] Series \"" + seriesId + "\" values length (" + values.size() + ") must match categories length (" + catLen + ")");
                        }
                        for (JsonNode v : values) {
                            if (!v.isNumber()) errors.add("[chart] Series \"" + seriesId + "\" contains non-numeric value: " + v);
                        }
                    }
                }
            }

            if ("progress".equals(type)) {
                int currentCount = 0;
                for (JsonNode s : d.path("stages")) {
                    if ("current".equals(s.path("status").asText(""))) currentCount++;
                }
                if (currentCount > 1) errors.add("[progress] At most one stage may have status=\"current\". Found " + currentCount);
            }
        }
        return errors;
    }

    private List<String> validateDisplayInvariants(JsonNode json) {
        List<String> errors = new ArrayList<>();
        JsonNode inter = json.path("interaction");
        String kind = inter.path("kind").asText("");

        if (!"none".equals(kind)) {
            String questionId = inter.path("question_id").asText("");
            String question = inter.path("question").asText("");
            if (questionId.trim().isEmpty() || question.trim().isEmpty()) {
                errors.add("An active question needs an ID and clear question text.");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode o : inter.path("options")) ids.add(o.path("id").asText(""));
            boolean anyBlank = ids.stream().anyMatch(id -> id == null || id.trim().isEmpty());
            boolean dupIds = new HashSet<>(ids).size() != ids.size();
            if (anyBlank || dupIds) errors.add("Question option IDs must be nonempty and unique.");

            String inputType = inter.path("input_type").asText("");
            if ("single_select".equals(inputType) && (ids.size() < 2 || ids.size() > 5)) {
                errors.add("Single-choice questions need 2–5 options.");
            }
            if ("multi_select".equals(inputType) && (ids.size() < 2 || ids.size() > 6)) {
                errors.add("Multiple-choice questions need 2–6 options.");
            }
            if ("fields".equals(inputType)) {
                List<String> fieldIds = new ArrayList<>();
                for (JsonNode f : inter.path("fields")) fieldIds.add(f.path("id").asText(""));
                if (fieldIds.isEmpty() || new HashSet<>(fieldIds).size() != fieldIds.size()) {
                    errors.add("A form needs nonempty, unique fields.");
                }
                for (JsonNode f : inter.path("fields")) {
                    String fieldId = f.path("id").asText("");
                    String fieldInputType = f.path("input_type").asText("");
                    JsonNode fieldOptions = f.path("options");
                    boolean hasOptions = fieldOptions.isArray() && fieldOptions.size() > 0;
                    if ("location".equals(fieldId) && ("single_select".equals(fieldInputType) || hasOptions)) {
                        errors.add("Location must be a typed field without location choices.");
                    }
                    if ("single_select".equals(fieldInputType) && !hasOptions) {
                        errors.add("A selection field needs choices.");
                    }
                }
            }
            if ("handoff".equals(kind) && !"fields".equals(inputType)) {
                errors.add("A handoff must use fields.");
            }
            if ("question".equals(kind) && !List.of("text", "single_select", "multi_select", "ranked_select").contains(inputType)) {
                errors.add("A question must have an answer control.");
            }
        }
        if ("none".equals(kind) && !"none".equals(inter.path("input_type").asText(""))) {
            errors.add("Inactive questions must not expose answer controls.");
        }

        for (JsonNode block : json.path("content_blocks")) {
            String type = block.path("type").asText("");
            if ("table".equals(type) || "comparison".equals(type)) {
                List<String> keys = new ArrayList<>();
                for (JsonNode c : block.path("columns")) keys.add(c.path("key").asText(""));
                if (keys.isEmpty() || new HashSet<>(keys).size() != keys.size()) {
                    errors.add("Comparison columns must be nonempty and unique.");
                }
                for (JsonNode row : block.path("rows")) {
                    List<String> cells = new ArrayList<>();
                    for (JsonNode c : row.path("cells")) cells.add(c.path("key").asText(""));
                    boolean allKnown = keys.containsAll(cells);
                    if (cells.size() != keys.size() || new HashSet<>(cells).size() != cells.size() || !allKnown) {
                        errors.add("Every comparison row must preserve one cell per column.");
                    }
                }
            }
        }
        return errors;
    }

    // -------------------------------------------------------------------
    // Server-side re-validation of a client-submitted UserEvent answer
    // -------------------------------------------------------------------

    /** Convenience overload for callers holding plain Map payloads (e.g. from ChatRequest.userEvent). */
    public UserEventValidationResult validateUserEventAgainstActiveInteraction(Map<String, Object> userEvent, Map<String, Object> activeInteraction) {
        JsonNode ue = userEvent == null ? null : objectMapper.valueToTree(userEvent);
        JsonNode ai = activeInteraction == null ? null : objectMapper.valueToTree(activeInteraction);
        return validateUserEventAgainstActiveInteraction(ue, ai);
    }

    /**
     * Server-side validation of an incoming UserEvent against the trusted, server-held
     * active interaction. Enforces question_id agreement, option-membership, ranked
     * bounds and field schema. {@code activeInteraction} is the "interaction" object
     * from the last response the server actually sent (never trust a client-echoed copy).
     */
    public UserEventValidationResult validateUserEventAgainstActiveInteraction(JsonNode userEvent, JsonNode activeInteraction) {
        UserEventValidationResult ok = new UserEventValidationResult();
        if (isAbsent(userEvent)) {
            ok.valid = true;
            return ok;
        }

        JsonNode interaction = extractInteraction(userEvent);
        boolean hasSignal = !isAbsent(interaction) && (
                hasNonEmptyText(interaction, "question_id")
                        || hasNonEmptyText(interaction, "action_id")
                        || interaction.has("selected_option_ids")
                        || interaction.has("ranked_option_ids")
                        || interaction.has("fields")
                        || hasNonEmptyText(interaction, "self_input"));
        if (!hasSignal) {
            ok.valid = true;
            return ok;
        }

        for (String name : new String[]{"selected_option_ids", "ranked_option_ids"}) {
            if (interaction.has(name)) {
                JsonNode arr = interaction.get(name);
                boolean bad = !arr.isArray();
                if (!bad) {
                    for (JsonNode v : arr) {
                        if (!v.isTextual()) {
                            bad = true;
                            break;
                        }
                    }
                }
                if (bad) return invalid("Choices must be a list of valid option IDs.");
            }
        }
        if (interaction.has("self_input") && !interaction.get("self_input").isNull() && !interaction.get("self_input").isTextual()) {
            return invalid("Your answer must be text.");
        }

        // Service action click verification.
        if (hasNonEmptyText(interaction, "action_id")) {
            String actionId = interaction.get("action_id").asText();
            List<String> errors = new ArrayList<>();
            if (!TrustedServiceActions.isTrusted(actionId)) {
                errors.add("Action ID \"" + actionId + "\" is not a recognized or trusted service action.");
            }
            UserEventValidationResult r = new UserEventValidationResult();
            r.valid = errors.isEmpty();
            r.errors = errors;
            return r;
        }

        boolean noActiveInteraction = isAbsent(activeInteraction) || "none".equals(activeInteraction.path("kind").asText(""));
        if (noActiveInteraction) {
            List<String> errors = new ArrayList<>();
            if (hasNonEmptyText(interaction, "question_id")) {
                errors.add("No active question interaction on server. Received structured event for question_id \""
                        + interaction.get("question_id").asText() + "\".");
            }
            UserEventValidationResult r = new UserEventValidationResult();
            r.valid = errors.isEmpty();
            r.errors = errors;
            return r;
        }

        List<String> errors = new ArrayList<>();
        String activeQId = hasNonEmptyText(activeInteraction, "question_id")
                ? activeInteraction.get("question_id").asText()
                : activeInteraction.path("id").asText("");
        if (hasNonEmptyText(interaction, "question_id") && !activeQId.isEmpty()
                && !interaction.get("question_id").asText().equals(activeQId)) {
            errors.add("Question ID mismatch: received \"" + interaction.get("question_id").asText()
                    + "\", but active server question is \"" + activeQId + "\".");
        }

        List<JsonNode> trustedOptions = new ArrayList<>();
        JsonNode trustedOptionsNode = activeInteraction.path("options");
        if (trustedOptionsNode.isArray()) trustedOptionsNode.forEach(trustedOptions::add);
        Set<String> trustedOptionIds = new HashSet<>();
        for (JsonNode o : trustedOptions) {
            String id = o.path("id").asText("");
            if (id.isEmpty()) id = o.path("option_id").asText("");
            if (id.isEmpty()) id = o.path("value").asText("");
            trustedOptionIds.add(id);
        }
        String inputType = hasNonEmptyText(activeInteraction, "input_type") ? activeInteraction.get("input_type").asText() : "single_select";

        List<String> selected = jsonArrayToStringList(interaction.path("selected_option_ids"));
        List<String> ranked = jsonArrayToStringList(interaction.path("ranked_option_ids"));
        String selfInput = interaction.path("self_input").isTextual() ? interaction.get("self_input").asText() : null;
        boolean hasSelfInput = selfInput != null && !selfInput.trim().isEmpty();
        boolean allowOtherInput = activeInteraction.path("allow_other_input").asBoolean(false);

        if ("single_select".equals(inputType)) {
            if (selected.size() > 1) errors.add("Single select interaction accepts at most 1 option, received " + selected.size() + ".");
            if (selected.isEmpty() && !hasSelfInput) errors.add("Single select interaction requires exactly one valid option or permitted self_input.");
            if (selected.size() == 1 && hasSelfInput) errors.add("Cannot submit both a selected option and self-input in single select.");
            for (String optId : selected) if (!trustedOptionIds.contains(optId)) errors.add("Selected option ID \"" + optId + "\" is not in the trusted active options list.");
            if (hasSelfInput && !allowOtherInput) errors.add("Self-input provided but allow_other_input is false for this question.");
        }

        if ("multi_select".equals(inputType)) {
            if (selected.isEmpty() && !hasSelfInput) errors.add("Choose at least one option or enter your own answer.");
            if (new HashSet<>(selected).size() != selected.size()) errors.add("Duplicate option IDs submitted in multi-select.");
            for (String optId : selected) if (!trustedOptionIds.contains(optId)) errors.add("Selected option ID \"" + optId + "\" is not in the trusted active options list.");
            if (hasSelfInput && !allowOtherInput) errors.add("Self-input provided but allow_other_input is false for this question.");
        }

        if ("ranked_select".equals(inputType)) {
            if (new HashSet<>(ranked).size() != ranked.size()) errors.add("Duplicate option IDs found in ranked selection.");
            for (String optId : ranked) if (!trustedOptionIds.contains(optId)) errors.add("Ranked option ID \"" + optId + "\" is not in the trusted active options list.");
            int minOptions = Math.min(3, trustedOptions.size());
            int maxOptions = Math.min(6, trustedOptions.size());
            if (ranked.size() < minOptions || ranked.size() > maxOptions) {
                errors.add("Ranked options count (" + ranked.size() + ") must be between " + minOptions + " and " + maxOptions + ".");
            }
        }

        if ("fields".equals(inputType) || "handoff".equals(activeInteraction.path("kind").asText(""))) {
            InteractionFieldsResult fr = validateInteractionFields(activeInteraction, interaction.path("fields"));
            errors.addAll(fr.errors);
        }
        if ("text".equals(inputType) && !hasSelfInput) {
            errors.add("Enter your answer before continuing.");
        }

        UserEventValidationResult r = new UserEventValidationResult();
        r.valid = errors.isEmpty();
        r.errors = errors;
        return r;
    }

    /** Convenience overload for callers holding plain Map payloads. */
    public InteractionFieldsResult validateInteractionFields(Map<String, Object> active, Map<String, Object> values) {
        JsonNode a = active == null ? null : objectMapper.valueToTree(active);
        JsonNode v = values == null ? null : objectMapper.valueToTree(values);
        return validateInteractionFields(a, v);
    }

    /** Validates a submitted fields/handoff answer (values) against the active interaction's field schema. */
    public InteractionFieldsResult validateInteractionFields(JsonNode active, JsonNode values) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        List<JsonNode> fieldList = new ArrayList<>();
        if (active != null) {
            JsonNode fields = active.path("fields");
            if (fields.isArray()) fields.forEach(fieldList::add);
        }

        ObjectNode submitted;
        if (isAbsent(values) || !values.isObject()) {
            fieldErrors.put("form", "Enter the requested details.");
            submitted = objectMapper.createObjectNode();
        } else {
            submitted = ((ObjectNode) values);
        }

        Set<String> fieldIds = new HashSet<>();
        for (JsonNode f : fieldList) fieldIds.add(f.path("id").asText(""));

        Iterator<String> keyIt = submitted.fieldNames();
        while (keyIt.hasNext()) {
            String key = keyIt.next();
            if (!fieldIds.contains(key)) fieldErrors.put(key, "This field is not part of the current question.");
        }

        for (JsonNode field : fieldList) {
            String fieldId = field.path("id").asText("");
            String label = field.path("label").asText("");
            String inputType = field.path("input_type").asText("");
            boolean required = field.path("required").asBoolean(false);
            JsonNode valueNode = submitted.get(fieldId);

            if (valueNode != null && !valueNode.isNull() && !valueNode.isTextual()) {
                fieldErrors.put(fieldId, label + " must be text.");
                continue;
            }
            String text = (valueNode != null && valueNode.isTextual()) ? valueNode.asText().trim() : "";

            if (required && text.isEmpty()) {
                fieldErrors.put(fieldId, "Enter " + ("location".equals(fieldId) ? "a city, suburb or postcode" : label.toLowerCase()) + ".");
            } else if (text.length() > 500) {
                fieldErrors.put(fieldId, "Keep this answer under 500 characters.");
            } else if (!text.isEmpty() && "single_select".equals(inputType)) {
                boolean matches = false;
                for (JsonNode o : field.path("options")) {
                    String val = o.path("value").asText("");
                    if (val.isEmpty()) val = o.path("label").asText("");
                    if (text.equals(val)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) fieldErrors.put(fieldId, "Choose one of the listed options for " + label.toLowerCase() + ".");
            }
        }

        InteractionFieldsResult result = new InteractionFieldsResult();
        result.fieldErrors = fieldErrors;
        result.errors = new ArrayList<>(fieldErrors.values());
        result.valid = fieldErrors.isEmpty();
        return result;
    }

    // -------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------

    private UserEventValidationResult invalid(String message) {
        UserEventValidationResult r = new UserEventValidationResult();
        r.valid = false;
        r.errors = List.of(message);
        return r;
    }

    /**
     * Fields whose schema enum includes "" as a valid member (see
     * RequestAssemblerService#sanitizeSchemaForGemini — any such enum gets marked
     * {@code nullable:true} in the schema sent to Gemini, since Gemini's structured-output mode
     * cannot represent an empty-string enum value directly). Gemini then legitimately returns
     * JSON {@code null} to mean "no value" for these — the response schema itself has no such
     * allowance, so without this normalization a well-formed, on-topic answer with (for example)
     * no active security penalty fails schema validation purely on this representational quirk.
     */
    private static final Set<String> EMPTY_ENUM_NULLABLE_FIELDS =
        Set.of("active_security_penalty", "status", "rmo_type");

    private static void normalizeGeminiEmptyEnumNulls(JsonNode node) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> names = obj.fieldNames();
            List<String> toBlank = new ArrayList<>();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode value = obj.get(name);
                if (value.isNull() && EMPTY_ENUM_NULLABLE_FIELDS.contains(name)) {
                    toBlank.add(name);
                } else {
                    normalizeGeminiEmptyEnumNulls(value);
                }
            }
            for (String name : toBlank) obj.put(name, "");
        } else if (node.isArray()) {
            for (JsonNode child : node) normalizeGeminiEmptyEnumNulls(child);
        }
    }

    private static boolean isAbsent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private static boolean hasNonEmptyText(JsonNode n, String field) {
        return n != null && n.has(field) && n.get(field).isTextual() && !n.get(field).asText().isEmpty();
    }

    private static List<String> jsonArrayToStringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) out.add(v.asText(""));
        return out;
    }

    /**
     * Mirrors validator.ts's fallback extraction of a structured interaction payload from a
     * UserEvent: prefers userEvent.interaction, then userEvent.userEvent.interaction, then
     * synthesizes one from legacy flat fields (type/option_id/ranked_ids/value/...).
     */
    private JsonNode extractInteraction(JsonNode userEvent) {
        if (userEvent.has("interaction") && userEvent.get("interaction").isObject()) {
            return userEvent.get("interaction");
        }
        JsonNode nested = userEvent.path("userEvent");
        if (nested.isObject() && nested.has("interaction") && nested.get("interaction").isObject()) {
            return nested.get("interaction");
        }
        if (!hasNonEmptyText(userEvent, "type")) {
            return null;
        }

        ObjectNode synthetic = objectMapper.createObjectNode();
        synthetic.put("question_id", userEvent.path("interaction_id").asText("active_question"));
        if (userEvent.has("option_id")) {
            ArrayNode arr = synthetic.putArray("selected_option_ids");
            arr.add(userEvent.path("option_id").asText(""));
        } else if (userEvent.has("selected_option_ids")) {
            synthetic.set("selected_option_ids", userEvent.get("selected_option_ids"));
        }
        if (userEvent.has("ranked_ids")) {
            synthetic.set("ranked_option_ids", userEvent.get("ranked_ids"));
        } else if (userEvent.has("ranked_option_ids")) {
            synthetic.set("ranked_option_ids", userEvent.get("ranked_option_ids"));
        }
        if (userEvent.has("fields")) {
            synthetic.set("fields", userEvent.get("fields"));
        }
        if (userEvent.has("value")) {
            synthetic.put("self_input", userEvent.path("value").asText(""));
        } else if (userEvent.has("self_input")) {
            synthetic.put("self_input", userEvent.path("self_input").asText(""));
        }
        if (userEvent.has("action_id")) {
            synthetic.put("action_id", userEvent.path("action_id").asText(""));
        }
        return synthetic;
    }
}
