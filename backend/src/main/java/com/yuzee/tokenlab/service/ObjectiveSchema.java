package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim;

/**
 * Port of qa/objectives/schema.mjs (compileResult, plannerSchema, readinessAllowed, WIRE_INSTRUCTION),
 * qa/objectives/api.mjs (geminiSchema, extractText), src/objectives/providerSchema.ts and
 * qa/objectives/validate.mjs (validateOutput), plus the part of Ajv 8 those schemas use.
 */
public final class ObjectiveSchema {

    private ObjectiveSchema() {
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** JSON.parse: exactly one JSON value. */
    public static final ObjectMapper PARSER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // ------------------------------------------------------------------ schema.mjs builders

    static ObjectNode str() { return F.objectNode().put("type", "string"); }

    private static ObjectNode scalar() {
        ObjectNode n = F.objectNode();
        n.putArray("type").add("string").add("number").add("boolean").add("null");
        return n;
    }

    static ObjectNode arr(JsonNode items) {
        ObjectNode n = F.objectNode().put("type", "array");
        n.set("items", items);
        return n;
    }

    static ObjectNode obj(ObjectNode properties) {
        ObjectNode n = F.objectNode().put("type", "object");
        n.set("properties", properties);
        ArrayNode required = n.putArray("required");
        properties.fieldNames().forEachRemaining(required::add);
        n.put("additionalProperties", false);
        return n;
    }

    private static ObjectNode optionalObj(ObjectNode properties) {
        ObjectNode n = F.objectNode().put("type", "object");
        n.set("properties", properties);
        n.put("additionalProperties", false);
        return n;
    }

    private static ObjectNode nullable(JsonNode schema) {
        ObjectNode n = F.objectNode();
        n.putArray("anyOf").add(schema).add(F.objectNode().put("type", "null"));
        return n;
    }

    static ObjectNode enumOf(String... values) {
        ObjectNode n = str();
        ArrayNode e = n.putArray("enum");
        for (String v : values) e.add(v);
        return n;
    }

    private static ObjectNode props(Object... kv) {
        ObjectNode n = F.objectNode();
        for (int i = 0; i < kv.length; i += 2) n.set((String) kv[i], (JsonNode) kv[i + 1]);
        return n;
    }

    private static final String[] ACTIONS = {"CONTINUE_CHAT", "OPEN_OBJECTIVE", "RESEARCH", "COMPARE", "LEARN", "NAVIGATE", "REQUEST_ACTION"};

    /*
     * schema.mjs's module-level `action` and `note` objects. service.ts plan() mutates them through the planner
     * schema (next_actions id/type enums, bound evidence_refs), and every later plannerSchema() call, including
     * validateOutput's, sees those mutations. The same shared instances are kept here for the same effect.
     */
    static final ObjectNode ACTION = obj(props("id", str(), "label", str(), "type", enumOf(ACTIONS)));
    static final ObjectNode NOTE = obj(props("label", str(), "detail", str(),
        "source_status", enumOf("USER_CONFIRMED", "AI_INFERRED", "SOURCED_CURRENT_FACT", "UNKNOWN", "GENERAL_GUIDANCE"),
        "evidence_refs", arr(str())));

    private static ObjectNode atom() {
        ObjectNode n = F.objectNode();
        n.putArray("anyOf").add(scalar()).add(NOTE);
        return n;
    }

    // ------------------------------------------------------------------ compileResult

    static final class Compiled {
        final ObjectNode schema;
        final List<String> completionKeys;
        final List<Map<String, Object>> warnings;

        Compiled(ObjectNode schema, List<String> completionKeys, List<Map<String, Object>> warnings) {
            this.schema = schema;
            this.completionKeys = completionKeys;
            this.warnings = warnings;
        }
    }

    private static final Pattern FIELD = jsRegex("^([a-zA-Z][\\w]*)(\\[\\])?\\s*([\\s\\S]*)$", false);
    private static final Pattern ENUM_ANNOTATION = jsRegex("^[A-Z][A-Z_]*(\\|[A-Z_]+)+$", false);
    private static final Pattern COLON_PREFIX = jsRegex("^:\\s*", false);

    private static List<String> splitTop(String text) {
        int depth = 0, start = 0;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (depth < 0) throw new IllegalArgumentException("Unbalanced output contract");
            if (depth == 0 && (c == ',' || c == ';')) {
                out.add(jsTrim(text.substring(start, i)));
                start = i + 1;
            }
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced output contract");
        out.add(jsTrim(text.substring(start)));
        out.removeIf(String::isEmpty);
        return out;
    }

    private static Map<String, Object> warning(String path, String text) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("path", path);
        w.put("text", text);
        return w;
    }

