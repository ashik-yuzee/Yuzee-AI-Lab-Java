package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small text/JSON helpers of the old app, each kept with its own exact semantics because they
 * differ subtly: normalize.ts ({@code cleanText}, {@code list}, {@code url}, {@code score}),
 * linked-data.cjs ({@code text}, {@code list}, {@code number}, {@code flag}), opportunities.cjs
 * ({@code clean}, {@code list}, {@code tokens}, {@code clause}), provider-data.cjs ({@code text}) and
 * catalogue-worker.cjs ({@code terms}). Values are either raw JDBC column values or Jackson nodes.
 */
final class WarehouseText {
    private WarehouseText() {}

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMPTY = Pattern.compile(
        "^(?:missing(?:_or_not_verified)?|not_verified|unknown|not_applicable(?:_at_this_course_type)?|n/?a|null|none)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex("\\s+", false); // JS \s
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOPWORDS = Set.of(
        "the", "at", "in", "of", "and", "a", "an", "for", "to", "i", "want", "please",
        "course", "courses", "compare", "show", "me", "with", "what", "is", "are");

    // ---- JS value semantics ----------------------------------------------------------------

    /** JS {@code String(x)} for JDBC values and Jackson nodes (numbers print without a trailing ".0"). */
    static String jsString(Object v) {
        if (v == null) return "null";
        if (v instanceof JsonNode n) {
            if (n.isMissingNode()) return "undefined";
            if (n.isNull()) return "null";
            if (n.isTextual()) return n.asText();
            if (n.isNumber()) return jsNumber(n.numberValue());
            if (n.isBoolean()) return String.valueOf(n.booleanValue());
            if (n.isArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode item : n) parts.add(item.isNull() || item.isMissingNode() ? "" : jsString(item));
                return String.join(",", parts);
            }
            return "[object Object]";
        }
        if (v instanceof Number num) return jsNumber(num);
        return String.valueOf(v);
    }

