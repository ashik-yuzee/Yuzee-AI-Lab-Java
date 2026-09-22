package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Port of streamBlocks.ts's {@code PathwayBlockStream}: a hand-written incremental parser that
 * reads only COMPLETE objects out of the top-level {@code content_blocks[]} array as raw model
 * text streams in, character by character. Tracks quoted-string/escape state and brace/bracket
 * depth so a brace inside a string, an escaped quote, or an arbitrary chunk boundary never fires
 * a false "block complete" signal -- and a block cut off mid-object (the stream stops before its
 * closing brace) is simply never emitted. No repaired/partial JSON is ever shown to the caller.
 *
 * NOT a Spring bean: like its TS counterpart this is stateful per stream (offset/depth/quote
 * tracking, seen-id set) and must be instantiated fresh with {@code new PathwayBlockStreamParser()}
 * for every generation attempt.
 */
public class PathwayBlockStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema BLOCK_SCHEMA = loadBlockSchema();

    private final StringBuilder text = new StringBuilder();
    private int offset = 0;
    private int depth = 0;
    private boolean quoted = false;
    private boolean escaped = false;
    private int stringStart = 0;
    private String rootKey = "";
    private char previous = 0;
    private boolean inBlocks = false;
    private int blockStart = -1;
    private final Set<String> ids = new HashSet<>();

    /**
     * Feed the next raw text delta from the stream. Returns any newly-completed, schema-valid
     * content blocks that carry a unique, nonempty id. A block still open when this delta's text
     * runs out -- including one permanently cut off because the stream stopped mid-object -- is
     * never returned by this or any later call.
     */
    public List<JsonNode> push(String delta) {
        text.append(delta);
        List<JsonNode> blocks = new ArrayList<>();
        for (; offset < text.length(); offset++) {
            char c = text.charAt(offset);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    quoted = false;
                    if (depth == 1) {
                        try {
                            rootKey = MAPPER.readTree(text.substring(stringStart, offset + 1)).asText();
                        } catch (Exception e) {
                            rootKey = "";
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                stringStart = offset;
                continue;
            }
            if (c == '[') {
                if (depth == 1 && previous == ':' && "content_blocks".equals(rootKey)) inBlocks = true;
                depth++;
            } else if (c == '{') {
                if (inBlocks && depth == 2) blockStart = offset;
                depth++;
            } else if (c == '}' || c == ']') {
                if (c == '}' && inBlocks && depth == 3 && blockStart >= 0) {
                    try {
                        JsonNode block = MAPPER.readTree(text.substring(blockStart, offset + 1));
                        String id = block.path("id").asText("");
                        if (!id.isEmpty() && !ids.contains(id) && isValidBlock(block)) {
                            ids.add(id);
                            blocks.add(block);
                        }
                    } catch (Exception ignored) {
                        // A malformed draft is never repaired or rendered.
                    }
                    blockStart = -1;
                }
                if (c == ']' && inBlocks && depth == 2) inBlocks = false;
                depth--;
            }
            if (!Character.isWhitespace(c)) previous = c;
        }
        return blocks;
    }

    private static boolean isValidBlock(JsonNode block) {
        return BLOCK_SCHEMA.validate(block).isEmpty();
    }

    private static JsonSchema loadBlockSchema() {
        try (InputStream is = new ClassPathResource("prompts/response-schema-v1.3.json").getInputStream()) {
            JsonNode root = MAPPER.readTree(is);
            JsonNode itemSchema = root.path("properties").path("content_blocks").path("items");
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(itemSchema);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load content_blocks item schema", e);
        }
    }
}
