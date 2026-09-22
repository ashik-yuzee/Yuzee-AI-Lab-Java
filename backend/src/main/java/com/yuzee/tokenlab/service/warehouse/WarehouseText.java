package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small text/JSON cleanup helpers shared by the warehouse normalize/comparison/query logic. These
 * mirror the tiny duplicated helpers scattered across the old app's normalize.ts, comparison.ts,
 * linked-data.cjs, opportunities.cjs and provider-data.cjs ({@code cleanText}, {@code json},
 * {@code list}, {@code text}, {@code number}, {@code flag}, {@code norm}, {@code tokens}).
 */
final class WarehouseText {
    private WarehouseText() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMPTY = Pattern.compile(
        "^(?:missing(?:_or_not_verified)?|not_verified|unknown|not_applicable(?:_at_this_course_type)?|n/?a|null|none)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Set<String> STOPWORDS = Set.of(
        "the", "at", "in", "of", "and", "a", "an", "for", "to", "i", "want", "please",
        "course", "courses", "compare", "show", "me", "with", "what", "is", "are");

    /** Ported from normalize.ts's cleanText(): strips tags/underscores, collapses whitespace, filters placeholders. */
    static String cleanText(Object value, int max) {
        if (value == null || value instanceof Boolean) return null;
        String t = SPACES.matcher(TAGS.matcher(String.valueOf(value)).replaceAll(" ")).replaceAll(" ").trim();
        if (t.isEmpty() || EMPTY.matcher(t).matches()) return null;
        t = t.replace('_', ' ');
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String cleanText(Object value) { return cleanText(value, 900); }

    /** Short form used across linked-data.cjs/opportunities.cjs/provider-data.cjs (their `text()`). */
    static String text(Object value, int max) {
        return value == null ? "" : (cleanText(value, max) == null ? "" : cleanText(value, max));
    }

    static String text(Object value) { return text(value, 500); }

    /** Parses a JSON blob column; returns an empty/null node rather than throwing on malformed JSON. */
    static JsonNode json(Object value) {
        if (value == null) return MAPPER.nullNode();
        if (!(value instanceof String s) || s.isBlank()) return MAPPER.nullNode();
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return MAPPER.nullNode();
        }
    }

    /** Ported from normalize.ts's list(): flattens a JSON array (of strings or {label|name|description}) to strings. */
    static List<String> list(Object value, int max) {
        List<String> out = new ArrayList<>();
        JsonNode node = value instanceof JsonNode jn ? jn : json(value);
        if (node.isArray()) {
            for (JsonNode item : node) {
                String cleaned = itemText(item);
                if (cleaned != null) out.add(cleaned);
                if (out.size() >= max) break;
            }
            return out;
        }
        String single = cleanText(value);
        if (single != null) out.add(single);
        return out;
    }

    static List<String> list(Object value) { return list(value, 8); }

    private static String itemText(JsonNode item) {
        if (item.isObject()) {
            if (item.hasNonNull("label")) return cleanText(item.get("label").asText());
            if (item.hasNonNull("name")) return cleanText(item.get("name").asText());
            if (item.hasNonNull("description")) return cleanText(item.get("description").asText());
            return null;
        }
        return cleanText(item.isTextual() ? item.asText() : item.toString());
    }

    /** Ported from normalize.ts's url(): only http(s) survives. */
    static String url(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        try {
            java.net.URI uri = java.net.URI.create(s);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            String lower = scheme.toLowerCase();
            return (lower.equals("http") || lower.equals("https")) ? uri.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ported from normalize.ts's score(): a 0-100 finite number, else null. */
    static Double score(Object value) {
        if (value == null || "".equals(value)) return null;
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(d) && d >= 0 && d <= 100 ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Double number(Object value) {
        if (value == null || "".equals(value)) return null;
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(d) ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Integer intNumber(Object value) {
        Double d = number(value);
        return d == null ? null : d.intValue();
    }

    /** Ported from linked-data.cjs's flag(): tri-state 1/0 -> true/false/null. */
    static Boolean flag(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        if (s.equals("1")) return Boolean.TRUE;
        if (s.equals("0")) return Boolean.FALSE;
        return null;
    }

    /** label(key) from linked-data.cjs: snake_case -> "Title case first letter". */
    static String label(String key) {
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    static String safeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Ported from catalogue-worker.cjs's terms()/opportunities.cjs's tokens(): lowercase word tokens, stopwords dropped. */
    static List<String> terms(String value, int max) {
        if (value == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        java.util.regex.Matcher m = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS)
            .matcher(value.toLowerCase());
        while (m.find() && seen.size() < max) {
            String w = m.group();
            if (!STOPWORDS.contains(w)) seen.add(w);
        }
        return new ArrayList<>(seen);
    }

    /** Ported from opportunities.cjs's clause()/tokens(): builds an FTS5 MATCH clause, prefix-matching longer words. */
    static String matchClause(String phrase) {
        List<String> tokens = terms(phrase, 8);
        if (tokens.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append(" AND ");
            String w = tokens.get(i);
            sb.append('"').append(w.replace("\"", "\"\"")).append('"');
            if (w.length() > 2) sb.append('*');
        }
        return sb.toString();
    }
}
