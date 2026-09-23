package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.DialogueTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.yuzee.tokenlab.service.ConversationMemoryService.MAPPER;
import static com.yuzee.tokenlab.service.ConversationMemoryService.contentToString;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsGet;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsIterate;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsJoinElement;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsStrictEq;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsStringOrThrow;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsToString;
import static com.yuzee.tokenlab.service.ConversationMemoryService.jsTrim;
import static com.yuzee.tokenlab.service.ConversationMemoryService.or;
import static com.yuzee.tokenlab.service.ConversationMemoryService.truthy;

/**
 * Exact Java port of MultiTurnRequestBuilder.ts: builds the Gemini Content[] ({role, parts:[{text}]})
 * as a Jackson ArrayNode.
 */
@Service
public class MultiTurnRequestBuilder {

    /** Exact port of formatAssistantMessageRich(). */
    public String formatAssistantMessageRich(Object rawContentObj) {
        String rawContent = contentToString(rawContentObj);
        if (rawContent.isEmpty()) return "";
        String trimmed = jsTrim(rawContent);
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) return trimmed;

        try {
            JsonNode parsed = MAPPER.readTree(trimmed);
            if (!jsStrictEq(parsed.path("schema_version"), "1.3") && !jsStrictEq(parsed.path("schema_version"), "1.4")) return trimmed;

            JsonNode blocks = or(or(parsed.path("content_blocks"), parsed.path("blocks")), MAPPER.createArrayNode());
            List<String> textParts = new ArrayList<>();

            for (JsonNode block : jsIterate(blocks)) {
                JsonNode blockTextNode = or(or(jsGet(block, "text"), block.path("content")), MAPPER.getNodeFactory().textNode(""));
                String type = block.path("type").isTextual() ? block.path("type").textValue() : null;

                JsonNode title = block.path("title");
                boolean headingTitle = false;
                if ("heading".equals(type) && !title.isMissingNode() && !title.isNull()) {
                    headingTitle = !jsTrim(jsStringOrThrow(title)).isEmpty(); // block.title?.trim()
                }
                if (headingTitle) {
                    String h = jsStrictEq(block.path("level"), "h3") ? "###" : "##";
                    textParts.add(h + " " + jsTrim(title.textValue()));
                } else if (!jsTrim(jsStringOrThrow(blockTextNode)).isEmpty()) {
                    textParts.add(jsTrim(blockTextNode.textValue()));
                }

                if ("callout".equals(type) && !jsTrim(jsStringOrThrow(blockTextNode)).isEmpty()) {
                    // Callout already captured above; add variant for context
                    JsonNode v = block.path("variant");
                    String variant = truthy(v) && !jsStrictEq(v, "default") ? " [" + jsToString(v) + "]" : "";
                    if (!variant.isEmpty()) textParts.set(textParts.size() - 1, textParts.get(textParts.size() - 1) + variant);
                }

                JsonNode items = block.path("items");
                if (items.isArray() && items.size() > 0) {
                    List<String> itemLines = new ArrayList<>();
                    for (JsonNode it : items) {
                        JsonNode itTitle = jsGet(it, "title");
                        String label = truthy(itTitle) ? "**" + jsToString(itTitle) + "**" : "";
                        JsonNode bodyNode = or(it.path("text"), it.path("content"));
                        String body = truthy(bodyNode) ? jsToString(bodyNode) : "";
                        String val = truthy(it.path("value")) ? " [" + jsToString(it.path("value")) + "]" : "";
                        String st = truthy(it.path("status")) ? " [" + jsToString(it.path("status")) + "]" : "";
                        itemLines.add(jsTrim("- " + label + (!label.isEmpty() && !body.isEmpty() ? ": " : "") + body + val + st));
                    }
                    if (!itemLines.isEmpty()) textParts.add(String.join("\n", itemLines));
                }

                // Table/comparison: include criteria column as compact rows
                JsonNode columns = block.path("columns");
                JsonNode rows = block.path("rows");
                if (("table".equals(type) || "comparison".equals(type))
                    && columns.isArray() && rows.isArray() && rows.size() > 0) {
                    List<String> colLabels = new ArrayList<>();
                    for (JsonNode c : columns) colLabels.add(jsJoinElement(or(jsGet(c, "label"), c.path("key"))));
                    textParts.add("| " + String.join(" | ", colLabels) + " |");
                    for (JsonNode row : rows) {
                        JsonNode crit = jsGet(row, "criteria");
                        String criteria = truthy(crit) ? jsToString(crit) + ": " : "";
                        String cells = "";
                        if (row.path("cells").isArray()) {
                            List<String> cellVals = new ArrayList<>();
                            for (JsonNode c : row.path("cells")) {
                                JsonNode cv = jsGet(c, "value");
                                cellVals.add(truthy(cv) ? jsToString(cv) : "");
                            }
                            cells = String.join(" | ", cellVals);
                        }
                        if (!criteria.isEmpty() || !cells.isEmpty()) textParts.add("| " + criteria + cells + " |");
                    }
                }
            }

            // Question and options
            JsonNode interaction = parsed.path("interaction");
            JsonNode question = interaction.isMissingNode() || interaction.isNull() ? null : interaction.path("question");
            boolean hasQuestion = question != null && !question.isMissingNode() && !question.isNull()
                && !jsTrim(jsStringOrThrow(question)).isEmpty(); // interaction?.question?.trim()
            if (hasQuestion) {
                StringBuilder qLine = new StringBuilder("Question asked: \"" + jsTrim(question.textValue()) + "\"");
                JsonNode options = interaction.path("options");
                if (options.isArray() && options.size() > 0) {
                    List<String> opts = new ArrayList<>();
                    for (JsonNode o : options) {
                        JsonNode opt = or(jsGet(o, "label"), o.path("value"));
                        if (truthy(opt)) opts.add(jsToString(opt)); // .filter(Boolean)
                    }
                    if (!opts.isEmpty()) qLine.append("\nOptions: ").append(String.join(" | ", opts));
                }
                textParts.add(qLine.toString());
            }

            String modeStr = jsToString(or(or(parsed.path("current_mode"), parsed.path("state").path("active_response_mode")),
                MAPPER.getNodeFactory().textNode("standard")));
            String intentStr = jsToString(or(parsed.path("response_intent"), MAPPER.getNodeFactory().textNode("GUIDANCE")));
            return "[Mode: " + modeStr + " | Intent: " + intentStr + "]\n" + String.join("\n", textParts);
        } catch (Exception e) {
            return trimmed;
        }
    }

    /**
     * Exact port of buildMultiTurnContents():
     * - Contents array alternates user/model and ends with user.
     * - History turns without an assistant message are dropped from history.
     * - Capsule + summary are prepended to the first user turn in history, or to the current turn when there is no history.
     * - Current turn is always the last element (user role). Original default richHistory = true.
     */
    public ArrayNode buildMultiTurnContents(String careerCapsule, String summary, List<DialogueTurn> keptTurns,
                                            String currentUserInput, boolean richHistory) {
        ArrayNode contents = MAPPER.createArrayNode();

        List<String> preambleParts = new ArrayList<>();
        if (careerCapsule != null && !careerCapsule.isEmpty()) preambleParts.add(careerCapsule);
        String summaryTrim = summary != null ? jsTrim(summary) : "";
        if (!summaryTrim.isEmpty()) preambleParts.add("PREVIOUS_CONVERSATION_SUMMARY:\n" + summaryTrim);
        String preamble = String.join("\n\n", preambleParts);

        List<DialogueTurn> completeTurns = new ArrayList<>();
        if (keptTurns != null) for (DialogueTurn t : keptTurns) if (t.assistantMessage != null) completeTurns.add(t);

        if (completeTurns.isEmpty()) {
            // No history — preamble (if any) + raw user text, no CURRENT_USER_INPUT: prefix.
            String text = !preamble.isEmpty() ? preamble + "\n\n" + currentUserInput : currentUserInput;
            contents.add(contentNode("user", text));
            return contents;
        }

        DialogueTurn firstTurn = completeTurns.get(0);
        String firstUserText = !preamble.isEmpty() ? preamble + "\n\n" + firstTurn.userMessage.content : firstTurn.userMessage.content;
        contents.add(contentNode("user", firstUserText));
        contents.add(contentNode("model", format(firstTurn.assistantMessage.content, richHistory)));

        for (int i = 1; i < completeTurns.size(); i++) {
            DialogueTurn turn = completeTurns.get(i);
            contents.add(contentNode("user", turn.userMessage.content));
            contents.add(contentNode("model", format(turn.assistantMessage.content, richHistory)));
        }

        contents.add(contentNode("user", currentUserInput));
        return contents;
    }

    private String format(String content, boolean richHistory) {
        return richHistory ? formatAssistantMessageRich(content) : content;
    }

    /** Exact port of estimateMultiTurnTokens(). */
    public int estimateMultiTurnTokens(JsonNode contents) {
        int total = 0;
        for (JsonNode c : contents) {
            for (JsonNode p : c.path("parts")) {
                JsonNode text = p.path("text");
                total += ConversationMemoryService.estimateTokens(truthy(text) ? jsToString(text) : "");
            }
        }
        return total;
    }

    /** Exact port of estimateContentsTokens(): a text node is a single-text request, otherwise Content[]. */
    public int estimateContentsTokens(JsonNode contents) {
        if (contents.isTextual()) return ConversationMemoryService.estimateTokens(contents.textValue());
        return estimateMultiTurnTokens(contents);
    }

    private ObjectNode contentNode(String role, String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        ArrayNode parts = node.putArray("parts");
        parts.addObject().put("text", text);
        return node;
    }
}
