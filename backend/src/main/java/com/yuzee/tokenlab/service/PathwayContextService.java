package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Port of miniPathway/pathwayContext.ts's {@code getPathwayContext()} (relevance-ranked, budget-
 * capped text summary of a saved pathway report for injection into ongoing chat context) folded
 * together with the small history.ts lookups ({@code completedPathways()}, {@code
 * selectSavedPathway()}, its {@code pathwayContext()} source-message lookup -- renamed {@link
 * #sourceLabel} here to avoid a name clash with this class's main method -- and {@code
 * pathwayDate()}), since all five operate on the same saved-pathway-run shape (the
 * {@code Map<String,Object>} entries in {@code Conversation.miniPathways}).
 */
@Service
public class PathwayContextService {

    private static final Set<String> ALWAYS_INCLUDE = Set.of("overview", "route-summary", "experience-playbook");
    private static final Set<String> STOP_WORDS = Set.of(
        "i", "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
        "is", "was", "are", "were", "have", "has", "had", "be", "do", "did", "will", "would", "could",
        "should", "may", "might", "can", "that", "this", "which", "who", "what", "where", "when", "how",
        "my", "me", "your", "we", "our", "they", "it", "not", "no", "yes", "if", "so", "as", "from",
        "into", "about", "than", "more", "also", "just", "been", "very", "only", "there", "here",
        "some", "any", "all", "both", "each", "few", "other", "such", "one", "two", "first", "last",
        "after", "before", "then", "now", "up", "out", "he", "she"
    );
    private static final Pattern WORD = Pattern.compile("\\b[a-z]{3,}\\b");
    private static final Pattern SOURCE_TAG_PREFIX = Pattern.compile("^\\[QUESTION_ANSWERS:.*?]\\n");

    private final ObjectMapper mapper = new ObjectMapper();

    // -----------------------------------------------------------------
    // pathwayContext.ts -- getPathwayContext()
    // -----------------------------------------------------------------

    /**
     * Returns a compact, relevance-ranked text summary of a saved pathway for chat-context
     * injection, or {@code null} when the pathway has no usable blocks. Always includes
     * overview/route-summary/experience-playbook; ranks remaining blocks by keyword overlap with
     * {@code followUpQuery}; stops once {@code charBudget} characters are used.
     */
    public String getPathwayContext(Map<String, Object> savedPathway, String followUpQuery, int charBudget) {
        if (savedPathway == null) return null;
        JsonNode run = mapper.valueToTree(savedPathway);
        JsonNode blocks = run.path("response").path("content_blocks");
        if (!blocks.isArray() || blocks.isEmpty()) return null;

        Set<String> queryKeywords = keywords(followUpQuery);
        List<Map.Entry<JsonNode, Double>> scored = new ArrayList<>();
        for (JsonNode block : blocks) {
            if ("heading".equals(block.path("type").asText(""))) continue;
            String id = block.path("id").asText("");
            double s = ALWAYS_INCLUDE.contains(id) ? Double.POSITIVE_INFINITY : score(block, queryKeywords);
            scored.add(Map.entry(block, s));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<String> parts = new ArrayList<>();
        int chars = 0;
        for (Map.Entry<JsonNode, Double> entry : scored) {
            JsonNode block = entry.getKey();
            double s = entry.getValue();
            String id = block.path("id").asText("");
            // Drop zero-relevance non-essential blocks once we've used 60% of budget.
            if (s == 0 && !ALWAYS_INCLUDE.contains(id) && chars > charBudget * 0.6) continue;
            String compact = compact(block);
            if (compact.trim().isEmpty()) continue;
            if (chars > 0 && chars + compact.length() > charBudget) break;
            parts.add(compact);
            chars += compact.length() + 2;
        }
        if (parts.isEmpty()) return null;
        return "ACTIVE PATHWAY PLAN (generated earlier in this conversation; use as primary context "
            + "when answering follow-up questions about routes, timelines or next steps):\n" + String.join("\n\n", parts);
    }

    private Set<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String w = matcher.group();
            if (!STOP_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    private String blockText(JsonNode b) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, b.path("title").asText(""));
        addIfPresent(parts, b.path("text").asText(""));
        for (JsonNode item : b.path("items")) {
            addIfPresent(parts, item.path("title").asText(""));
            addIfPresent(parts, item.path("text").asText(""));
        }
        for (JsonNode row : b.path("rows")) {
            for (JsonNode cell : row.path("cells")) {
                addIfPresent(parts, cell.path("value").asText(""));
            }
        }
        return String.join(" ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isEmpty()) parts.add(value);
    }

    private double score(JsonNode block, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) return 0;
        Set<String> blockKeywords = keywords(blockText(block));
        int hits = 0;
        for (String w : queryKeywords) if (blockKeywords.contains(w)) hits++;
        return hits;
    }

