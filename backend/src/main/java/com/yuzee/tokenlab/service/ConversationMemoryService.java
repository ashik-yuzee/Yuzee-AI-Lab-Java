package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.CompactionMetrics;
import com.yuzee.tokenlab.model.DialogueTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Exact Java port of TokenBudgetMemoryManager.ts (estimateTokens, formatAssistantMessageForContext,
 * groupIntoTurns, TokenBudgetMemoryManager.assembleMemory) plus server.ts summarizeEvictedTurns.
 * JS semantics (truthiness, String.trim, /\s+/, template-literal stringification) are reproduced by
 * the js* helpers below, which MultiTurnRequestBuilder reuses.
 */
@Service
public class ConversationMemoryService {

    static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from",
        "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "may", "might", "shall", "can", "not", "no", "so", "if",
        "as", "up", "it", "its", "i", "you", "we", "they", "he", "she", "that", "this", "these", "those",
        "my", "your", "our", "their", "me", "him", "her", "us", "them", "what", "how", "when", "where",
        "which", "who", "about", "into", "than", "then", "there", "here", "just", "also", "more",
        "some", "any", "all", "most", "other", "such", "only", "own", "same", "few", "both", "very"
    );

    // ---------------------------------------------------------------- JS semantics helpers

    /** Characters matched by JS \s and stripped by String.prototype.trim. */
    static boolean isJsWs(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0x00A0
            || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
            || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    static String jsTrim(String s) {
        int start = 0, end = s.length();
        while (start < end && isJsWs(s.charAt(start))) start++;
        while (end > start && isJsWs(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** JS truthiness of a parsed JSON value (missing = undefined). */
    static boolean truthy(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return false;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) { double d = n.doubleValue(); return d != 0 && !Double.isNaN(d); }
        if (n.isTextual()) return !n.textValue().isEmpty();
        return true;
    }

    /** JS `a || b`. */
    static JsonNode or(JsonNode a, JsonNode b) {
        return truthy(a) ? a : b;
    }

    /** JS String(x) / template-literal interpolation of a parsed JSON value. */
    static String jsToString(JsonNode n) {
        if (n == null || n.isMissingNode()) return "undefined";
        if (n.isNull()) return "null";
        if (n.isTextual()) return n.textValue();
        if (n.isBoolean()) return String.valueOf(n.booleanValue());
        if (n.isNumber()) {
            if (n.isIntegralNumber() && n.canConvertToLong()) return String.valueOf(n.longValue());
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e21) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode el : n) parts.add(jsJoinElement(el));
            return String.join(",", parts);
        }
        return "[object Object]";
    }

    /** How Array.prototype.join renders one element (null/undefined become ""). */
    static String jsJoinElement(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "" : jsToString(n);
    }

    /** JS `x.trim()` on a value that must be a string (otherwise TypeError, which the callers' try/catch absorbs). */
    static String jsStringOrThrow(JsonNode n) {
        if (n != null && n.isTextual()) return n.textValue();
        throw new IllegalStateException("TypeError: trim is not a function");
    }

    /** JS property access on a value: TypeError on null/undefined, undefined for primitives. */
    static JsonNode jsGet(JsonNode obj, String key) {
        if (obj == null || obj.isMissingNode() || obj.isNull()) throw new IllegalStateException("TypeError: cannot read " + key);
        return obj.path(key);
    }

    /** JS `for (const x of v)`: arrays iterate, strings iterate chars (no usable fields), others throw. */
    static List<JsonNode> jsIterate(JsonNode v) {
        List<JsonNode> out = new ArrayList<>();
        if (v.isArray()) { v.forEach(out::add); return out; }
        if (v.isTextual()) {
            for (int i = 0; i < v.textValue().length(); i++) out.add(MAPPER.getNodeFactory().textNode(String.valueOf(v.textValue().charAt(i))));
            return out;
        }
        throw new IllegalStateException("TypeError: not iterable");
    }

    static boolean jsStrictEq(JsonNode n, String s) {
        return n != null && n.isTextual() && n.textValue().equals(s);
    }

    /** Math.round(x * 10) / 10 as a JS number (whole values serialise without ".0"). */
    static Number jsRound1(double x) {
        double r = Math.round(x * 10) / 10.0;
        return r == Math.rint(r) ? (Number) (long) r : (Number) r;
    }

    static String contentToString(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    // ---------------------------------------------------------------- ported functions

    /** Exact port of estimateTokens(). */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        String clean = jsTrim(text);
        if (clean.isEmpty()) return 0;
        int words = 1; // clean.split(/\s+/).length on a trimmed string = whitespace runs + 1
        for (int i = 1; i < clean.length(); i++) {
            if (isJsWs(clean.charAt(i)) && !isJsWs(clean.charAt(i - 1))) words++;
        }
        return Math.max(1, (int) Math.ceil(clean.length() * 0.26 + words * 0.15));
    }

    /** Exact port of formatAssistantMessageForContext(); accepts ChatMessage content (non-strings are JSON-stringified). */
    public static String formatAssistantMessageForContext(Object rawContentObj) {
        String rawContent = contentToString(rawContentObj);
        if (rawContent.isEmpty()) return "";
        String trimmed = jsTrim(rawContent);
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode parsed = MAPPER.readTree(trimmed);
                if (jsStrictEq(parsed.path("schema_version"), "1.3")
                    && (truthy(parsed.path("content_blocks")) || truthy(parsed.path("blocks")))) {
                    JsonNode blocks = or(parsed.path("content_blocks"), parsed.path("blocks"));
                    List<String> textParts = new ArrayList<>();

                    for (JsonNode block : jsIterate(blocks)) {
                        JsonNode title = jsGet(block, "title");
                        JsonNode text = block.path("text");
                        if (jsStrictEq(block.path("type"), "heading") && truthy(title) && !jsTrim(jsStringOrThrow(title)).isEmpty()) {
                            String hLevel = jsStrictEq(block.path("level"), "h3") ? "###" : "##";
                            textParts.add(hLevel + " " + jsTrim(title.textValue()));
                        } else if (truthy(text) && !jsTrim(jsStringOrThrow(text)).isEmpty()) {
                            textParts.add(jsTrim(text.textValue()));
                        }
                        JsonNode items = block.path("items");
                        if (truthy(items) && items.isArray() && items.size() > 0) {
                            List<String> itemTexts = new ArrayList<>();
                            for (JsonNode it : items) {
                                JsonNode itTitle = jsGet(it, "title");
                                String label = truthy(itTitle) ? "**" + jsToString(itTitle) + "**" : "";
                                String body = truthy(it.path("text")) ? jsToString(it.path("text")) : "";
                                String val = truthy(it.path("value")) ? " [value: " + jsToString(it.path("value")) + "]" : "";
                                String status = truthy(it.path("status")) ? " [" + jsToString(it.path("status")) + "]" : "";
                                // "- " is inside the trimmed template, so an empty item still yields "-" (filter(Boolean) keeps it).
                                itemTexts.add(jsTrim("- " + label + (!label.isEmpty() && !body.isEmpty() ? ": " : "") + body + val + status));
                            }
                            if (!itemTexts.isEmpty()) textParts.add(String.join("\n", itemTexts));
                        }
                    }

                    JsonNode question = parsed.path("interaction").path("question");
                    if (truthy(question) && question.isTextual() && !jsTrim(question.textValue()).isEmpty()) {
                        textParts.add("Question asked: \"" + jsTrim(question.textValue()) + "\"");
                    }

                    String modeStr = jsToString(or(or(parsed.path("current_mode"), parsed.path("state").path("active_response_mode")),
                        MAPPER.getNodeFactory().textNode("standard")));
                    String intentStr = jsToString(or(parsed.path("response_intent"), MAPPER.getNodeFactory().textNode("GUIDANCE")));
                    String summary = String.join("\n", textParts);
                    return "[Mode: " + modeStr + " | Intent: " + intentStr + "]\n" + summary;
                }
            } catch (Exception ignored) {
                // Fallback to raw content if JSON parsing fails
            }
        }
        return trimmed;
    }

    private static DialogueTurn.Message toTurnMessage(ChatMessage m, String content) {
        long createdAt = m.getTimestamp() != null ? m.getTimestamp().toEpochMilli() : 0L;
        return new DialogueTurn.Message(m.getId(), content, createdAt != 0 ? createdAt : System.currentTimeMillis());
    }

    /** Original: msg.protocolAccepted !== false && msg.schemaValid !== false && msg.telemetry?.validation?.protocolAccepted !== false. */
    private static boolean isAccepted(ChatMessage msg) {
        Map<String, Object> telemetry = msg.getTelemetry();
        return !(telemetry != null && telemetry.get("validation") instanceof Map<?, ?> v && Boolean.FALSE.equals(v.get("protocolAccepted")));
    }

    /** Exact port of groupIntoTurns(). */
    public static List<DialogueTurn> groupIntoTurns(List<ChatMessage> messages) {
        List<DialogueTurn> turns = new ArrayList<>();
        if (messages == null) return turns;
        String currentId = null;
        DialogueTurn.Message currentUser = null;

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                if (currentUser != null) {
                    // Unclosed turn (e.g. consecutive user messages)
                    turns.add(new DialogueTurn(currentId, currentUser, null, estimateTokens(currentUser.content) + estimateTokens("")));
                }
                currentId = "turn-" + (turns.size() + 1);
                currentUser = toTurnMessage(msg, contentToString(msg.getContent()));
            } else if ("assistant".equals(msg.getRole()) && currentUser != null) {
                if (isAccepted(msg)) {
                    String compactContent = formatAssistantMessageForContext(contentToString(msg.getContent()));
                    turns.add(new DialogueTurn(currentId, currentUser, toTurnMessage(msg, compactContent),
                        estimateTokens(currentUser.content) + estimateTokens(compactContent)));
                } else {
                    // Rejected response: do not add assistant message to model history turn
                    turns.add(new DialogueTurn(currentId, currentUser, null, estimateTokens(currentUser.content)));
                }
                currentUser = null;
            }
        }

        // Trailing incomplete turn
        if (currentUser != null) {
            turns.add(new DialogueTurn(currentId, currentUser, null, estimateTokens(currentUser.content) + estimateTokens("")));
        }
        return turns;
    }

    private static List<String> extractKeywords(String text, int maxKeywords) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        StringBuilder cleaned = new StringBuilder(text.toLowerCase(Locale.ROOT));
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || isJsWs(c))) cleaned.setCharAt(i, ' ');
        }
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= cleaned.length(); i++) {
            if (i == cleaned.length() || isJsWs(cleaned.charAt(i))) {
                String word = w.toString();
                w.setLength(0);
                if (word.length() < 3 || STOP_WORDS.contains(word)) continue;
                freq.merge(word, 1, Integer::sum);
            } else {
                w.append(cleaned.charAt(i));
            }
        }
        return freq.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(maxKeywords)
            .map(Map.Entry::getKey)
            .toList();
    }

    private static double scoreTurnRelevance(DialogueTurn turn, List<String> queryKeywords) {
        if (queryKeywords.isEmpty()) return 0;
        String asst = turn.assistantMessage != null && turn.assistantMessage.content != null ? turn.assistantMessage.content : "";
        String turnText = (turn.userMessage.content + " " + asst).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String kw : queryKeywords) {
            if (turnText.contains(kw)) hits++;
        }
        // sqrt dampening: penalise turns that only match one or two keywords weakly
        return Math.sqrt(hits) / Math.sqrt(queryKeywords.size());
    }

    private static String historyText(List<DialogueTurn> kept) {
        List<String> parts = new ArrayList<>();
        for (DialogueTurn t : kept) {
            String userPart = "USER: " + t.userMessage.content;
            String asstPart = t.assistantMessage != null ? "\nASSISTANT: " + t.assistantMessage.content : "";
            parts.add(userPart + asstPart);
        }
        return String.join("\n\n", parts);
    }

    private static Map<String, Object> excludedItem(DialogueTurn evicted, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Evicted Turn (" + evicted.id + ")");
        item.put("reason", reason);
        item.put("tokens", evicted.estimatedTokens);
        String c = evicted.userMessage.content;
        item.put("preview", c.substring(0, Math.min(65, c.length())));
        return item;
    }

    private static CompactionMetrics simulatedMetrics(List<DialogueTurn> evictedTurns, String eventId, String range) {
        List<String> lines = new ArrayList<>();
        for (DialogueTurn t : evictedTurns) {
            String asst = t.assistantMessage != null && t.assistantMessage.content != null ? t.assistantMessage.content : "";
            lines.add("User: " + t.userMessage.content + "\nAsst: " + asst);
        }
        int sourceTokens = estimateTokens(String.join("\n", lines));
        int simulatedSummaryTokens = Math.max(20, (int) Math.round(sourceTokens * 0.2));
        int tokensRemoved = Math.max(0, sourceTokens - simulatedSummaryTokens);
        int compCost = sourceTokens + simulatedSummaryTokens + 20;
        int netSavingsPerTurn = Math.max(1, tokensRemoved);

        CompactionMetrics m = new CompactionMetrics();
        m.compactionEventId = eventId;
        m.sourceTurnsRange = range;
        m.sourceTokens = sourceTokens;
        m.summaryTokens = simulatedSummaryTokens;
        m.tokensRemoved = tokensRemoved;
        m.compactionInputTokens = sourceTokens + 20;
        m.compactionOutputTokens = simulatedSummaryTokens;
        m.compactionTotalCost = compCost;
        m.estimatedNetSavingsPerTurn = netSavingsPerTurn;
        m.estimatedBreakEvenTurns = jsRound1((double) compCost / netSavingsPerTurn);
        m.timestamp = System.currentTimeMillis();
        m.isSimulated = true;
        return m;
    }

    /**
     * Exact port of TokenBudgetMemoryManager.assembleMemory(). BASELINE keeps everything,
     * SEMANTIC_EVIDENCE is relevance + 3-turn recency anchor, and every other strategy
     * (ADAPTIVE_HYBRID, SUMMARY_RECENT, ...) is newest-first whole-turn budget eviction.
     * Original defaults: budget 2000, recentTurnsToKeep 100, strategy 'ADAPTIVE_HYBRID', summary '', query ''.
     */
    public MemoryResult assembleMemory(List<ChatMessage> historicalMessages, int dynamicBudgetTokens, int recentTurnsToKeep,
                                       String strategy, String existingSummary, String currentMessage) {
        if (strategy == null) strategy = "ADAPTIVE_HYBRID";
        List<Map<String, Object>> excludedItems = new ArrayList<>();
        String summaryText = existingSummary != null ? existingSummary : "";
        int removedTokens = 0;
        CompactionMetrics compactionMetrics = null;

        List<DialogueTurn> turns = groupIntoTurns(historicalMessages);

        if (turns.isEmpty()) {
            return new MemoryResult(summaryText, new ArrayList<>(), "", 0, 0, null, new ArrayList<>());
        }

        if ("BASELINE".equals(strategy)) {
            // Baseline: Keep all historical turns without eviction
            return new MemoryResult("", turns, historyText(turns), turns.size(), 0, null, new ArrayList<>());
        }

        if ("SEMANTIC_EVIDENCE".equals(strategy)) {
            String queryText = currentMessage != null ? currentMessage : "";
            List<String> queryKeywords = extractKeywords(queryText, 15);
            int recencyAnchor = Math.min(3, turns.size());
            List<DialogueTurn> recentAnchorTurns = turns.subList(turns.size() - recencyAnchor, turns.size());
            List<DialogueTurn> historyPool = turns.subList(0, turns.size() - recencyAnchor);

            Map<DialogueTurn, Double> scores = new HashMap<>();
            for (DialogueTurn t : historyPool) scores.put(t, scoreTurnRelevance(t, queryKeywords));
            List<DialogueTurn> scored = new ArrayList<>();
            for (DialogueTurn t : historyPool) if (scores.get(t) > 0) scored.add(t);
            scored.sort((a, b) -> Double.compare(scores.get(b), scores.get(a))); // stable, like V8

            int accTokens = estimateTokens(summaryText);
            List<DialogueTurn> keptEvidence = new ArrayList<>();
            List<DialogueTurn> keptRecent = new ArrayList<>();

            for (int i = 0; i < recentAnchorTurns.size(); i++) {
                DialogueTurn t = recentAnchorTurns.get(i);
                if (accTokens + t.estimatedTokens <= dynamicBudgetTokens) {
                    keptRecent.add(t);
                    accTokens += t.estimatedTokens;
                } else if (keptRecent.isEmpty() && i == recentAnchorTurns.size() - 1) {
                    // Always guarantee the most recent turn even if it individually exceeds budget.
                    keptRecent.add(t);
                    accTokens += t.estimatedTokens;
                }
            }
            for (DialogueTurn turn : scored) {
                if (keptEvidence.size() + keptRecent.size() >= Math.max(1, recentTurnsToKeep)) break;
                if (accTokens + turn.estimatedTokens > dynamicBudgetTokens) continue;
                keptEvidence.add(turn);
                accTokens += turn.estimatedTokens;
            }

            // Merge and sort chronologically, deduplicate
            List<DialogueTurn> merged = new ArrayList<>(keptEvidence);
            merged.addAll(keptRecent);
            merged.sort(Comparator.comparingLong(t -> t.userMessage.createdAt));
            Set<String> seen = new HashSet<>();
            List<DialogueTurn> keptTurns = new ArrayList<>();
            for (DialogueTurn t : merged) if (seen.add(t.id)) keptTurns.add(t);

            Set<String> keptIds = new HashSet<>();
            for (DialogueTurn t : keptTurns) keptIds.add(t.id);
            List<DialogueTurn> evictedTurns = new ArrayList<>();
            for (DialogueTurn t : turns) if (!keptIds.contains(t.id)) evictedTurns.add(t);

            int removedTokensSE = 0;
            List<Map<String, Object>> excludedItemsSE = new ArrayList<>();
            for (DialogueTurn evicted : evictedTurns) {
                removedTokensSE += evicted.estimatedTokens;
                excludedItemsSE.add(excludedItem(evicted, "Below relevance threshold or budget exceeded (semantic evidence strategy)"));
            }

            CompactionMetrics compMetricsSE = evictedTurns.isEmpty() ? null
                : simulatedMetrics(evictedTurns, "cmp-se-" + System.currentTimeMillis(), evictedTurns.size() + " evicted by semantic relevance");

            return new MemoryResult(summaryText, keptTurns, historyText(keptTurns), keptTurns.size(), removedTokensSE, compMetricsSE, excludedItemsSE);
        }

        // Determine turns to retain within dynamic budget & recentTurns limit
        int maxTurnsByCount = Math.max(1, recentTurnsToKeep);
        List<DialogueTurn> keptTurns = new ArrayList<>();
        List<DialogueTurn> evictedTurns = new ArrayList<>();

        // Traverse turns from newest to oldest
        int accumulatedTokens = estimateTokens(summaryText);
        for (int i = turns.size() - 1; i >= 0; i--) {
            DialogueTurn turn = turns.get(i);
            int turnTokens = turn.estimatedTokens;
            if (keptTurns.size() < maxTurnsByCount && (accumulatedTokens + turnTokens) <= dynamicBudgetTokens) {
                keptTurns.add(0, turn);
                accumulatedTokens += turnTokens;
            } else {
                evictedTurns.add(0, turn);
            }
        }

        for (DialogueTurn evicted : evictedTurns) {
            removedTokens += evicted.estimatedTokens;
            excludedItems.add(excludedItem(evicted,
                "Exceeded dynamic budget (" + dynamicBudgetTokens + " tokens) or turn limit (" + maxTurnsByCount + " turns)"));
        }

        // Track simulated compaction metrics for diagnostic purposes without injecting fake summary prose
        if (!evictedTurns.isEmpty()) {
            compactionMetrics = simulatedMetrics(evictedTurns, "cmp-" + System.currentTimeMillis(), "Turns 1 to " + evictedTurns.size());
        }

        return new MemoryResult(summaryText, keptTurns, historyText(keptTurns), keptTurns.size(), removedTokens, compactionMetrics, excludedItems);
    }

    /** server.ts summarizeEvictedTurns prompt (contents string sent to the model). */
    static String buildSummaryPrompt(List<DialogueTurn> evictedTurns, String previousSummary) {
        List<String> turnTexts = new ArrayList<>();
        for (DialogueTurn t : evictedTurns) {
            String asst = t.assistantMessage != null && t.assistantMessage.content != null && !t.assistantMessage.content.isEmpty()
                ? t.assistantMessage.content : "";
            turnTexts.add("User: " + t.userMessage.content + "\nAssistant: " + asst);
        }
        String evictedText = String.join("\n---\n", turnTexts);

        List<String> parts = new ArrayList<>();
        if (previousSummary != null && !previousSummary.isEmpty()) parts.add("Existing summary:\n" + previousSummary + "\n");
        parts.add("Compress these career counseling turns into structured bullets (max 500 tokens).\nOnly include facts that are explicitly stated:\n\n"
            + evictedText
            + "\n\n- Goal: [career target/role]\n- Background: [experience, certs, skills]\n- Constraints: [budget, time, location]\n- Decisions: [committed choices, chosen courses/certs]\n- Progress: [completed steps, feedback given]\n- Open: [unanswered questions, hesitations]\nOmit any field with no clear evidence. Plain bullets only.");
        return String.join("\n", parts);
    }

    /**
     * Port of server.ts summarizeEvictedTurns: builds the same prompt and passes it (as the whole
     * `contents`, no system instruction) to {@code generate}; returns the trimmed text, or null on failure.
     * Original call: model gemini-3.5-flash-lite, config {maxOutputTokens: 500}, plain-text response.
     */
    public String summarizeEvictedTurns(List<DialogueTurn> evictedTurns, String previousSummary, Function<String, String> generate) {
        try {
            String text = generate.apply(buildSummaryPrompt(evictedTurns, previousSummary));
            return (text != null ? text : "").trim();
        } catch (Exception e) {
            return null;
        }
    }
}
