package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exact port of src/protocol/validator.ts.
 *
 * Layer 1: Schema conformance against response-schema-v1.3.json / v1.4.json, evaluated with an
 * Ajv({allErrors:true, strict:false})-equivalent walker (same keyword order, instancePath and
 * messages) so schemaErrors strings match the original byte for byte.
 * Layer 2: Semantic & invariant rules. Layer 3: trusted service action registry check.
 * Also ports validateUserEventAgainstActiveInteraction() and validateInteractionFields().
 * JS truthiness / String() / trim() semantics are reproduced via the js* helpers at the bottom.
 */
@Service
public class ProtocolValidator {

    private final ObjectMapper objectMapper;
    private final JsonNode schemaV13;
    private final JsonNode schemaV14;

    public ProtocolValidator(SecurityStateService securityStateService) {
        // JSON.parse rejects trailing content; Jackson ignores it unless told otherwise.
        this.objectMapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.schemaV13 = loadSchema("prompts/response-schema-v1.3.json");
        this.schemaV14 = loadSchema("prompts/response-schema-v1.4.json");
    }

    private JsonNode loadSchema(String classpathLocation) {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JSON schema resource: " + classpathLocation, e);
        }
    }

    // -------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------

    /** ExtendedProtocolValidationResult. Serialises with the original's keys and key order. */
    @JsonPropertyOrder({"jsonParsed", "schemaValid", "semanticValid", "protocolAccepted", "schemaErrors", "semanticErrors", "errors", "warnings"})
    public static class ValidationResult {
        public boolean jsonParsed;
        public boolean schemaValid;
        public boolean semanticValid;
        public boolean protocolAccepted;
        public List<String> schemaErrors = new ArrayList<>();
        public List<String> semanticErrors = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        /** server.ts Object.assign(validationResult, {teachingReview}); omitted when unset. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Object teachingReview;
        /** Java-only: the parsed tree when produced by validateProtocolResponse(String, String). */
        @JsonIgnore
        public JsonNode parsed;

        @JsonIgnore
        public boolean isValid() {
            return protocolAccepted;
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

    private static ValidationResult notObject() {
        ValidationResult r = new ValidationResult();
        r.schemaErrors.add("Response is not a valid JSON object");
        r.errors.add("Response is not a valid JSON object");
        return r;
    }

    private static ValidationResult schemaFailed(List<String> schemaErrors, List<String> warnings) {
        ValidationResult r = new ValidationResult();
        r.jsonParsed = true;
        r.schemaErrors = schemaErrors;
        r.errors = schemaErrors; // same array instance, exactly like the original's early return
        r.warnings = warnings;
        return r;
    }

    private static ValidationResult finish(List<String> schemaErrors, List<String> semanticErrors, List<String> warnings) {
        ValidationResult r = new ValidationResult();
        r.jsonParsed = true;
        r.schemaValid = true;
        r.semanticValid = semanticErrors.isEmpty();
        r.protocolAccepted = r.semanticValid;
        r.schemaErrors = schemaErrors;
        r.semanticErrors = semanticErrors;
        r.errors = new ArrayList<>(schemaErrors);
        r.errors.addAll(semanticErrors);
        r.warnings = warnings;
        return r;
    }

    // -------------------------------------------------------------------
    // Entry points
    // -------------------------------------------------------------------

    /** Parses raw model text (JSON.parse) then delegates to validateProtocol. schemaVersion is ignored: the original dispatches on the payload. */
    public ValidationResult validateProtocolResponse(String rawResponseText, String schemaVersion) {
        JsonNode json = null;
        try {
            json = rawResponseText == null ? null : objectMapper.readTree(rawResponseText);
        } catch (Exception ignored) {
            // fall through to the parse-failure result
        }
        if (json == null || json.isMissingNode()) {
            ValidationResult r = new ValidationResult();
            r.schemaErrors.add("Failed to parse model output as JSON");
            r.errors.add("Failed to parse model output as JSON");
            return r;
        }
        ValidationResult r = validateProtocol(json);
        r.parsed = json;
        return r;
    }

    /** Version-agnostic dispatcher: routes to v1.3 or v1.4 validator based on schema_version. */
    public ValidationResult validateProtocol(JsonNode json) {
        if (json != null && isStr(json.path("schema_version"), "1.4")) return validateProtocolV14(json);
        return validateProtocolV13(json);
    }

    public ValidationResult validateProtocolV13(JsonNode json) {
        List<String> schemaErrors = new ArrayList<>();
        List<String> semanticErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (json == null || !json.isObject()) return notObject();

        // Layer 1: canonical JSON Schema validation (Ajv-equivalent)
        schemaCheck(schemaV13, json, "", schemaErrors);
        boolean isAjvValid = schemaErrors.isEmpty();

        if (!isStr(json.path("schema_version"), "1.3")) {
            schemaErrors.add("Invalid schema_version: expected \"1.3\", received \"" + jsStr(json.path("schema_version")) + "\"");
        }
        if (!json.path("content_blocks").isArray()) schemaErrors.add("content_blocks must be an array");
        if (!isJsObject(json.path("interaction"))) schemaErrors.add("interaction object is required in envelope");
        JsonNode serviceObj = json.path("service_trigger");
        if (!isJsObject(serviceObj)) schemaErrors.add("service_trigger object is required in envelope");
        if (!isJsObject(json.path("state"))) schemaErrors.add("state object is required in envelope");
        if (!isJsObject(json.path("followups"))) schemaErrors.add("followups object is required in envelope");

        boolean schemaValid = isAjvValid && schemaErrors.isEmpty();
        if (!schemaValid) return schemaFailed(schemaErrors, warnings);
        semanticErrors.addAll(validateDisplayInvariants(json));

        // Rule #10: First content block MUST be plain text with level="none" and title=""
        JsonNode blocks = json.path("content_blocks");
        if (blocks.isArray() && blocks.size() > 0) {
            JsonNode first = blocks.get(0);
            if (!isStr(first.path("type"), "text")) {
                semanticErrors.add("[Rule #10] First content block must be type=\"text\". Received: \"" + jsStr(first.path("type")) + "\"");
            }
            if (truthy(first.path("level")) && !isStr(first.path("level"), "none")) {
                semanticErrors.add("[Rule #10] First content block level must be \"none\". Received: \"" + jsStr(first.path("level")) + "\"");
            }
            if (truthy(first.path("title")) && !jsTrim(jsStr(first.path("title"))).isEmpty()) {
                semanticErrors.add("[Rule #10] First content block title must be empty string. Received: \"" + jsStr(first.path("title")) + "\"");
            }
        } else {
            semanticErrors.add("content_blocks must be a non-empty array");
        }

        // Interaction Invariants
        JsonNode inter = json.path("interaction");
        if (isJsObject(inter)) {
            JsonNode kind = inter.path("kind");
            List<JsonNode> options = arr(inter.path("options"));
            List<JsonNode> recommendedActions = arr(inter.path("recommended_actions"));

            if (isStr(inter.path("input_type"), "ranked_select") && (options.size() < 3 || options.size() > 6)) {
                semanticErrors.add("[Invariant] ranked_select interaction requires between 3 and 6 options. Received: " + options.size());
            }
            if ((isStr(kind, "question") || isStr(kind, "handoff")) && inter.path("recommended_actions").isArray() && !recommendedActions.isEmpty()) {
                semanticErrors.add("[Invariant] recommended_actions must be empty [] when interaction.kind is \"" + jsStr(kind) + "\". Received: " + recommendedActions.size() + " actions");
            }
            if (isStr(kind, "handoff") && isJsObject(json.path("rmo_readiness"))) {
                handoffWarnings(json, inter, warnings);
            }
        }

        // Security Penalty Invariant: penalty requires at least one breach (warning — content still renders)
        penaltyWarning(json, warnings);

        // User Confidence State Invariants
        confidenceErrors(json, semanticErrors, true);

        // Layer 3: Trusted Service Action Registry Check
        for (JsonNode act : arr(serviceObj.path("actions"))) {
            JsonNode actId = truthy(act.path("action_id")) ? act.path("action_id") : act.path("id");
            if (truthy(actId) && !TrustedServiceActions.isTrusted(jsStr(actId))) {
                warnings.add("[Security] Service action_id \"" + jsStr(actId) + "\" is untrusted/unregistered in server registry");
            }
        }

        return finish(schemaErrors, semanticErrors, warnings);
    }

    public ValidationResult validateProtocolV14(JsonNode json) {
        List<String> schemaErrors = new ArrayList<>();
        List<String> semanticErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (json == null || !json.isObject()) return notObject();

        schemaCheck(schemaV14, json, "", schemaErrors);
        boolean isAjvValid = schemaErrors.isEmpty();

        if (!isStr(json.path("schema_version"), "1.4")) {
            schemaErrors.add("Invalid schema_version: expected \"1.4\", received \"" + jsStr(json.path("schema_version")) + "\"");
        }
        if (!json.path("content_blocks").isArray()) schemaErrors.add("content_blocks must be an array");
        if (!isJsObject(json.path("interaction"))) schemaErrors.add("interaction object is required");
        if (!isJsObject(json.path("service_trigger"))) schemaErrors.add("service_trigger object is required in v1.4 envelope");
        if (!isJsObject(json.path("rmo_readiness"))) schemaErrors.add("rmo_readiness object is required in v1.4 envelope");
        if (!isJsObject(json.path("state"))) schemaErrors.add("state object is required");
        if (!isJsObject(json.path("followups"))) schemaErrors.add("followups object is required");

        boolean schemaValid = isAjvValid && schemaErrors.isEmpty();
        if (!schemaValid) return schemaFailed(schemaErrors, warnings);
        semanticErrors.addAll(validateDisplayInvariants(json));

        JsonNode blocks = json.path("content_blocks");
        if (blocks.isArray() && blocks.size() > 0) {
            JsonNode first = blocks.get(0);
            if (!isStr(first.path("type"), "text")) semanticErrors.add("[Rule #10] First content block must be type=\"text\". Received: \"" + jsStr(first.path("type")) + "\"");
            if (truthy(first.path("level")) && !isStr(first.path("level"), "none")) semanticErrors.add("[Rule #10] First content block level must be \"none\".");
            if (truthy(first.path("title")) && !jsTrim(jsStr(first.path("title"))).isEmpty()) semanticErrors.add("[Rule #10] First content block title must be empty string.");
        } else {
            semanticErrors.add("content_blocks must be a non-empty array");
        }

        JsonNode inter = json.path("interaction");
        if (isJsObject(inter)) {
            JsonNode kind = inter.path("kind");
            List<JsonNode> options = arr(inter.path("options"));
            if (isStr(inter.path("input_type"), "ranked_select") && (options.size() < 3 || options.size() > 6)) {
                semanticErrors.add("[Invariant] ranked_select requires 3-6 options. Received: " + options.size());
            }
            if ((isStr(kind, "question") || isStr(kind, "handoff")) && inter.path("recommended_actions").isArray() && inter.path("recommended_actions").size() > 0) {
                semanticErrors.add("[Invariant] recommended_actions must be empty [] when interaction.kind is \"" + jsStr(kind) + "\".");
            }
            if (isStr(kind, "handoff") && truthy(json.path("rmo_readiness"))) {
                handoffWarnings(json, inter, warnings);
            }
        }

        penaltyWarning(json, warnings);
        confidenceErrors(json, semanticErrors, false);

        // v1.4 semantic rules for new block types
        for (JsonNode block : arr(blocks)) {
            JsonNode d = block.path("data");
            String blockId = jsStr(block.path("id"));

            if (isStr(block.path("type"), "flow")) {
                List<JsonNode> rawNodes = arr(d.path("nodes"));
                Set<String> nodeIds = new HashSet<>();
                for (JsonNode n : rawNodes) nodeIds.add(jsStr(n.path("id")));
                if (nodeIds.size() != rawNodes.size()) {
                    Set<String> seen = new HashSet<>();
                    for (JsonNode n : rawNodes) {
                        String id = jsStr(n.path("id"));
                        if (seen.contains(id)) semanticErrors.add("[flow] Duplicate node id \"" + id + "\" in block \"" + blockId + "\"");
                        seen.add(id);
                    }
                }
                for (JsonNode edge : arr(d.path("edges"))) {
                    if (truthy(edge.path("from")) && !nodeIds.contains(jsStr(edge.path("from")))) warnings.add("[flow] Edge \"from\" id \"" + jsStr(edge.path("from")) + "\" does not reference a known node");
                    if (truthy(edge.path("to")) && !nodeIds.contains(jsStr(edge.path("to")))) warnings.add("[flow] Edge \"to\" id \"" + jsStr(edge.path("to")) + "\" does not reference a known node");
                }
            }

            if (isStr(block.path("type"), "pathway_map")) {
                List<JsonNode> rawLanes = arr(d.path("lanes"));
                Set<String> laneIds = new HashSet<>();
                for (JsonNode l : rawLanes) laneIds.add(jsStr(l.path("id")));
                if (laneIds.size() != rawLanes.size()) semanticErrors.add("[pathway_map] Duplicate lane ids in block \"" + blockId + "\"");
                for (JsonNode lane : rawLanes) {
                    List<JsonNode> steps = arr(lane.path("steps"));
                    Set<String> stepIds = new HashSet<>();
                    for (JsonNode s : steps) stepIds.add(jsStr(s.path("id")));
                    if (stepIds.size() != steps.size()) semanticErrors.add("[pathway_map] Duplicate step ids in lane \"" + jsStr(lane.path("id")) + "\"");
                }
            }

            if (isStr(block.path("type"), "timeline")) {
                List<JsonNode> rawMs = arr(d.path("milestones"));
                Set<String> msIds = new HashSet<>();
                for (JsonNode m : rawMs) msIds.add(jsStr(m.path("id")));
                if (msIds.size() != rawMs.size()) semanticErrors.add("[timeline] Duplicate milestone ids in block \"" + blockId + "\"");
                Set<String> validStatuses = Set.of("completed", "current", "upcoming", "blocked", "paused", "unknown");
                for (JsonNode m : rawMs) {
                    JsonNode st = m.path("status");
                    if (truthy(st) && !(st.isTextual() && validStatuses.contains(st.asText()))) semanticErrors.add("[timeline] Invalid milestone status \"" + jsStr(st) + "\" in block \"" + blockId + "\"");
                }
            }

            if (isStr(block.path("type"), "scorecard")) {
                for (JsonNode m : arr(d.path("metrics"))) {
                    JsonNode vt = m.path("value_type");
                    if (isStr(vt, "number") || isStr(vt, "percentage") || isStr(vt, "rating")) {
                        if (!m.path("value").isNumber()) semanticErrors.add("[scorecard] Metric \"" + jsStr(m.path("id")) + "\" value must be a number when value_type=\"" + jsStr(vt) + "\"");
                        JsonNode max = m.path("max");
                        if (!max.isMissingNode() && !max.isNull() && !max.isNumber()) semanticErrors.add("[scorecard] Metric \"" + jsStr(m.path("id")) + "\" max must be a number");
                    }
                }
            }

            if (isStr(block.path("type"), "chart")) {
                int catLen = arr(d.path("categories")).size();
                JsonNode chartType = d.path("chart_type");
                List<String> allowedTypes = List.of("bar", "line", "donut", "funnel");
                if (truthy(chartType) && !(chartType.isTextual() && allowedTypes.contains(chartType.asText()))) {
                    semanticErrors.add("[chart] chart_type \"" + jsStr(chartType) + "\" not in allowed set [" + String.join(",", allowedTypes) + "]");
                }
                for (JsonNode s : arr(d.path("series"))) {
                    JsonNode values = s.path("values");
                    if (values.isArray()) {
                        if (values.size() != catLen) {
                            semanticErrors.add("[chart] Series \"" + jsStr(s.path("id")) + "\" values length (" + values.size() + ") must match categories length (" + catLen + ")");
                        }
                        for (JsonNode v : values) {
                            if (!v.isNumber()) semanticErrors.add("[chart] Series \"" + jsStr(s.path("id")) + "\" contains non-numeric value: " + v);
                        }
                    }
                }
            }

            if (isStr(block.path("type"), "progress")) {
                int currentCount = 0;
                for (JsonNode s : arr(d.path("stages"))) if (isStr(s.path("status"), "current")) currentCount++;
                if (currentCount > 1) semanticErrors.add("[progress] At most one stage may have status=\"current\". Found " + currentCount);
            }
        }

        // Trusted service action check (v1.4 uses service_trigger.actions)
        for (JsonNode act : arr(json.path("service_trigger").path("actions"))) {
            JsonNode actId = truthy(act.path("action_id")) ? act.path("action_id") : act.path("id");
            if (truthy(actId) && !TrustedServiceActions.isTrusted(jsStr(actId))) {
                warnings.add("[Security] service_trigger action_id \"" + jsStr(actId) + "\" is untrusted/unregistered in server registry");
            }
        }

        return finish(schemaErrors, semanticErrors, warnings);
    }

    private static void handoffWarnings(JsonNode json, JsonNode inter, List<String> warnings) {
        List<String> fieldIds = new ArrayList<>();
        for (JsonNode f : arr(inter.path("fields"))) fieldIds.add(jsStr(f.path("id")));
        for (JsonNode m : arr(json.path("rmo_readiness").path("missing_inputs"))) {
            if (!fieldIds.contains(jsStr(m))) {
                warnings.add("[Handoff] rmo_readiness.missing_input \"" + jsStr(m) + "\" not present in interaction.fields");
            }
        }
    }

    private static void penaltyWarning(JsonNode json, List<String> warnings) {
        JsonNode prog = json.path("state").path("progress");
        if (!truthy(prog)) return;
        JsonNode pen = prog.path("active_security_penalty");
        JsonNode count = prog.path("security_breach_count");
        if (truthy(pen) && !isStr(pen, "") && count.isNumber() && count.asDouble() == 0) {
            warnings.add("[Invariant] active_security_penalty=\"" + jsStr(pen) + "\" requires security_breach_count >= 1, but got security_breach_count=0");
        }
    }

    private static void confidenceErrors(JsonNode json, List<String> semanticErrors, boolean v13) {
        JsonNode uc = json.path("state").path("user_confidence");
        if (!truthy(uc)) return;
        JsonNode score = uc.path("score");
        String band = jsStr(uc.path("band"));
        if (score.isNumber() && score.asDouble() == -1) {
            if (!isStr(uc.path("band"), "unknown")) semanticErrors.add("[Confidence] When score is -1, band must be \"unknown\". Received: \"" + band + "\"");
            if (!isStr(uc.path("evidence_strength"), "none")) {
                semanticErrors.add(v13
                        ? "[Confidence] When score is -1, evidence_strength must be \"none\". Received: \"" + jsStr(uc.path("evidence_strength")) + "\""
                        : "[Confidence] When score is -1, evidence_strength must be \"none\".");
            }
        } else if (score.isNumber() && score.asDouble() >= 0 && score.asDouble() <= 100) {
            double s = score.asDouble();
            String ss = jsStr(score);
            if (s <= 39 && !isStr(uc.path("band"), "low")) semanticErrors.add("[Confidence] Score " + ss + " (0-39) requires band=\"low\". Received: \"" + band + "\"");
            else if (s >= 40 && s <= 69 && !isStr(uc.path("band"), "medium")) semanticErrors.add("[Confidence] Score " + ss + " (40-69) requires band=\"medium\". Received: \"" + band + "\"");
            else if (s >= 70 && s <= 100 && !isStr(uc.path("band"), "high")) semanticErrors.add("[Confidence] Score " + ss + " (70-100) requires band=\"high\". Received: \"" + band + "\"");
        } else {
            semanticErrors.add("[Confidence] score must be -1 or integer 0..100. Received: " + jsStr(score));
        }
    }

    private static List<String> validateDisplayInvariants(JsonNode json) {
        List<String> errors = new ArrayList<>();
        JsonNode inter = json.path("interaction");
        if (!isStr(inter.path("kind"), "none")) {
            if (blank(inter.path("question_id")) || blank(inter.path("question"))) errors.add("An active question needs an ID and clear question text.");
            List<JsonNode> idNodes = new ArrayList<>();
            for (JsonNode o : arr(inter.path("options"))) idNodes.add(o.path("id"));
            if (idNodes.stream().anyMatch(ProtocolValidator::blank) || !unique(idNodes)) errors.add("Question option IDs must be nonempty and unique.");
            int n = idNodes.size();
            if (isStr(inter.path("input_type"), "single_select") && (n < 2 || n > 5)) errors.add("Single-choice questions need 2–5 options.");
            if (isStr(inter.path("input_type"), "multi_select") && (n < 2 || n > 6)) errors.add("Multiple-choice questions need 2–6 options.");
            if (isStr(inter.path("input_type"), "fields")) {
                List<JsonNode> fieldIds = new ArrayList<>();
                for (JsonNode f : arr(inter.path("fields"))) fieldIds.add(f.path("id"));
                if (fieldIds.isEmpty() || !unique(fieldIds)) errors.add("A form needs nonempty, unique fields.");
                for (JsonNode f : arr(inter.path("fields"))) {
                    boolean hasOptions = f.path("options").size() > 0;
                    if (isStr(f.path("id"), "location") && (isStr(f.path("input_type"), "single_select") || hasOptions)) errors.add("Location must be a typed field without location choices.");
                    if (isStr(f.path("input_type"), "single_select") && !hasOptions) errors.add("A selection field needs choices.");
                }
            }
            if (isStr(inter.path("kind"), "handoff") && !isStr(inter.path("input_type"), "fields")) errors.add("A handoff must use fields.");
            if (isStr(inter.path("kind"), "question") && !(isStr(inter.path("input_type"), "text") || isStr(inter.path("input_type"), "single_select")
                    || isStr(inter.path("input_type"), "multi_select") || isStr(inter.path("input_type"), "ranked_select"))) errors.add("A question must have an answer control.");
        }
        if (isStr(inter.path("kind"), "none") && !isStr(inter.path("input_type"), "none")) errors.add("Inactive questions must not expose answer controls.");
        for (JsonNode block : arr(json.path("content_blocks"))) {
            if (isStr(block.path("type"), "table") || isStr(block.path("type"), "comparison")) {
                List<JsonNode> keys = new ArrayList<>();
                for (JsonNode c : arr(block.path("columns"))) keys.add(c.path("key"));
                if (keys.isEmpty() || !unique(keys)) errors.add("Comparison columns must be nonempty and unique.");
                Set<String> keySet = new HashSet<>();
                for (JsonNode k : keys) keySet.add(identity(k));
                for (JsonNode row : arr(block.path("rows"))) {
                    List<JsonNode> cells = new ArrayList<>();
                    for (JsonNode c : arr(row.path("cells"))) cells.add(c.path("key"));
                    boolean unknown = cells.stream().anyMatch(k -> !keySet.contains(identity(k)));
                    if (cells.size() != keys.size() || !unique(cells) || unknown) errors.add("Every comparison row must preserve one cell per column.");
                }
            }
        }
        return errors;
    }

    // -------------------------------------------------------------------
    // Ajv({allErrors:true, strict:false}) equivalent for the keywords the two schemas use.
    // Keyword order mirrors Ajv 8: upfront type check (only when the type's keyword group is
    // absent), then const, enum, allOf, if/then, then the number/string/array/object groups
    // (type error reported in the group's else-branch when that group has keywords).
    // ponytail: only keywords present in the v1.3/v1.4 schemas; add others if the schemas grow.
    // -------------------------------------------------------------------

    private static final Map<String, List<String>> GROUP_KEYWORDS = Map.of(
            "number", List.of("maximum", "minimum"),
            "string", List.of("maxLength"),
            "array", List.of("maxItems", "items"),
            "object", List.of("required", "additionalProperties", "properties"));

    private static void schemaCheck(JsonNode s, JsonNode d, String path, List<String> errs) {
        if (s == null || !s.isObject()) return;
        String type = s.path("type").isTextual() ? s.get("type").asText() : null;
        boolean upfront = type != null && !hasGroup(s, type);
        if (upfront && !typeMatches(type, d)) schemaErr(errs, path, "must be " + type);

        if (s.has("const") && !jsEquals(d, s.get("const"))) schemaErr(errs, path, "must be equal to constant");
        if (s.has("enum")) {
            boolean match = false;
            for (JsonNode v : s.get("enum")) if (jsEquals(d, v)) { match = true; break; }
            if (!match) schemaErr(errs, path, "must be equal to one of the allowed values");
        }
        for (JsonNode sub : arr(s.path("allOf"))) schemaCheck(sub, d, path, errs);
        if (s.has("if") && s.has("then")) {
            List<String> ifErrs = new ArrayList<>();
            schemaCheck(s.get("if"), d, path, ifErrs);
            if (ifErrs.isEmpty()) {
                int before = errs.size();
                schemaCheck(s.get("then"), d, path, errs);
                if (errs.size() > before) schemaErr(errs, path, "must match \"then\" schema");
            }
        }

        if (hasGroup(s, "number")) {
            if (d != null && d.isNumber()) {
                if (s.has("maximum") && d.asDouble() > s.get("maximum").asDouble()) schemaErr(errs, path, "must be <= " + jsStr(s.get("maximum")));
                if (s.has("minimum") && d.asDouble() < s.get("minimum").asDouble()) schemaErr(errs, path, "must be >= " + jsStr(s.get("minimum")));
            } else if ("number".equals(type)) schemaErr(errs, path, "must be number");
        }
        if (hasGroup(s, "string")) {
            if (d != null && d.isTextual()) {
                String t = d.asText();
                if (s.has("maxLength") && t.codePointCount(0, t.length()) > s.get("maxLength").asInt()) {
                    schemaErr(errs, path, "must NOT have more than " + jsStr(s.get("maxLength")) + " characters");
                }
            } else if ("string".equals(type)) schemaErr(errs, path, "must be string");
        }
        if (hasGroup(s, "array")) {
            if (d != null && d.isArray()) {
                if (s.has("maxItems") && d.size() > s.get("maxItems").asInt()) schemaErr(errs, path, "must NOT have more than " + jsStr(s.get("maxItems")) + " items");
                if (s.path("items").isObject()) {
                    for (int i = 0; i < d.size(); i++) schemaCheck(s.get("items"), d.get(i), path + "/" + i, errs);
                }
            } else if ("array".equals(type)) schemaErr(errs, path, "must be array");
        }
        if (hasGroup(s, "object")) {
            if (d != null && d.isObject()) {
                for (JsonNode r : arr(s.path("required"))) {
                    if (!d.has(r.asText())) schemaErr(errs, path, "must have required property '" + r.asText() + "'");
                }
                JsonNode props = s.path("properties");
                if (s.has("additionalProperties") && s.get("additionalProperties").isBoolean() && !s.get("additionalProperties").asBoolean()) {
                    for (String key : jsKeys(d)) if (!props.has(key)) schemaErr(errs, path, "must NOT have additional properties");
                }
                Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (d.has(e.getKey())) schemaCheck(e.getValue(), d.get(e.getKey()), path + "/" + e.getKey().replace("~", "~0").replace("/", "~1"), errs);
                }
            } else if ("object".equals(type)) schemaErr(errs, path, "must be object");
        }
    }

    private static boolean hasGroup(JsonNode s, String type) {
        List<String> kws = GROUP_KEYWORDS.get(type);
        if (kws == null) return false; // integer/boolean/null have no keyword group in Ajv
        for (String k : kws) if (s.has(k)) return true;
        return false;
    }

    private static boolean typeMatches(String type, JsonNode d) {
        if (d == null || d.isMissingNode()) return false;
        switch (type) {
            case "object": return d.isObject();
            case "array": return d.isArray();
            case "string": return d.isTextual();
            case "boolean": return d.isBoolean();
            case "null": return d.isNull();
            case "number": return d.isNumber();
            case "integer": return d.isNumber() && (d.isIntegralNumber() || (Double.isFinite(d.asDouble()) && d.asDouble() % 1 == 0));
            default: return true;
        }
    }

    private static void schemaErr(List<String> errs, String path, String message) {
        errs.add("[Schema] " + (path.isEmpty() ? "root" : path) + ": " + message);
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
     * Server-side validation of incoming UserEvent against trusted Active Server Interaction.
     * Enforces question_id agreement, valid options, ranked bounds, and field schema.
     */
    public UserEventValidationResult validateUserEventAgainstActiveInteraction(JsonNode userEvent, JsonNode activeInteraction) {
        if (!truthy(userEvent)) return result(new ArrayList<>());

        JsonNode interaction;
        JsonNode nested = truthy(userEvent.path("userEvent")) ? userEvent.path("userEvent").path("interaction") : null;
        if (truthy(userEvent.path("interaction"))) interaction = userEvent.path("interaction");
        else if (truthy(nested)) interaction = nested;
        else if (truthy(userEvent.path("type"))) interaction = synthesiseInteraction(userEvent);
        else interaction = null;

        // If no structured interaction is attached (e.g. standard user text), it's valid
        if (interaction == null || (!truthy(interaction.path("question_id")) && !truthy(interaction.path("action_id"))
                && !truthy(interaction.path("selected_option_ids")) && !truthy(interaction.path("ranked_option_ids"))
                && !truthy(interaction.path("fields")) && !truthy(interaction.path("self_input")))) {
            return result(new ArrayList<>());
        }

        List<String> errors = new ArrayList<>();
        for (String name : new String[]{"selected_option_ids", "ranked_option_ids"}) {
            JsonNode v = interaction.path(name);
            if (!v.isMissingNode()) {
                boolean allStrings = v.isArray();
                if (allStrings) for (JsonNode x : v) if (!x.isTextual()) { allStrings = false; break; }
                if (!allStrings) return result(new ArrayList<>(List.of("Choices must be a list of valid option IDs.")));
            }
        }
        JsonNode selfInputNode = interaction.path("self_input");
        if (!selfInputNode.isMissingNode() && !selfInputNode.isTextual()) return result(new ArrayList<>(List.of("Your answer must be text.")));

        // Service Action Click Verification
        JsonNode actionId = interaction.path("action_id");
        if (truthy(actionId)) {
            if (!TrustedServiceActions.isTrusted(jsStr(actionId))) {
                errors.add("Action ID \"" + jsStr(actionId) + "\" is not a recognized or trusted service action.");
            }
            return result(errors);
        }

        JsonNode questionId = interaction.path("question_id");
        // If the interaction is targeting a question, verify active interaction existence
        if (!truthy(activeInteraction) || isStr(activeInteraction.path("kind"), "none")) {
            if (truthy(questionId)) {
                errors.add("No active question interaction on server. Received structured event for question_id \"" + jsStr(questionId) + "\".");
            }
            return result(errors);
        }

        JsonNode activeQId = truthy(activeInteraction.path("question_id")) ? activeInteraction.path("question_id") : activeInteraction.path("id");
        if (truthy(questionId) && truthy(activeQId) && !strictEquals(questionId, activeQId)) {
            errors.add("Question ID mismatch: received \"" + jsStr(questionId) + "\", but active server question is \"" + jsStr(activeQId) + "\".");
        }

        List<JsonNode> trustedOptions = arr(activeInteraction.path("options"));
        List<JsonNode> trustedOptionIds = new ArrayList<>();
        for (JsonNode o : trustedOptions) {
            trustedOptionIds.add(truthy(o.path("id")) ? o.path("id") : truthy(o.path("option_id")) ? o.path("option_id") : o.path("value"));
        }
        JsonNode inputType = truthy(activeInteraction.path("input_type")) ? activeInteraction.path("input_type") : TextNode.valueOf("single_select");

        List<String> selected = strings(interaction.path("selected_option_ids"));
        List<String> ranked = strings(interaction.path("ranked_option_ids"));
        String selfInput = selfInputNode.isTextual() ? selfInputNode.asText() : null;
        boolean hasSelfInput = selfInput != null && !jsTrim(selfInput).isEmpty();
        boolean allowOther = truthy(activeInteraction.path("allow_other_input"));

        // Validate Single Select
        if (isStr(inputType, "single_select")) {
            if (selected.size() > 1) errors.add("Single select interaction accepts at most 1 option, received " + selected.size() + ".");
            if (selected.isEmpty() && !hasSelfInput) errors.add("Single select interaction requires exactly one valid option or permitted self_input.");
            if (selected.size() == 1 && hasSelfInput) errors.add("Cannot submit both a selected option and self-input in single select.");
            for (String optId : selected) if (!containsText(trustedOptionIds, optId)) errors.add("Selected option ID \"" + optId + "\" is not in the trusted active options list.");
            if (hasSelfInput && !allowOther) errors.add("Self-input provided but allow_other_input is false for this question.");
        }

        // Validate Multi Select
        if (isStr(inputType, "multi_select")) {
            if (selected.isEmpty() && !hasSelfInput) errors.add("Choose at least one option or enter your own answer.");
            if (new HashSet<>(selected).size() != selected.size()) errors.add("Duplicate option IDs submitted in multi-select.");
            for (String optId : selected) if (!containsText(trustedOptionIds, optId)) errors.add("Selected option ID \"" + optId + "\" is not in the trusted active options list.");
            if (hasSelfInput && !allowOther) errors.add("Self-input provided but allow_other_input is false for this question.");
        }

        // Validate Ranked Select
        if (isStr(inputType, "ranked_select")) {
            if (new HashSet<>(ranked).size() != ranked.size()) errors.add("Duplicate option IDs found in ranked selection.");
            for (String optId : ranked) if (!containsText(trustedOptionIds, optId)) errors.add("Ranked option ID \"" + optId + "\" is not in the trusted active options list.");
            int minOptions = Math.min(3, trustedOptions.size());
            int maxOptions = Math.min(6, trustedOptions.size());
            if (ranked.size() < minOptions || ranked.size() > maxOptions) {
                errors.add("Ranked options count (" + ranked.size() + ") must be between " + minOptions + " and " + maxOptions + ".");
            }
        }

        // Validate required fields and values against the active server-owned form.
        if (isStr(inputType, "fields") || isStr(activeInteraction.path("kind"), "handoff")) {
            errors.addAll(validateInteractionFields(activeInteraction, interaction.path("fields")).errors);
        }
        if (isStr(inputType, "text") && !hasSelfInput) {
            errors.add("Enter your answer before continuing.");
        }
        return result(errors);
    }

    /** Legacy flat UserEvent fields → interaction, with the original's `||` fallbacks. */
    private ObjectNode synthesiseInteraction(JsonNode ue) {
        ObjectNode s = objectMapper.createObjectNode();
        s.set("question_id", truthy(ue.path("interaction_id")) ? ue.path("interaction_id") : TextNode.valueOf("active_question"));
        if (truthy(ue.path("option_id"))) {
            ArrayNode a = s.putArray("selected_option_ids");
            a.add(ue.path("option_id"));
        } else if (truthy(ue.path("selected_option_ids"))) {
            s.set("selected_option_ids", ue.path("selected_option_ids"));
        }
        JsonNode ranked = truthy(ue.path("ranked_ids")) ? ue.path("ranked_ids") : ue.path("ranked_option_ids");
        if (!ranked.isMissingNode()) s.set("ranked_option_ids", ranked);
        if (!ue.path("fields").isMissingNode()) s.set("fields", ue.path("fields"));
        JsonNode selfInput = truthy(ue.path("value")) ? ue.path("value") : ue.path("self_input");
        if (!selfInput.isMissingNode()) s.set("self_input", selfInput);
        if (!ue.path("action_id").isMissingNode()) s.set("action_id", ue.path("action_id"));
        return s;
    }

    /** Convenience overload for callers holding plain Map payloads. */
    public InteractionFieldsResult validateInteractionFields(Map<String, Object> active, Map<String, Object> values) {
        JsonNode a = active == null ? null : objectMapper.valueToTree(active);
        JsonNode v = values == null ? null : objectMapper.valueToTree(values);
        return validateInteractionFields(a, v);
    }

    public InteractionFieldsResult validateInteractionFields(JsonNode active, JsonNode values) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        List<JsonNode> fields = active == null ? new ArrayList<>() : arr(active.path("fields"));
        JsonNode submitted = values;
        if (!truthy(values) || !values.isObject()) {
            fieldErrors.put("form", "Enter the requested details.");
            submitted = objectMapper.createObjectNode();
        }
        for (String key : jsKeys(submitted)) {
            if (fields.stream().noneMatch(f -> isStr(f.path("id"), key))) fieldErrors.put(key, "This field is not part of the current question.");
        }
        for (JsonNode field : fields) {
            String id = jsStr(field.path("id"));
            String label = jsStr(field.path("label"));
            JsonNode value = submitted.path(id);
            if (!value.isMissingNode() && !value.isTextual()) {
                fieldErrors.put(id, label + " must be text.");
                continue;
            }
            String text = value.isTextual() ? jsTrim(value.asText()) : "";
            if (truthy(field.path("required")) && text.isEmpty()) {
                fieldErrors.put(id, "Enter " + (isStr(field.path("id"), "location") ? "a city, suburb or postcode" : label.toLowerCase(Locale.ROOT)) + ".");
            } else if (text.length() > 500) {
                fieldErrors.put(id, "Keep this answer under 500 characters.");
            } else if (!text.isEmpty() && isStr(field.path("input_type"), "single_select")
                    && arr(field.path("options")).stream().noneMatch(o -> isStr(truthy(o.path("value")) ? o.path("value") : o.path("label"), text))) {
                fieldErrors.put(id, "Choose one of the listed options for " + label.toLowerCase(Locale.ROOT) + ".");
            }
        }
        // Object.values(): integer-like keys first (ascending), then insertion order.
        Map<String, String> ordered = new LinkedHashMap<>();
        fieldErrors.keySet().stream().filter(ProtocolValidator::isArrayIndex).sorted((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)))
                .forEach(k -> ordered.put(k, fieldErrors.get(k)));
        fieldErrors.forEach(ordered::putIfAbsent);

        InteractionFieldsResult result = new InteractionFieldsResult();
        result.fieldErrors = ordered;
        result.errors = new ArrayList<>(ordered.values());
        result.valid = ordered.isEmpty();
        return result;
    }

    // -------------------------------------------------------------------
    // JS-semantics helpers
    // -------------------------------------------------------------------

    private static UserEventValidationResult result(List<String> errors) {
        UserEventValidationResult r = new UserEventValidationResult();
        r.valid = errors.isEmpty();
        r.errors = errors;
        return r;
    }

    /** JS truthiness of a JSON value (missing/null → undefined/null). */
    static boolean truthy(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return false;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) { double d = n.asDouble(); return d != 0 && !Double.isNaN(d); }
        if (n.isTextual()) return !n.asText().isEmpty();
        return true;
    }

    /** typeof x === 'object' && x !== null (arrays included, as in the original's envelope checks). */
    private static boolean isJsObject(JsonNode n) {
        return n != null && (n.isObject() || n.isArray());
    }

    private static boolean isStr(JsonNode n, String s) {
        return n != null && n.isTextual() && n.asText().equals(s);
    }

    /** JS String(x) / template-literal conversion. */
    static String jsStr(JsonNode n) {
        if (n == null || n.isMissingNode()) return "undefined";
        if (n.isNull()) return "null";
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return String.valueOf(n.asBoolean());
        if (n.isIntegralNumber()) return n.asText();
        if (n.isNumber()) {
            double d = n.asDouble();
            if (Double.isFinite(d) && d % 1 == 0 && Math.abs(d) < 1e21) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                JsonNode e = n.get(i);
                if (!e.isNull()) sb.append(jsStr(e));
            }
            return sb.toString();
        }
        return "[object Object]";
    }

    /** String.prototype.trim(): strips JS WhiteSpace + LineTerminator code points. */
    static String jsTrim(String s) {
        int start = 0, end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) start++;
        while (end > start && isJsSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0 || c == 0xFEFF
                || c == 0x2028 || c == 0x2029 || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /** !x?.trim() for a value the schema guarantees is a string. */
    private static boolean blank(JsonNode n) {
        return !n.isTextual() || jsTrim(n.asText()).isEmpty();
    }

    /** Set-identity key (SameValueZero across primitives; strings and numbers stay distinct). */
    private static String identity(JsonNode n) {
        if (n == null || n.isMissingNode()) return "u:";
        if (n.isTextual()) return "s:" + n.asText();
        return "o:" + jsStr(n) + ":" + n.getNodeType();
    }

    private static boolean unique(List<JsonNode> nodes) {
        Set<String> seen = new HashSet<>();
        for (JsonNode n : nodes) if (!seen.add(identity(n))) return false;
        return true;
    }

    private static boolean strictEquals(JsonNode a, JsonNode b) {
        if (a.isTextual() && b.isTextual()) return a.asText().equals(b.asText());
        if (a.isNumber() && b.isNumber()) return a.asDouble() == b.asDouble();
        if (a.isBoolean() && b.isBoolean()) return a.asBoolean() == b.asBoolean();
        return a == b;
    }

    /** fast-deep-equal as used by Ajv const/enum. */
    private static boolean jsEquals(JsonNode a, JsonNode b) {
        if (a == null || a.isMissingNode()) return false;
        if (a.isNumber() && b.isNumber()) return a.asDouble() == b.asDouble();
        return a.equals(b);
    }

    private static boolean containsText(List<JsonNode> nodes, String s) {
        for (JsonNode n : nodes) if (isStr(n, s)) return true;
        return false;
    }

    private static List<JsonNode> arr(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n != null && n.isArray()) n.forEach(out::add);
        return out;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr(n)) out.add(v.asText());
        return out;
    }

    private static boolean isArrayIndex(String k) {
        return k.matches("0|[1-9][0-9]{0,9}") && Long.parseLong(k) < 4294967295L;
    }

    /** Object.keys / for-in order: array-index keys ascending, then insertion order. */
    private static List<String> jsKeys(JsonNode obj) {
        List<String> index = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        if (obj != null && obj.isObject()) {
            obj.fieldNames().forEachRemaining(k -> (isArrayIndex(k) ? index : rest).add(k));
        }
        index.sort((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        index.addAll(rest);
        return index;
    }
}
