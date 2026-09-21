package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Java port of MultiTurnRequestBuilder.ts.
 * Builds a strictly-alternating user/model Gemini Content[] array (as a Jackson ArrayNode in the
 * {role, parts:[{text}]} shape the Gemini REST API expects) from the retained conversation turns.
 */
@Service
public class MultiTurnRequestBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class Turn {
        final ChatMessage user;
        final ChatMessage assistant;

        Turn(ChatMessage user, ChatMessage assistant) {
            this.user = user;
            this.assistant = assistant;
        }
    }

    /**
     * Rules (ported from buildMultiTurnContents):
     * - Contents array must alternate user/model and end with user.
     * - History turns without an assistant message are dropped (rejected/pending responses must
     *   not appear as model turns) — wherever in the list they occur, not just trailing.
     * - Career capsule + summary are prepended to the first user turn in history, or to the
     *   current turn when there is no history.
     * - Current turn is always the last element (user role).
     */
    public ArrayNode buildMultiTurnContents(List<ChatMessage> retainedTurns, String careerCapsuleText,
                                             String summaryText, Object currentUserInput) {
        List<Turn> turns = pairTurns(retainedTurns);

        List<String> preambleParts = new ArrayList<>();
        if (careerCapsuleText != null && !careerCapsuleText.isBlank()) preambleParts.add(careerCapsuleText);
        if (summaryText != null && !summaryText.isBlank()) {
            preambleParts.add("PREVIOUS_CONVERSATION_SUMMARY:\n" + summaryText.trim());
        }
        String preamble = String.join("\n\n", preambleParts);
        String currentText = stringifyUserInput(currentUserInput);

        ArrayNode contents = mapper.createArrayNode();

        if (turns.isEmpty()) {
            // No history — send preamble (if any) + raw user text. No "CURRENT_USER_INPUT:" label:
            // that shifts the model's interpretation vs AI Studio's plain-text first turn.
            String text = preamble.isEmpty() ? currentText : preamble + "\n\n" + currentText;
            contents.add(contentNode("user", text));
            return contents;
        }

        Turn first = turns.get(0);
        String firstUserText = preamble.isEmpty()
            ? contentToString(first.user.getContent())
            : preamble + "\n\n" + contentToString(first.user.getContent());
        contents.add(contentNode("user", firstUserText));
        contents.add(contentNode("model", formatAssistantMessageRich(first.assistant.getContent())));

        for (int i = 1; i < turns.size(); i++) {
            Turn t = turns.get(i);
            contents.add(contentNode("user", contentToString(t.user.getContent())));
            contents.add(contentNode("model", formatAssistantMessageRich(t.assistant.getContent())));
        }

        contents.add(contentNode("user", currentText));
        return contents;
    }

    /** Unified token estimator over a built Content[] array (same ~4 chars/token heuristic as ConversationMemoryService). */
    public int estimateContentsTokens(ArrayNode contents) {
        int total = 0;
        for (JsonNode item : contents) {
            for (JsonNode part : item.path("parts")) {
                String clean = part.path("text").asText("").trim();
                if (!clean.isEmpty()) {
                    int words = clean.split("\\s+").length;
                    total += Math.max(1, (int) Math.ceil(clean.length() * 0.26 + words * 0.15));
                }
            }
        }
        return total;
    }

    /**
     * Pairs a flat, chronologically-ordered message list into user/assistant turns. Any user
     * message not immediately followed by an assistant message (rejected, pending, or a trailing
     * unanswered turn) has no partner and is therefore excluded from the paired list, matching
     * the TS `completeTurns = keptTurns.filter(t => t.assistantMessage != null)` behaviour.
     */
    private List<Turn> pairTurns(List<ChatMessage> messages) {
        List<Turn> turns = new ArrayList<>();
        if (messages == null) return turns;
        ChatMessage pendingUser = null;
        for (ChatMessage m : messages) {
            if ("user".equals(m.getRole())) {
                pendingUser = m; // any previously unclosed pendingUser (no assistant) is dropped
            } else if ("assistant".equals(m.getRole()) && pendingUser != null) {
                turns.add(new Turn(pendingUser, m));
                pendingUser = null;
            }
        }
        return turns;
    }

    private ObjectNode contentNode(String role, String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        ArrayNode parts = mapper.createArrayNode();
        ObjectNode part = mapper.createObjectNode();
        part.put("text", text != null ? text : "");
        parts.add(part);
        node.set("parts", parts);
        return node;
    }

    private String contentToString(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        try {
            return mapper.writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    private String stringifyUserInput(Object input) {
        if (input == null) return "";
        if (input instanceof String s) return s.trim();
        try {
            return mapper.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    /**
     * Richer assistant-message formatter for multi-turn history: extends the compact
     * ConversationMemoryService.formatAssistantMessageForContext formatter with table/comparison
     * rows, callout variants, and question+options. Ported from
     * MultiTurnRequestBuilder.formatAssistantMessageRich (TS). Output stays compact plain text —
     * no JSON is ever dumped into history.
     */
    public String formatAssistantMessageRich(Object rawContent) {
        String raw = contentToString(rawContent);
        if (raw.isEmpty()) return "";
        String trimmed = raw.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) return trimmed;

        try {
            JsonNode parsed = mapper.readTree(trimmed);
            String schemaVersion = parsed.path("schema_version").asText("");
            if (!"1.3".equals(schemaVersion) && !"1.4".equals(schemaVersion)) return trimmed;

            JsonNode blocks = parsed.has("content_blocks") ? parsed.path("content_blocks") : parsed.path("blocks");
            List<String> textParts = new ArrayList<>();

            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    String type = block.path("type").asText("");
                    String blockText = block.has("text") ? block.path("text").asText("") : block.path("content").asText("");

                    if ("heading".equals(type) && !block.path("title").asText("").trim().isEmpty()) {
                        String h = "h3".equals(block.path("level").asText("")) ? "###" : "##";
                        textParts.add(h + " " + block.path("title").asText("").trim());
                    } else if (!blockText.trim().isEmpty()) {
                        textParts.add(blockText.trim());
                    }

                    if ("callout".equals(type) && !blockText.trim().isEmpty() && !textParts.isEmpty()) {
                        String variant = block.path("variant").asText("");
                        if (!variant.isEmpty() && !"default".equals(variant)) {
                            int lastIdx = textParts.size() - 1;
                            textParts.set(lastIdx, textParts.get(lastIdx) + " [" + variant + "]");
                        }
                    }

                    JsonNode items = block.path("items");
                    if (items.isArray() && items.size() > 0) {
                        List<String> itemLines = new ArrayList<>();
                        for (JsonNode it : items) {
                            String itTitle = it.path("title").asText("");
                            String label = itTitle.isEmpty() ? "" : "**" + itTitle + "**";
                            String body = it.has("text") ? it.path("text").asText("") : it.path("content").asText("");
                            String value = it.path("value").asText("");
                            String val = value.isEmpty() ? "" : " [" + value + "]";
                            String status = it.path("status").asText("");
                            String st = status.isEmpty() ? "" : " [" + status + "]";
                            String line = (label + (!label.isEmpty() && !body.isEmpty() ? ": " : "") + body + val + st).trim();
                            if (!line.isEmpty()) itemLines.add("- " + line);
                        }
                        if (!itemLines.isEmpty()) textParts.add(String.join("\n", itemLines));
                    }

                    if (("table".equals(type) || "comparison".equals(type))
                        && block.path("columns").isArray() && block.path("rows").isArray() && block.path("rows").size() > 0) {
                        List<String> colLabels = new ArrayList<>();
                        for (JsonNode col : block.path("columns")) {
                            colLabels.add(col.has("label") ? col.path("label").asText("") : col.path("key").asText(""));
                        }
                        textParts.add("| " + String.join(" | ", colLabels) + " |");
                        for (JsonNode row : block.path("rows")) {
                            String criteria = row.path("criteria").asText("");
                            String criteriaPrefix = criteria.isEmpty() ? "" : criteria + ": ";
                            List<String> cellVals = new ArrayList<>();
                            if (row.path("cells").isArray()) {
                                for (JsonNode cell : row.path("cells")) cellVals.add(cell.path("value").asText(""));
                            }
                            String cells = String.join(" | ", cellVals);
                            if (!criteriaPrefix.isEmpty() || !cells.isEmpty()) {
                                textParts.add("| " + criteriaPrefix + cells + " |");
                            }
                        }
                    }
                }
            }

            JsonNode interaction = parsed.path("interaction");
            String question = interaction.path("question").asText("");
            if (!question.trim().isEmpty()) {
                StringBuilder qLine = new StringBuilder("Question asked: \"" + question.trim() + "\"");
                JsonNode options = interaction.path("options");
                if (options.isArray() && options.size() > 0) {
                    List<String> opts = new ArrayList<>();
                    for (JsonNode o : options) {
                        String label = o.has("label") ? o.path("label").asText("") : o.path("value").asText("");
                        if (!label.isEmpty()) opts.add(label);
                    }
                    if (!opts.isEmpty()) qLine.append("\nOptions: ").append(String.join(" | ", opts));
                }
                textParts.add(qLine.toString());
            }

            String modeStr = firstNonEmpty(parsed.path("current_mode").asText(""),
                parsed.path("state").path("active_response_mode").asText(""), "standard");
            String intentStr = firstNonEmpty(parsed.path("response_intent").asText(""), "GUIDANCE");
            return "[Mode: " + modeStr + " | Intent: " + intentStr + "]\n" + String.join("\n", textParts);
        } catch (Exception e) {
            return trimmed;
        }
    }
}
