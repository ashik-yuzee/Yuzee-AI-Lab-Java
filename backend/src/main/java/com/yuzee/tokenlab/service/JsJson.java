package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;

/**
 * JSON.stringify output from Jackson: lowercase \\u00xx escapes and JavaScript number text (1 not 1.0, 1e-7 not
 * 1.0E-7). The original writes every SSE frame, prompt fragment and hash input with JSON.stringify.
 */
public final class JsJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();

    private JsJson() {
    }

    /** JSON.stringify(value) ("null" if Jackson cannot write it). */
    public static String stringify(Object value) {
        return stringify(MAPPER, value);
    }

    /** JSON.stringify(value) using the given mapper's serializers (its hex/number settings are overridden). */
    public static String stringify(ObjectMapper mapper, Object value) {
        try {
            StringWriter w = new StringWriter();
            JsonGenerator g = MAPPER.getFactory().createGenerator(w);
            mapper.writeValue(new JsNumbers(g), value);
            g.flush();
            return escapeLoneSurrogates(w.toString());
        } catch (IOException e) {
            return "null";
        }
    }

    /** JSON.stringify(value, null, 2): two-space indent, `"key": value`, empty containers as {} and []. */
    public static String pretty(Object value) {
        return pretty(MAPPER, value);
    }

    /** JSON.stringify(value, null, 2) using the given mapper's serializers. */
    public static String pretty(ObjectMapper mapper, Object value) {
        try {
            StringWriter w = new StringWriter();
            JsonGenerator g = MAPPER.getFactory().createGenerator(w);
            g.setPrettyPrinter(new JsPretty());
            mapper.writeValue(new JsNumbers(g), value);
            g.flush();
            return escapeLoneSurrogates(w.toString());
        } catch (IOException e) {
            return "null";
        }
    }

    /** V8's JSON.stringify indentation (Jackson's DefaultPrettyPrinter writes `"key" : v`, `{ }` and inline arrays). */
    private static final class JsPretty implements com.fasterxml.jackson.core.PrettyPrinter {
        private int depth;

        private void newline(JsonGenerator g) throws IOException { g.writeRaw('\n'); g.writeRaw("  ".repeat(depth)); }

        @Override public void writeRootValueSeparator(JsonGenerator g) { }
        @Override public void writeStartObject(JsonGenerator g) throws IOException { g.writeRaw('{'); depth++; }
        @Override public void beforeObjectEntries(JsonGenerator g) throws IOException { newline(g); }
        @Override public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException { g.writeRaw(": "); }
        @Override public void writeObjectEntrySeparator(JsonGenerator g) throws IOException { g.writeRaw(','); newline(g); }
        @Override public void writeEndObject(JsonGenerator g, int entries) throws IOException { depth--; if (entries > 0) newline(g); g.writeRaw('}'); }
        @Override public void writeStartArray(JsonGenerator g) throws IOException { g.writeRaw('['); depth++; }
        @Override public void beforeArrayValues(JsonGenerator g) throws IOException { newline(g); }
        @Override public void writeArrayValueSeparator(JsonGenerator g) throws IOException { g.writeRaw(','); newline(g); }
        @Override public void writeEndArray(JsonGenerator g, int values) throws IOException { depth--; if (values > 0) newline(g); g.writeRaw(']'); }
    }

    /** Well-formed JSON.stringify: an unpaired surrogate is written as a lowercase backslash-u-dxxx escape (Jackson writes it raw). */
    static String escapeLoneSurrogates(String json) {
        StringBuilder sb = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            boolean paired = Character.isHighSurrogate(c) ? i + 1 < json.length() && Character.isLowSurrogate(json.charAt(i + 1))
                : Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(json.charAt(i - 1));
            if (Character.isSurrogate(c) && !paired) {
                if (sb == null) sb = new StringBuilder(json.length() + 16).append(json, 0, i);
                sb.append('\\').append('u').append(Integer.toHexString(c));
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? json : sb.toString();
    }

    private static final ObjectMapper PARSER = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** JSON.parse(text): exactly one JSON value, else an IllegalStateException (V8's message for empty input). */
    public static com.fasterxml.jackson.databind.JsonNode parse(String text) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = PARSER.readTree(text);
            if (n == null || n.isMissingNode()) throw new IllegalStateException("Unexpected end of JSON input");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e.getOriginalMessage() == null ? "Unexpected end of JSON input" : e.getOriginalMessage());
        }
    }

    /** JSON.stringify(string). */
    public static String quote(String s) {
        return stringify(s);
    }

    /** Number.prototype.toString() (JSON.stringify writes NaN/Infinity as null). */
    public static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == 0) return "0";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros(); // shortest round-trip digits
        String digits = bd.unscaledValue().toString();
        int k = digits.length(), n = k - bd.scale();
        StringBuilder sb = new StringBuilder(d < 0 ? "-" : "");
        if (k <= n && n <= 21) sb.append(digits).append("0".repeat(n - k));
        else if (0 < n && n <= 21) sb.append(digits, 0, n).append('.').append(digits, n, k);
        else if (-6 < n && n <= 0) sb.append("0.").append("0".repeat(-n)).append(digits);
        else {
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            sb.append('e').append(n - 1 >= 0 ? "+" : "-").append(Math.abs(n - 1));
        }
        return sb.toString();
    }

    private static final long MAX_SAFE = 9007199254740992L;

    private static final class JsNumbers extends JsonGeneratorDelegate {
        JsNumbers(JsonGenerator d) { super(d, false); }

        @Override public void writeNumber(double v) throws IOException { delegate.writeNumber(number(v)); }
        @Override public void writeNumber(float v) throws IOException { delegate.writeNumber(number(Double.parseDouble(Float.toString(v)))); }
        @Override public void writeNumber(BigDecimal v) throws IOException { delegate.writeNumber(number(v.doubleValue())); }
        // JSON.parse gives every number a double: integers beyond 2^53 print as the nearest double does.
        @Override public void writeNumber(long v) throws IOException {
            if (Math.abs(v) <= MAX_SAFE) delegate.writeNumber(v); else delegate.writeNumber(number((double) v));
        }
        @Override public void writeNumber(java.math.BigInteger v) throws IOException { delegate.writeNumber(number(v.doubleValue())); }
        @Override public void close() throws IOException { delegate.close(); }
        @Override public void flush() throws IOException { delegate.flush(); }
    }
}