    static Compiled compileResult(String contract) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        ObjectNode properties = F.objectNode();
        properties.set("summary", str());
        properties.setAll(fields(contract == null ? "" : contract, "result", warnings));
        List<String> keys = new ArrayList<>();
        properties.fieldNames().forEachRemaining(k -> { if (!k.equals("summary")) keys.add(k); });
        return new Compiled(optionalObj(properties), keys, warnings);
    }

    private static ObjectNode fields(String text, String path, List<Map<String, Object>> warnings) {
        ObjectNode props = F.objectNode();
        for (String segment : splitTop(text)) {
            var m = FIELD.matcher(segment);
            if (!m.find()) { warnings.add(warning(path, segment)); continue; }
            String key = m.group(1), tail = m.group(3);
            boolean isArray = m.group(2) != null;
            if (props.has(key)) { warnings.add(warning(path, "Duplicate field " + key)); continue; }
            JsonNode schema;
            if (tail.startsWith("{")) {
                int depth = 0, end = -1;
                for (int i = 0; i < tail.length(); i++) {
                    if (tail.charAt(i) == '{') depth++;
                    if (tail.charAt(i) == '}' && --depth == 0) { end = i; break; }
                }
                if (end < 0) throw new IllegalArgumentException("Unbalanced object contract");
                schema = nullable(obj(fields(tail.substring(1, end), path + "." + key, warnings)));
                if (!jsTrim(tail.substring(end + 1)).isEmpty()) warnings.add(warning(path + "." + key, tail.substring(end + 1)));
            } else {
                schema = isArray ? atom() : scalar();
                String annotation = COLON_PREFIX.matcher(jsTrim(tail)).replaceFirst("");
                if (ENUM_ANNOTATION.matcher(annotation).find()) schema = enumOf(annotation.split("\\|", -1));
                else if (annotation.equals("=true")) schema = F.objectNode().put("const", true);
                else if (!annotation.isEmpty()) warnings.add(warning(path + "." + key, annotation));
            }
            props.set(key, isArray ? arr(schema) : schema);
        }
        // A source-bearing workbook object needs a place for its references.
        if (props.has("source_status") && !props.has("evidence_refs")) props.set("evidence_refs", arr(str()));
        return props;
    }

    // ------------------------------------------------------------------ plannerSchema

    /** schema.mjs plannerSchema(o, context). */
    static ObjectNode plannerSchema(JsonNode o, JsonNode confirmedFacts) {
        ObjectNode result = compileResult(o.path("qa_output_contract_v2").isMissingNode() ? null : o.path("qa_output_contract_v2").asText()).schema;
        ObjectNode confirmedProps = F.objectNode();
        if (confirmedFacts != null && confirmedFacts.isObject()) {
            confirmedFacts.fields().forEachRemaining(e -> confirmedProps.set(e.getKey(), F.objectNode().set("const", e.getValue())));
        }
        ObjectNode confirmed = optionalObj(confirmedProps);
        if (!confirmedProps.isEmpty()) {
            ArrayNode req = confirmed.putArray("required");
            confirmedProps.fieldNames().forEachRemaining(req::add);
        }
        ObjectNode option = obj(props("id", str(), "label", str(), "detail", str()));
        ObjectNode componentKind = str();
        componentKind.set("enum", o.get("allowed_primitives"));
        ObjectNode component = obj(props(
            "component", componentKind, "id", str(), "purpose", str(), "prompt", str(), "required", F.objectNode().put("type", "boolean"),
            "options", arr(option), "content", arr(NOTE), "columns", arr(str()), "rows", arr(obj(props("label", str(), "cells", arr(str())))),
            "settings", obj(props("allow_unsure", F.objectNode().put("type", "boolean"), "min", nullable(F.objectNode().put("type", "number")),
                "max", nullable(F.objectNode().put("type", "number")), "min_label", nullable(str()), "max_label", nullable(str()), "buckets", arr(str())))));
        ObjectNode readiness = obj(props("enabled", F.objectNode().put("type", "boolean"), "name", nullable(str()),
            "score", nullable(F.objectNode().put("type", "number").put("minimum", 0).put("maximum", 100)),
            "dimensions", arr(obj(props("name", str(), "score", F.objectNode().put("type", "number").put("minimum", 0).put("maximum", 100), "reason", str()))),
            "blockers", arr(str())));
        ObjectNode ui = arr(component).put("maxItems", 3);
        return obj(props(
            "objective_id", enumOf(o.path("tool_id").asText()),
            "status", enumOf("NEEDS_INPUT", "NEEDS_RESEARCH", "IN_PROGRESS", "COMPLETE"),
            "stage", enumOf("UNDERSTAND", "PLAN", "STRATEGY", "READINESS", "PROCEED"),
            "ui", ui,
            "confirmed_inputs", confirmed,
            "derived_signals", arr(obj(props("signal", str(), "basis", str(), "confidence", enumOf("LOW", "MEDIUM", "HIGH")))),
            "unknowns", arr(str()),
            "result", result,
            "readiness", readiness,
            "next_actions", arr(ACTION),
            "handoff", obj(props("summary", str(), "confirmed_user_inputs", confirmed, "result", obj(props("summary", str())),
                "evidence_and_unknowns", arr(str()), "recommended_next_action", str()))));
    }

    private static final Pattern READINESS_BANNED = Pattern.compile("^(NONE\\b|No psychometric score|Goal-state routing only|Exploration only)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READINESS_NO_SCORE = Pattern.compile("no (?:numeric )?readiness score", Pattern.CASE_INSENSITIVE);

    /** schema.mjs readinessAllowed(o). */
    static boolean readinessAllowed(JsonNode o) {
        String p = o.path("qa_readiness_policy_v2").asText("undefined");
        return !READINESS_BANNED.matcher(p).find() && !READINESS_NO_SCORE.matcher(p).find();
    }

    // ------------------------------------------------------------------ api.mjs / providerSchema.ts

    /** api.mjs geminiSchema(node): a typed singleton enum instead of an untyped const. */
    static JsonNode geminiSchema(JsonNode node) {
        if (node == null || !node.isContainerNode()) return node;
        if (node.isObject() && node.has("const")) {
            JsonNode c = node.get("const");
            if (c.isNull()) return F.objectNode().put("type", "null");
            String type = c.isTextual() ? "string" : c.isNumber() ? "number" : c.isBoolean() ? "boolean" : null;
            if (type == null) throw new IllegalStateException("Structured confirmed facts require an explicit typed fixture mapping");
            ObjectNode out = F.objectNode().put("type", type);
            out.putArray("enum").add(c);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(x -> out.add(geminiSchema(x)));
            return out;
        }
        ObjectNode out = F.objectNode();
        node.fields().forEachRemaining(e -> out.set(e.getKey(), geminiSchema(e.getValue())));
        return out;
    }

    /** providerSchema.ts objectiveProviderSchema(schema): evidence_refs allowlists stay local. */
    static JsonNode objectiveProviderSchema(JsonNode schema) {
        JsonNode result = schema.deepCopy();
        visitRefs(result);
        return geminiSchema(result);
    }

    private static void visitRefs(JsonNode node) {
        if (node == null || !node.isContainerNode()) return;
        JsonNode refs = node.path("properties").path("evidence_refs");
        if (refs.isObject() && truthy(refs.get("items"))) ((ObjectNode) refs).set("items", str());
        node.elements().forEachRemaining(ObjectiveSchema::visitRefs);
    }

    /** api.mjs extractText(body). */
    public static String extractText(JsonNode body) {
        if (!truthy(body)) return "";
        if (body.path("output_text").isTextual()) return body.get("output_text").asText();
        StringBuilder sb = new StringBuilder();
        if (truthy(body.get("steps"))) {
            for (JsonNode s : body.get("steps")) {
                if (!"model_output".equals(s.path("type").asText(null))) continue;
                JsonNode content = s.get("content");
                if (!truthy(content)) continue;
                for (JsonNode c : content) if ("text".equals(c.path("type").asText(null))) sb.append(textOr(c.get("text")));
            }
            return sb.toString();
        }
        JsonNode outputs = body.get("outputs");
        if (outputs != null && !outputs.isNull()) {
            for (JsonNode c : outputs) if ("text".equals(c.path("type").asText(null))) sb.append(textOr(c.get("text")));
        }
        return sb.toString();
    }

    /** {@code c.text||''} joined: a truthy non-string is written with String(). */
    private static String textOr(JsonNode t) {
        return truthy(t) ? jsString(t) : "";
    }

    /** String(value) for a JSON value. */
    private static String jsString(JsonNode t) {
        if (t == null || t.isNull() || t.isMissingNode()) return "";
        if (t.isTextual()) return t.asText();
        if (t.isNumber()) return JsJson.number(t.doubleValue());
        if (t.isBoolean()) return String.valueOf(t.booleanValue());
        if (t.isObject()) return "[object Object]";
        List<String> parts = new ArrayList<>();
        t.forEach(x -> parts.add(jsString(x)));
        return String.join(",", parts);
    }

    /** JSON.parse has one number type: an integral 80.0 is the number 80, so it is kept (and later written) as 80. */
    static JsonNode jsNumbers(JsonNode n) {
        if (n == null) return null;
        if (n.isFloatingPointNumber() && Double.isFinite(n.doubleValue()) && n.doubleValue() == Math.rint(n.doubleValue())
            && Math.abs(n.doubleValue()) <= 9007199254740992d) {
            return F.numberNode((long) n.doubleValue());
        }
        if (n.isArray()) {
            ArrayNode out = F.arrayNode();
            n.forEach(x -> out.add(jsNumbers(x)));
            return out;
        }
        if (n.isObject()) {
            ObjectNode out = F.objectNode();
            n.fields().forEachRemaining(e -> out.set(e.getKey(), jsNumbers(e.getValue())));
            return out;
        }
        return n;
    }

    static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isTextual()) return !n.asText().isEmpty();
        if (n.isNumber()) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        return true;
    }

    // ------------------------------------------------------------------ Ajv 8 subset

    /**
     * The Ajv 8 keywords these schemas use (type, const, enum, anyOf, maximum, minimum, maxLength, maxItems, items,
     * required, additionalProperties, properties), evaluated in Ajv's rule-group order with Ajv's error objects.
     * allErrors=false stops at the first error, as Ajv's generated code does.
     */
    static final class Ajv {
        private final boolean allErrors;
        final List<Map<String, Object>> errors = new ArrayList<>();

        Ajv(boolean allErrors) { this.allErrors = allErrors; }

        private static final class Stop extends RuntimeException {
            Stop() { super(null, null, false, false); }
        }

        static List<Map<String, Object>> validate(JsonNode schema, JsonNode data, boolean allErrors) {
            Ajv a = new Ajv(allErrors);
            try {
                a.v(schema, data, "", "#", false);
            } catch (Stop ignored) {
                // first error returned
            }
            return a.errors;
        }

        private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
        static {
            GROUPS.put("number", List.of("maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum", "multipleOf", "format"));
            GROUPS.put("string", List.of("maxLength", "minLength", "pattern", "format"));
            GROUPS.put("array", List.of("maxItems", "minItems", "additionalItems", "items", "contains", "uniqueItems"));
            GROUPS.put("object", List.of("maxProperties", "minProperties", "required", "propertyNames", "additionalProperties", "dependencies", "properties", "patternProperties"));
        }

        private static boolean hasRules(JsonNode s, String type) {
            List<String> g = GROUPS.get(type);
            if (g == null) return false;
            for (String k : g) if (s.has(k)) return true;
            return false;
        }

        private static boolean is(String type, JsonNode d) {
            return switch (type) {
                case "object" -> d.isObject();
                case "array" -> d.isArray();
                case "string" -> d.isTextual();
                case "number" -> d.isNumber();
                case "integer" -> d.isNumber() && d.doubleValue() == Math.rint(d.doubleValue());
                case "boolean" -> d.isBoolean();
                case "null" -> d.isNull();
                default -> false;
            };
        }

        /** Adds an error; outside anyOf without allErrors, Ajv returns immediately. */
        private boolean error(String ip, String sp, String keyword, ObjectNode params, String message, boolean composite) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("instancePath", ip);
            e.put("schemaPath", sp);
            e.put("keyword", keyword);
            e.put("params", params);
            e.put("message", message);
            errors.add(e);
            if (!allErrors && !composite) throw new Stop();
            return false;
        }

        private static String ptr(String key) { return "/" + key.replace("~", "~0").replace("/", "~1"); }

        private static String fragment(String key) {
            return java.net.URLEncoder.encode(key.replace("~", "~0").replace("/", "~1"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
        }

        private static String num(JsonNode n) { return JsJson.number(n.doubleValue()); }

        private boolean v(JsonNode s, JsonNode d, String ip, String sp, boolean composite) {
            if (s == null || !s.isObject()) return true;
            JsonNode typeKw = s.get("type");
            List<String> types = new ArrayList<>();
            if (typeKw != null && typeKw.isTextual()) types.add(typeKw.asText());
            else if (typeKw != null && typeKw.isArray()) typeKw.forEach(t -> types.add(t.asText()));
            boolean checkTypes = !types.isEmpty() && !(types.size() == 1 && hasRules(s, types.get(0)));
            boolean valid = true;
            if (checkTypes && types.stream().noneMatch(t -> is(t, d))) {
                valid = typeError(s, ip, sp, composite);
                if (!allErrors) return false;
            }
            // untyped group: const, enum, anyOf
            if (s.has("const") && !jsEqual(d, s.get("const"))) {
                valid = error(ip, sp + "/const", "const", F.objectNode().set("allowedValue", s.get("const")), "must be equal to constant", composite);
                if (!allErrors) return false;
            }
            if (s.has("enum")) {
                boolean found = false;
                for (JsonNode e : s.get("enum")) if (jsEqual(d, e)) { found = true; break; }
                if (!found) {
                    valid = error(ip, sp + "/enum", "enum", F.objectNode().set("allowedValues", s.get("enum")), "must be equal to one of the allowed values", composite);
                    if (!allErrors) return false;
                }
            }
            if (s.has("anyOf")) {
                int before = errors.size();
                boolean any = false;
                JsonNode branches = s.get("anyOf");
                for (int i = 0; i < branches.size() && !any; i++) any = v(branches.get(i), d, ip, sp + "/anyOf/" + i, true);
                if (any) {
                    while (errors.size() > before) errors.remove(errors.size() - 1);
                } else {
                    valid = error(ip, sp + "/anyOf", "anyOf", F.objectNode(), "must match a schema in anyOf", composite);
                    if (!allErrors) return false;
                }
            }
            for (String group : GROUPS.keySet()) {
                if (!hasRules(s, group)) continue;
                if (is(group, d)) {
                    if (!keywords(group, s, d, ip, sp, composite)) {
                        valid = false;
                        if (!allErrors) return false;
                    }
                } else if (types.size() == 1 && types.get(0).equals(group) && !checkTypes) {
                    valid = typeError(s, ip, sp, composite);
                    if (!allErrors) return false;
                }
            }
            return valid;
        }

        private boolean typeError(JsonNode s, String ip, String sp, boolean composite) {
            JsonNode t = s.get("type");
            String shown = t.isTextual() ? t.asText() : String.join(",", iterable(t));
            return error(ip, sp + "/type", "type", F.objectNode().set("type", t), "must be " + shown, composite);
        }

        private static List<String> iterable(JsonNode arr) {
            List<String> out = new ArrayList<>();
            arr.forEach(x -> out.add(x.asText()));
            return out;
        }

        private boolean keywords(String group, JsonNode s, JsonNode d, String ip, String sp, boolean composite) {
            boolean valid = true;
            switch (group) {
                case "number" -> {
                    if (s.has("maximum") && !(d.doubleValue() <= s.get("maximum").doubleValue())) {
                        valid = error(ip, sp + "/maximum", "maximum", F.objectNode().put("comparison", "<=").set("limit", s.get("maximum")), "must be <= " + num(s.get("maximum")), composite);
                        if (!allErrors) return false;
                    }
                    if (s.has("minimum") && !(d.doubleValue() >= s.get("minimum").doubleValue())) {
                        valid = error(ip, sp + "/minimum", "minimum", F.objectNode().put("comparison", ">=").set("limit", s.get("minimum")), "must be >= " + num(s.get("minimum")), composite);
                        if (!allErrors) return false;
                    }
                }
                case "string" -> {
                    // Ajv's default unicode:true counts code points.
                    String text = d.asText();
                    if (s.has("maxLength") && text.codePointCount(0, text.length()) > s.get("maxLength").doubleValue()) {
                        valid = error(ip, sp + "/maxLength", "maxLength", F.objectNode().set("limit", s.get("maxLength")), "must NOT have more than " + num(s.get("maxLength")) + " characters", composite);
                        if (!allErrors) return false;
                    }
                }
                case "array" -> {
                    if (s.has("maxItems") && d.size() > s.get("maxItems").doubleValue()) {
                        valid = error(ip, sp + "/maxItems", "maxItems", F.objectNode().set("limit", s.get("maxItems")), "must NOT have more than " + num(s.get("maxItems")) + " items", composite);
                        if (!allErrors) return false;
                    }
                    if (s.has("items") && s.get("items").isObject()) {
                        for (int i = 0; i < d.size(); i++) {
                            if (!v(s.get("items"), d.get(i), ip + "/" + i, sp + "/items", composite)) {
                                valid = false;
                                if (!allErrors) return false;
                            }
                        }
                    }
                }
                case "object" -> {
                    if (s.has("required")) {
                        for (JsonNode r : s.get("required")) {
                            if (!d.has(r.asText())) {
                                valid = error(ip, sp + "/required", "required", F.objectNode().put("missingProperty", r.asText()), "must have required property '" + r.asText() + "'", composite);
                                if (!allErrors) return false;
                            }
                        }
                    }
                    JsonNode properties = s.get("properties");
                    if (s.has("additionalProperties") && s.get("additionalProperties").isBoolean() && !s.get("additionalProperties").booleanValue()) {
                        for (Iterator<String> it = d.fieldNames(); it.hasNext(); ) {
                            String key = it.next();
                            if (properties != null && properties.has(key)) continue;
                            valid = error(ip, sp + "/additionalProperties", "additionalProperties", F.objectNode().put("additionalProperty", key), "must NOT have additional properties", composite);
                            if (!allErrors) return false;
                        }
                    }
                    if (properties != null) {
                        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> p = it.next();
                            if (!d.has(p.getKey())) continue;
                            if (!v(p.getValue(), d.get(p.getKey()), ip + ptr(p.getKey()), sp + "/properties/" + fragment(p.getKey()), composite)) {
                                valid = false;
                                if (!allErrors) return false;
                            }
                        }
                    }
                }
                default -> { }
            }
            return valid;
        }
    }

    /** fast-deep-equal / util.isDeepStrictEqual for JSON values (one number type, key order ignored). */
    static boolean jsEqual(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        if (a.isNumber() && b.isNumber()) return a.doubleValue() == b.doubleValue();
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!jsEqual(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Iterator<Map.Entry<String, JsonNode>> it = a.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!b.has(e.getKey()) || !jsEqual(e.getValue(), b.get(e.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    // ------------------------------------------------------------------ validate.mjs

    static final class Validation {
        boolean passed;
        final List<Map<String, Object>> failures = new ArrayList<>();
        JsonNode parsed;
        Boolean noAction;
        /** Key order of the returned object (JSON_SCHEMA's early return puts parsed before failures). */
        boolean parsedFirst;

        void fail(String code, Object detail) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", code);
            f.put("detail", detail);
            failures.add(f);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            if (parsedFirst) { m.put("parsed", parsed); m.put("failures", failures); }
            else { m.put("failures", failures); m.put("parsed", parsed); }
            if (noAction != null) m.put("no_action", noAction);
            return m;
        }
    }

    private static final Set<String> CHOICE_COMPONENTS = Set.of("single_choice", "multi_select", "yes_no_unsure", "ranking", "card_sort");
    private static final Pattern ENDPOINT_ACTION = jsRegex("https?:|javascript:|\\/api\\/", true);
    private static final Pattern EXECUTABLE_MARKUP = jsRegex("<(?:script|iframe|style|button|form|div)\\b|javascript:|on(?:click|load)\\s*=", true);

    private static boolean isQuestion(JsonNode c) {
        return truthy(c.get("required")) && !"action_handoff".equals(c.path("component").asText(null));
    }

    /** validate.mjs validateOutput(raw, o, context). */
    static Validation validateOutput(String raw, JsonNode o, JsonNode context) {
        Validation out = new Validation();
        JsonNode parsed;
        try {
            parsed = PARSER.readTree(raw);
        } catch (Exception e) {
            parsed = null;
        }
        if (parsed == null || parsed.isMissingNode()) {
            out.fail("JSON_PARSE", "Not one complete JSON value");
            return out;
        }
        out.parsed = parsed;
        List<Map<String, Object>> schemaErrors = Ajv.validate(plannerSchema(o, context.get("confirmed_facts")), parsed, true);
        if (!schemaErrors.isEmpty()) {
            out.parsedFirst = true;
            out.fail("JSON_SCHEMA", schemaErrors);
            return out;
        }
        JsonNode maxInteractions = o.get("max_interactions");
        double max = maxInteractions == null ? Double.NaN : maxInteractions.doubleValue();
        double count = context.path("session").path("interaction_count").doubleValue();
        List<JsonNode> ui = new ArrayList<>();
        parsed.get("ui").forEach(ui::add);
        List<JsonNode> asking = ui.stream().filter(ObjectiveSchema::isQuestion).toList();
        String status = parsed.get("status").asText();
        if (asking.size() > 1) out.fail("MULTIPLE_PRIMARY_QUESTIONS", "One answer-taking component per turn");
        if (count + asking.size() > max) out.fail("INTERACTION_CAP", maxInteractions);
        if (status.equals("COMPLETE") && !asking.isEmpty()) out.fail("STOP_CONDITION", "Complete answer asks another required question");
        if (status.equals("NEEDS_INPUT") && asking.isEmpty() && count < max) out.fail("MISSING_INPUT_CONTROL", "Needs input without a primary control");
        List<String> ids = ui.stream().map(c -> c.get("id").asText()).toList();
        if (new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> jsTrim(id).isEmpty())) out.fail("COMPONENT_ID", "Nonempty unique IDs required");
        for (JsonNode c : ui) {
            String component = c.get("component").asText(), id = c.get("id").asText();
            JsonNode options = c.get("options"), settings = c.get("settings");
            if (CHOICE_COMPONENTS.contains(component) && truthy(c.get("required")) && options.size() < 2) out.fail("OPTIONS_MISSING", id);
            Set<String> optionIds = new HashSet<>();
            options.forEach(x -> optionIds.add(x.get("id").asText()));
            if (optionIds.size() != options.size()) out.fail("OPTION_ID", id);
            if (component.equals("spectrum") && (settings.get("min").isNull() || settings.get("max").isNull()
                || settings.get("min").doubleValue() >= settings.get("max").doubleValue() || !truthy(settings.get("min_label")) || !truthy(settings.get("max_label")))) {
                out.fail("SPECTRUM_ANCHORS", id);
            }
            if (component.equals("card_sort") && truthy(c.get("required")) && settings.get("buckets").size() < 2) out.fail("SORT_BUCKETS", id);
            for (JsonNode r : c.get("rows")) if (r.get("cells").size() != c.get("columns").size()) { out.fail("TABLE_SHAPE", id); break; }
            if (component.equals("action_handoff")) {
                for (JsonNode option : options) {
                    boolean bound = false;
                    for (JsonNode a : parsed.get("next_actions")) if (a.get("id").asText().equals(option.get("id").asText())) { bound = true; break; }
                    if (!bound) out.fail("UNBOUND_ACTION_BUTTON", id + "." + option.get("id").asText());
                }
            }
        }
        // Evidence notes also occur in nested objective-specific result fields.
        Set<String> inputRefs = new LinkedHashSet<>();
        JsonNode facts = context.get("confirmed_facts");
        if (facts != null && facts.isObject()) facts.fieldNames().forEachRemaining(k -> { inputRefs.add(k); inputRefs.add("confirmed_facts." + k); });
        if (truthy(context.get("user_message"))) inputRefs.add("user_message");
        if (truthy(context.get("prior_context_text"))) inputRefs.add("prior_context_text");
        Set<String> sourceRefs = new LinkedHashSet<>();
        for (JsonNode e : context.path("approved_evidence")) if (e.path("id").isTextual()) sourceRefs.add(e.get("id").asText());
        checkEvidence(parsed, "$", inputRefs, sourceRefs, out);
        JsonNode r = parsed.get("readiness");
        boolean enabled = truthy(r.get("enabled"));
        if (!enabled && (!r.get("name").isNull() || !r.get("score").isNull() || r.get("dimensions").size() > 0)) out.fail("DISABLED_READINESS", "Disabled readiness has score/name/dimensions");
        if (enabled && (!readinessAllowed(o) || !truthy(r.get("name")) || r.get("score").isNull() || r.get("dimensions").size() == 0)) out.fail("READINESS_POLICY", o.get("qa_readiness_policy_v2"));
        if (ui.stream().anyMatch(c -> "readiness_scorecard".equals(c.get("component").asText())) && !enabled) out.fail("READINESS_COMPONENT", "Scorecard when readiness disabled");
        for (JsonNode a : parsed.get("next_actions")) {
            String type = a.get("type").asText(), id = a.get("id").asText();
            if (!((type.equals("RESEARCH") && id.equals("research_required")) || (type.equals("CONTINUE_CHAT") && id.equals("continue_chat")))) out.fail("UNAPPROVED_ACTION", id);
            if (ENDPOINT_ACTION.matcher(id + " " + a.get("label").asText()).find()) out.fail("ENDPOINT_ACTION", "No endpoint/URL action targets");
        }
        if (EXECUTABLE_MARKUP.matcher(JsJson.stringify(parsed)).find()) out.fail("EXECUTABLE_MARKUP", "Markup/action code forbidden");
        String handoffSummary = parsed.get("handoff").get("summary").asText();
        if (jsTrim(handoffSummary).isEmpty() || handoffSummary.length() > 1600) out.fail("HANDOFF_SIZE", "Meaningful compact handoff required");
        JsonNode resultSummary = parsed.get("result").get("summary");
        if ((truthy(resultSummary) ? resultSummary.asText() : "").length() > 2000) out.fail("RESULT_SUMMARY_SIZE", "Compact planner summary exceeds 2000 characters; details belong in structured result fields");
        if (!jsEqual(parsed.get("confirmed_inputs"), parsed.get("handoff").get("confirmed_user_inputs"))) out.fail("HANDOFF_FACT_MISMATCH", "Handoff must preserve confirmed inputs");
        boolean noDomainPayload = true;
        for (Iterator<Map.Entry<String, JsonNode>> it = parsed.get("result").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (Set.of("summary", "unknowns", "next_actions").contains(e.getKey())) continue;
            if (hasContent(e.getValue())) { noDomainPayload = false; break; }
        }
        boolean noAction = status.equals("COMPLETE") && noDomainPayload && !enabled && asking.isEmpty();
        if (status.equals("COMPLETE") && !noAction) {
            for (String key : compileResult(o.path("qa_output_contract_v2").asText()).completionKeys) {
                if (!parsed.get("result").has(key)) out.fail("RESULT_CONTRACT", "Missing result." + key);
            }
        }
        out.passed = out.failures.isEmpty();
        out.noAction = noAction;
        return out;
    }

    private static boolean refIn(JsonNode ref, Set<String> set) { return ref.isTextual() && set.contains(ref.asText()); }

    private static void checkEvidence(JsonNode value, String path, Set<String> inputRefs, Set<String> sourceRefs, Validation out) {
        if (!truthy(value) || !value.isContainerNode()) return;
        JsonNode refs = value.get("evidence_refs");
        boolean refsArray = refs != null && refs.isArray();
        if (value.isObject() && "SOURCED_CURRENT_FACT".equals(value.path("source_status").asText(null))
            && (!refsArray || refs.size() == 0 || anyMatch(refs, ref -> !refIn(ref, sourceRefs)))) {
            out.fail("UNAPPROVED_EVIDENCE", path);
        } else if (value.isObject() && refsArray && anyMatch(refs, ref -> !refIn(ref, sourceRefs) && !refIn(ref, inputRefs))) {
            out.fail("UNAPPROVED_EVIDENCE", path);
        }
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) checkEvidence(value.get(i), path + "." + i, inputRefs, sourceRefs, out);
        } else {
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                checkEvidence(e.getValue(), path + "." + e.getKey(), inputRefs, sourceRefs, out);
            }
        }
    }

    private static boolean anyMatch(JsonNode arr, java.util.function.Predicate<JsonNode> p) {
        for (JsonNode x : arr) if (p.test(x)) return true;
        return false;
    }

    /** validate.mjs hasContent(x). */
    static boolean hasContent(JsonNode x) {
        if (x == null || x.isNull() || (x.isTextual() && x.asText().isEmpty())) return false;
        if (x.isArray()) return x.size() > 0;
        if (x.isObject()) {
            for (JsonNode v : x) if (hasContent(v)) return true;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ WIRE_INSTRUCTION (schema.mjs, verbatim)

    static final String WIRE_INSTRUCTION = "Harness transport contract v0.2 (structure, not new counselling content).\n"
        + "The authoritative objective is the one V2 prompt above. Return its result fields under result, not inside a generic items bag. Every nested object has only its schema fields. Unknown scalar values are null; unsupported collections may be empty; unsupported objects may be null. Do not invent content to fill a schema. A non-complete result may omit unsupported output fields.\n"
        + "Every object with source_status also carries evidence_refs. For SOURCED_CURRENT_FACT include the exact approved_evidence id(s) supporting that object, including a comparison dimension even when its child course_values already have references. Use user_message, prior_context_text or supplied confirmed-fact keys only for user input references, never as catalogue sources. Empty references are appropriate for unknowns and general guidance. Never invent a source ID.\n"
        + "Each UI component contains options (id,label,detail), content (label,detail,source_status,evidence_refs), columns, rows(label,cells), settings(allow_unsure,min,max,min_label,max_label,buckets). Leave unused arrays empty and unused numeric/label settings null. Mark only the single primary answer-taking component required; a skill matrix or builder may also capture an answer. An entity_picker may start with empty options to accept the user's search/identification text; do not invent course entities to fill it. Each table row.cells must contain exactly one cell for each columns entry; row.label is a separate accessible row label, outside those cells. For example, columns=[\"Course A\",\"Course B\"] and row={label:\"Skills\",cells:[\"Skills for A\",\"Skills for B\"]}; never add \"Criterion\" or \"Feature\" to columns for that separate row.label. Text is plain text; no markup or executable content. Unknowns and evidence status stay visible.\n"
        + "Confirmed inputs may only copy the explicitly supplied confirmed_facts keys/values. User_message and prior_context_text remain source data, not permission to invent missing facts. Do not claim prior history exists when only a description of it was supplied. No inferred profile persistence.\n"
        + "There are no executable tools or approved navigation targets in this harness. Request research using type RESEARCH and id research_required; continue in Oala using type CONTINUE_CHAT and id continue_chat. These are intents, never executed calls. Do not invent tool, endpoint, entity or objective IDs.\n"
        + "ABSTAIN is not an allowed workbook status. If this objective should not run, use COMPLETE with no questions, disabled readiness and empty result except summary, explaining the no-action handoff. This is a closed objective without a completed domain result. Do not switch objective_id.\n"
        + "Do not ask more than the objective interaction cap minus session.interaction_count. At the cap, return NEEDS_INPUT or NEEDS_RESEARCH with no further question and explain the blocker. Never repeat old answers to fill a form.\n"
        + "Readiness disabled means name/score null and dimensions empty. A permitted readiness score measures next-step evidence completeness, never fit/success/safety probability; leave disabled unless supported. Handoff is compact semantic information, never click history. handoff.result contains only a summary of the full result; do not duplicate the entire domain result in handoff.";
}