    private static String jsNumber(Number num) {
        if (num instanceof Double || num instanceof Float || num instanceof java.math.BigDecimal) {
            double d = num.doubleValue();
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
            if (d == Math.rint(d) && Math.abs(d) < 1e21) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(num);
    }

    /** JS truthiness. */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof JsonNode n) {
            if (n.isMissingNode() || n.isNull()) return false;
            if (n.isTextual()) return !n.asText().isEmpty();
            if (n.isNumber()) { double d = n.asDouble(); return d != 0 && !Double.isNaN(d); }
            if (n.isBoolean()) return n.booleanValue();
            return true;
        }
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Number num) { double d = num.doubleValue(); return d != 0 && !Double.isNaN(d); }
        if (v instanceof Boolean b) return b;
        return true;
    }

    /** JS {@code a||b||c}: the first truthy value, else the last one. */
    static Object or(Object... values) {
        for (int i = 0; i < values.length - 1; i++) if (truthy(values[i])) return values[i];
        return values[values.length - 1];
    }

    private static boolean nullish(Object v) {
        return v == null || (v instanceof JsonNode n && (n.isNull() || n.isMissingNode()));
    }

    /** JS {@code a??b}. */
    static Object coalesce(Object a, Object b) { return nullish(a) ? b : a; }

    private static boolean isObjectLike(Object v) {
        return v instanceof JsonNode n && (n.isObject() || n.isArray());
    }

    private static String strip(String s) {
        return com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim(SPACES.matcher(TAGS.matcher(s).replaceAll(" ")).replaceAll(" "));
    }

    private static String slice(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    // ---- normalize.ts ----------------------------------------------------------------------

    /** normalize.ts cleanText(): objects/booleans/nullish -> null; strips tags, drops placeholders, underscores -> spaces. */
    static String cleanText(Object v, int max) {
        if (nullish(v) || v instanceof Boolean || isObjectLike(v) || (v instanceof JsonNode n && n.isBoolean())) return null;
        String t = strip(jsString(v));
        return t.isEmpty() || EMPTY.matcher(t).matches() ? null : slice(t.replace('_', ' '), max);
    }

    static String cleanText(Object v) { return cleanText(v, 900); }

    /** normalize.ts json(): non-strings pass through ({@code v??{}}); strings parse, else {}. */
    static JsonNode json(Object v) {
        if (v instanceof JsonNode n) return n.isNull() || n.isMissingNode() ? MAPPER.createObjectNode() : n;
        if (!(v instanceof String s)) return v == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(v);
        try {
            JsonNode parsed = MAPPER.readTree(s);
            return parsed == null || parsed.isMissingNode() ? MAPPER.createObjectNode() : parsed;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** normalize.ts list(): arrays map label||name||description; any other value becomes [cleanText(v)]. */
    static List<String> list(Object v, int max) {
        List<String> out = new ArrayList<>();
        if (v instanceof JsonNode n && n.isArray()) {
            for (JsonNode x : n) {
                String t = cleanText(x.isObject() || x.isArray() || x.isNull()
                    ? or(x.path("label"), x.path("name"), x.path("description")) : x);
                if (t != null) out.add(t);
                if (out.size() >= max) break;
            }
            return out;
        }
        String single = cleanText(v);
        if (single != null) out.add(single);
        return out;
    }

    static List<String> list(Object v) { return list(v, 8); }

    /** normalize.ts url(): WHATWG-style parse; only http(s) survives, returned normalized. */
    static String url(Object value) {
        if (nullish(value)) return null;
        try {
            java.net.URI u = new java.net.URI(jsString(value).trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || u.getRawAuthority() == null) return null;
            String host = u.getHost() == null ? u.getRawAuthority() : u.getRawAuthority().replace(u.getHost(), u.getHost().toLowerCase(java.util.Locale.ROOT));
            if (scheme.equals("http") && host.endsWith(":80")) host = host.substring(0, host.length() - 3);
            if (scheme.equals("https") && host.endsWith(":443")) host = host.substring(0, host.length() - 4);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return scheme + "://" + host + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "")
                + (u.getRawFragment() != null ? "#" + u.getRawFragment() : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** normalize.ts score(): a finite 0-100 number, else null. */
    static Double score(Object v) {
        if (nullish(v) || "".equals(v) || (v instanceof JsonNode n && n.isTextual() && n.asText().isEmpty())) return null;
        Double d = jsNumberValue(v);
        return d != null && Double.isFinite(d) && d >= 0 && d <= 100 ? d : null;
    }

    /** JS {@code Number(x)} for the value shapes the readers see (null when NaN). */
    static Double jsNumberValue(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNumber()) return n.asDouble();
            if (n.isBoolean()) return n.booleanValue() ? 1d : 0d;
            if (n.isNull()) return 0d;
            if (!n.isTextual()) return null;
            v = n.asText();
        }
        if (v == null) return 0d;
        if (v instanceof Number num) return num.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        // StringToNumber: JS whitespace trimmed; decimal, Infinity or 0x/0o/0b integer literals only.
        String s = com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim(String.valueOf(v));
        if (s.isEmpty()) return 0d;
        if (s.matches("[+-]?Infinity")) return s.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (s.matches("0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+")) return new java.math.BigInteger(s.substring(2), "xXoObB".indexOf(s.charAt(1)) / 2 == 0 ? 16 : "xXoObB".indexOf(s.charAt(1)) / 2 == 1 ? 8 : 2).doubleValue();
        if (!s.matches("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) return null;
        return Double.parseDouble(s);
    }

    /** snake_case -> "Snake case". */
    static String label(String key) {
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ---- linked-data.cjs / opportunities.cjs / provider-data.cjs -------------------------

    /** linked-data.cjs text() (default 500) and opportunities.cjs clean() (default 600): nullish -> "". */
    static String text(Object v, int n) {
        if (v == null || (v instanceof JsonNode node && (node.isNull() || node.isMissingNode()))) return "";
        return slice(strip(jsString(v)), n);
    }

    static String text(Object v) { return text(v, 500); }

    /** provider-data.cjs text(): nullish or empty -> null, default 700. */
    static String providerText(Object v) {
        String t = text(v, 700);
        return v == null || t.isEmpty() ? null : t;
    }

    /** linked-data.cjs list(): parsed arrays map name||label||description; other strings split on " | ". */
    static List<String> linkedList(Object x, int n) {
        JsonNode parsed = x instanceof String s ? json(s) : x instanceof JsonNode node ? node : MissingNode.getInstance();
        List<Object> items = new ArrayList<>();
        if (parsed.isArray()) parsed.forEach(items::add);
        else if (x instanceof String s) items.addAll(Arrays.asList(s.split(" \\| ", -1)));
        List<String> out = new ArrayList<>();
        for (Object v : items) {
            String t = text(v instanceof JsonNode node && (node.isObject() || node.isArray() || node.isNull())
                ? or(node.path("name"), node.path("label"), node.path("description")) : v, 300);
            if (!t.isEmpty()) out.add(t);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** opportunities.cjs list(): parsed arrays only, items name||skill_name||label, 160 chars. */
    static List<String> oppList(Object v, int max) {
        JsonNode parsed;
        if (v instanceof String s) {
            try { parsed = MAPPER.readTree(s); } catch (Exception e) { parsed = null; }
        } else parsed = v instanceof JsonNode node ? node : null;
        List<String> out = new ArrayList<>();
        if (parsed == null || !parsed.isArray()) return out;
        for (JsonNode x : parsed) {
            String t = text(x.isObject() || x.isArray() || x.isNull() ? or(x.path("name"), x.path("skill_name"), x.path("label")) : x, 160);
            if (!t.isEmpty()) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    static List<String> oppList(Object v) { return oppList(v, 12); }

    /** linked-data.cjs number(): finite number, else null ("" and nullish are null). */
    static Double number(Object v) {
        if (nullish(v) || "".equals(v)) return null;
        Double d = jsNumberValue(v);
        return d != null && Double.isFinite(d) ? d : null;
    }

    /** number() as a JSON value: integral values serialize without a trailing ".0", like JS. */
    static Number num(Object v) {
        Double d = number(v);
        if (d == null) return null;
        return d == Math.rint(d) && Math.abs(d) < 9e15 ? (Number) (long) d.doubleValue() : d;
    }

    /** linked-data.cjs flag(): 1/'1' -> true, 0/'0' -> false, else null. */
    static Boolean flag(Object v) {
        if (v instanceof Number n && !(v instanceof Double || v instanceof Float)) {
            long l = n.longValue();
            return l == 1 ? Boolean.TRUE : l == 0 ? Boolean.FALSE : null;
        }
        if (v instanceof Number n) return n.doubleValue() == 1 ? Boolean.TRUE : n.doubleValue() == 0 ? Boolean.FALSE : null;
        if ("1".equals(v)) return Boolean.TRUE;
        if ("0".equals(v)) return Boolean.FALSE;
        return null;
    }

    /** JS {@code x===1||x==='1'}. */
    static boolean isOne(Object v) {
        return (v instanceof Number n && n.doubleValue() == 1) || "1".equals(v);
    }

    static String safeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> words(String lower) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(lower);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** provider-data.cjs: {@code String(q).toLowerCase(java.util.Locale.ROOT).match(/[\p{L}\p{N}]+/gu)?.slice(0,8)} (no stopwords). */
    static List<String> providerWords(String q) {
        List<String> w = words(q.toLowerCase(java.util.Locale.ROOT));
        return w.size() > 8 ? w.subList(0, 8) : w;
    }

    /** opportunities.cjs tokens(): norm(v) words, first 8 (no dedupe, no stopwords). */
    static List<String> tokens(Object v) {
        List<String> w = words(text(v, 160).toLowerCase(java.util.Locale.ROOT));
        return w.size() > 8 ? w.subList(0, 8) : w;
    }

    /** opportunities.cjs clause(): {@code "w"*} for words longer than 2, joined by AND; "" when empty. */
    static String clause(Object v) {
        List<String> out = new ArrayList<>();
        for (String w : tokens(v)) out.add('"' + w + '"' + (w.length() > 2 ? "*" : ""));
        return String.join(" AND ", out);
    }

    private static final Pattern ROMAN = Pattern.compile("^(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)$");

    /** catalogue-worker.cjs terms() + search() clause: deduped non-stopword words (max 14); no prefix for numerals/digits. */
    static String catalogueClause(String text) {
        List<String> out = new ArrayList<>();
        for (String w : new LinkedHashSet<>(words(String.valueOf(text).toLowerCase(java.util.Locale.ROOT)))) {
            if (STOPWORDS.contains(w)) continue;
            if (out.size() >= 14) break;
            boolean prefix = w.length() > 2 && !ROMAN.matcher(w).matches() && !w.matches(".*[0-9].*");
            out.add('"' + w + '"' + (prefix ? "*" : ""));
        }
        return String.join(" AND ", out);
    }
}
