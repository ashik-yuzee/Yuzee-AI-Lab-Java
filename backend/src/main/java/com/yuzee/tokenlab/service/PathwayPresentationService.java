package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * Port of miniPathway/presentation.ts's {@code miniPathwayPanelResponse()}: splits a
 * table/comparison block that carries a leading {@code text} intro into two blocks (a plain
 * text intro block followed by the table/comparison with its own {@code text} cleared), so a
 * narrow side-panel layout never has to stack an introduction above a table inside one block.
 * The shared renderer only ever displays a table block's rows, never its {@code text} -- so
 * without this split, the intro sentence would silently disappear in the panel. Stored JSON,
 * cell values and row order are otherwise unchanged.
 */
@Service
public class PathwayPresentationService {

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode miniPathwayPanelResponse(JsonNode response) {
        ObjectNode out = response.deepCopy();
        ArrayNode newBlocks = mapper.createArrayNode();
        for (JsonNode block : response.path("content_blocks")) {
            String type = block.path("type").asText("");
            String text = block.path("text").asText("");
            boolean splittable = ("table".equals(type) || "comparison".equals(type)) && !text.isEmpty();
            if (!splittable) {
                newBlocks.add(block);
                continue;
            }
            ObjectNode intro = block.deepCopy();
            intro.put("id", block.path("id").asText("") + "-intro");
            intro.put("type", "text");
            intro.put("title", "");
            intro.set("columns", mapper.createArrayNode());
            intro.set("rows", mapper.createArrayNode());
            intro.set("items", mapper.createArrayNode());
            newBlocks.add(intro);

            ObjectNode rest = block.deepCopy();
            rest.put("text", "");
            newBlocks.add(rest);
        }
        out.set("content_blocks", newBlocks);
        return out;
    }
}