    private String compact(JsonNode b) {
        String label = !b.path("title").asText("").isEmpty() ? b.path("title").asText() : b.path("id").asText("");
        String type = b.path("type").asText("");
        List<String> lines = new ArrayList<>();
        switch (type) {
            case "text":
            case "callout": {
                lines.add("[" + label + "]");
                String text = b.path("text").asText("");
                if (!text.isEmpty()) lines.add(truncate(text, 700));
                break;
            }
            case "table":
            case "comparison": {
                lines.add("[" + label + "]");
                Map<String, String> colLabel = new LinkedHashMap<>();
                for (JsonNode col : b.path("columns")) colLabel.put(col.path("key").asText(""), col.path("label").asText(""));
                for (JsonNode row : b.path("rows")) {
                    List<String> cells = new ArrayList<>();
                    for (JsonNode cell : row.path("cells")) {
                        String key = cell.path("key").asText("");
                        String colName = colLabel.getOrDefault(key, key);
                        cells.add(colName + ": " + truncate(cell.path("value").asText(""), 150));
                    }
                    lines.add("• " + String.join(" | ", cells));
                }
                break;
            }
            case "steps":
            case "list": {
                lines.add("[" + label + "]");
                int i = 1;
                for (JsonNode item : b.path("items")) {
                    String itemText = item.path("text").asText("");
                    String desc = itemText.isEmpty() ? "" : ": " + truncate(itemText, 180);
                    lines.add(i + ". " + item.path("title").asText("") + desc);
                    i++;
                }
                break;
            }
            case "heading":
                return "## " + label;
            default: {
                lines.add("[" + label + "]");
                String text = b.path("text").asText("");
                if (!text.isEmpty()) lines.add(truncate(text, 300));
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // -----------------------------------------------------------------
    // history.ts -- completedPathways() / selectSavedPathway() / pathwayContext() / pathwayDate()
    // -----------------------------------------------------------------

    /** Port of history.ts's {@code completedPathways()}: this conversation's complete, unique-by-id runs, oldest first. */
    public List<Map<String, Object>> completedPathways(List<Map<String, Object>> runs, String conversationId) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> run : runs == null ? List.<Map<String, Object>>of() : runs) {
            if (conversationId.equals(run.get("conversationId")) && "complete".equals(run.get("status")) && run.get("response") != null) {
                unique.put(String.valueOf(run.get("id")), run);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("createdAt")))
            .thenComparing(r -> String.valueOf(r.get("id"))));
        return out;
    }

    /** Port of history.ts's {@code selectSavedPathway()}: the preferred run by id, else the most recent. */
    public Map<String, Object> selectSavedPathway(List<Map<String, Object>> runs, String preferredId) {
        if (preferredId != null) {
            for (Map<String, Object> run : runs) {
                if (preferredId.equals(run.get("id"))) return run;
            }
        }
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    /**
     * Port of history.ts's {@code pathwayContext()} (renamed to avoid clashing with {@link
     * #getPathwayContext}): a short label for a saved run -- the user message that originated it,
     * truncated -- for display in a history picker.
     */
    public String sourceLabel(Map<String, Object> run, List<ChatMessage> messages) {
        Object sourceMessageId = run.get("sourceMessageId");
        int index = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId().equals(sourceMessageId)) {
                index = i;
                break;
            }
        }
        if (index < 0) return "Saved from an earlier answer";
        ChatMessage found = null;
        for (int i = index - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                found = messages.get(i);
                break;
            }
        }
        String raw = found == null ? "" : String.valueOf(found.getContent());
        String text = SOURCE_TAG_PREFIX.matcher(raw).replaceFirst("").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "Saved from an earlier answer";
        return text.length() > 110 ? text.substring(0, 107) + "…" : text;
    }

    /** Port of history.ts's {@code pathwayDate()}. */
    public String pathwayDate(String createdAt) {
        try {
            Instant instant = Instant.parse(createdAt);
            DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.forLanguageTag("en-AU")).withZone(ZoneId.systemDefault());
            return formatter.format(instant);
        } catch (Exception e) {
            return "Date unavailable";
        }
    }
}
