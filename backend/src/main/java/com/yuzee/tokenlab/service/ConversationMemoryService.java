package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.CompactionMetrics;
import com.yuzee.tokenlab.model.Conversation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of TokenBudgetMemoryManager.ts.
 * Enforces token-based context budgeting with whole-turn atomic eviction, and groups a
 * conversation's flat message list into atomic user/assistant turns before applying one of
 * three retention strategies (see {@link MemoryStrategy}).
 */
@Service
public class ConversationMemoryService {

    /** recentTurnsToKeep in the old app's TokenBudgetMemoryManager.assembleMemory default param. */
    private static final int DEFAULT_MAX_TURNS = 100;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from",
        "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "may", "might", "shall", "can", "not", "no", "so", "if",
        "as", "up", "it", "its", "i", "you", "we", "they", "he", "she", "that", "this", "these", "those",
        "my", "your", "our", "their", "me", "him", "her", "us", "them", "what", "how", "when", "where",
        "which", "who", "about", "into", "than", "then", "there", "here", "just", "also", "more",
        "some", "any", "all", "most", "other", "such", "only", "own", "same", "few", "both", "very"
    );

    /** A paired user/assistant dialogue turn, ported from TS's DialogueTurn. */
    private static final class Turn {
        final ChatMessage user;
        final ChatMessage assistant; // null when rejected/pending or a trailing incomplete turn
        final long createdAtMillis;
        final int estimatedTokens;

        Turn(ChatMessage user, ChatMessage assistant, int estimatedTokens) {
            this.user = user;
            this.assistant = assistant;
            this.createdAtMillis = user.getTimestamp() != null ? user.getTimestamp().toEpochMilli() : 0L;
            this.estimatedTokens = estimatedTokens;
        }
    }

    /** ~4 chars/token heuristic, matching TokenBudgetMemoryManager.estimateTokens exactly. */
    public int estimateTokens(String text) {
        if (text == null) return 0;
        String clean = text.trim();
        if (clean.isEmpty()) return 0;
        int words = clean.split("\\s+").length;
        return Math.max(1, (int) Math.ceil(clean.length() * 0.26 + words * 0.15));
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

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    /**
     * Compacts stored protocol JSON (schema v1.3 content_blocks) into plain text for history/token
     * accounting. Ported from TokenBudgetMemoryManager.formatAssistantMessageForContext.
     */
    public String formatAssistantMessageForContext(Object rawContent) {
        String raw = contentToString(rawContent);
        if (raw.isEmpty()) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode parsed = mapper.readTree(trimmed);
                String schemaVersion = parsed.path("schema_version").asText("");
                JsonNode blocks = parsed.has("content_blocks") ? parsed.path("content_blocks") : parsed.path("blocks");
                if ("1.3".equals(schemaVersion) && blocks.isArray()) {
                    List<String> textParts = new ArrayList<>();
                    for (JsonNode block : blocks) {
                        String type = block.path("type").asText("");
                        String title = block.path("title").asText("");
                        if ("heading".equals(type) && !title.trim().isEmpty()) {
                            String hLevel = "h3".equals(block.path("level").asText("")) ? "###" : "##";
                            textParts.add(hLevel + " " + title.trim());
                        } else {
                            String text = block.path("text").asText("");
                            if (!text.trim().isEmpty()) textParts.add(text.trim());
                        }

                        JsonNode items = block.path("items");
                        if (items.isArray() && items.size() > 0) {
                            List<String> itemTexts = new ArrayList<>();
                            for (JsonNode it : items) {
                                String itTitle = it.path("title").asText("");
                                String label = itTitle.isEmpty() ? "" : "**" + itTitle + "**";
                                String body = it.path("text").asText("");
                                String value = it.path("value").asText("");
                                String val = value.isEmpty() ? "" : " [value: " + value + "]";
                                String status = it.path("status").asText("");
                                String st = status.isEmpty() ? "" : " [" + status + "]";
                                String line = (label + (!label.isEmpty() && !body.isEmpty() ? ": " : "") + body + val + st).trim();
                                if (!line.isEmpty()) itemTexts.add("- " + line);
                            }
                            if (!itemTexts.isEmpty()) textParts.add(String.join("\n", itemTexts));
                        }
                    }

                    String question = parsed.path("interaction").path("question").asText("");
                    if (!question.trim().isEmpty()) {
                        textParts.add("Question asked: \"" + question.trim() + "\"");
                    }

                    String modeStr = firstNonEmpty(parsed.path("current_mode").asText(""),
                        parsed.path("state").path("active_response_mode").asText(""), "standard");
                    String intentStr = firstNonEmpty(parsed.path("response_intent").asText(""), "GUIDANCE");
                    return "[Mode: " + modeStr + " | Intent: " + intentStr + "]\n" + String.join("\n", textParts);
                }
            } catch (Exception ignored) {
                // Fall back to raw content if JSON parsing fails, matching the TS try/catch.
            }
        }
        return trimmed;
    }

    /**
     * Group a conversation's flat message list into atomic dialogue turns. Rejects
     * unaccepted/failed assistant responses from entering model context (ported from
     * TokenBudgetMemoryManager.groupIntoTurns, adapted to ChatMessage.validationFailed as the
     * single accept/reject signal since this Java model doesn't carry the old app's separate
     * protocolAccepted/schemaValid/telemetry fields).
     */
    private List<Turn> groupIntoTurns(List<ChatMessage> messages) {
        List<Turn> turns = new ArrayList<>();
        ChatMessage pendingUser = null;

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                if (pendingUser != null) {
                    // Unclosed turn (e.g. consecutive user messages).
                    turns.add(new Turn(pendingUser, null, estimateTokens(contentToString(pendingUser.getContent()))));
                }
                pendingUser = msg;
            } else if ("assistant".equals(msg.getRole()) && pendingUser != null) {
                boolean accepted = !Boolean.TRUE.equals(msg.getValidationFailed());
                if (accepted) {
                    String compact = formatAssistantMessageForContext(msg.getContent());
                    int tokens = estimateTokens(contentToString(pendingUser.getContent())) + estimateTokens(compact);
                    turns.add(new Turn(pendingUser, msg, tokens));
                } else {
                    // Rejected response: do not let the assistant message enter model history.
                    turns.add(new Turn(pendingUser, null, estimateTokens(contentToString(pendingUser.getContent()))));
                }
                pendingUser = null;
            }
        }

        if (pendingUser != null) {
            turns.add(new Turn(pendingUser, null, estimateTokens(contentToString(pendingUser.getContent()))));
        }
        return turns;
    }

    private List<String> extractKeywords(String text, int maxKeywords) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        String cleaned = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        for (String w : cleaned.split("\\s+")) {
            if (w.length() < 3 || STOP_WORDS.contains(w)) continue;
            freq.merge(w, 1, Integer::sum);
        }
        return freq.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(maxKeywords)
            .map(Map.Entry::getKey)
            .toList();
    }

    private double scoreTurnRelevance(Turn turn, List<String> queryKeywords) {
        if (queryKeywords.isEmpty()) return 0;
        String turnText = (contentToString(turn.user.getContent()) + " "
            + (turn.assistant != null ? formatAssistantMessageForContext(turn.assistant.getContent()) : "")).toLowerCase();
        int hits = 0;
        for (String kw : queryKeywords) {
            if (turnText.contains(kw)) hits++;
        }
        // sqrt dampening: penalise turns that only match one or two keywords weakly.
        return Math.sqrt(hits) / Math.sqrt(queryKeywords.size());
    }

    private List<ChatMessage> flatten(List<Turn> turns) {
        List<ChatMessage> out = new ArrayList<>();
        for (Turn t : turns) {
            out.add(t.user);
            if (t.assistant != null) out.add(t.assistant);
        }
        return out;
    }

    /**
     * Assembles retained conversation turns adhering to the given token budget and strategy.
     * No fake synthetic summary prose is ever injected (compactedSummary stays null — this port
     * doesn't run a real summarization step, matching the old app's "no fake summary" comment).
     */
    public MemoryResult assembleMemory(Conversation conversation, int tokenBudget, MemoryStrategy strategy, String currentUserQuery) {
        List<ChatMessage> messages = conversation != null && conversation.getMessages() != null
            ? conversation.getMessages() : List.of();
        List<Turn> turns = groupIntoTurns(messages);

        if (turns.isEmpty()) {
            return new MemoryResult(List.of(), null, new CompactionMetrics(0, 0, 0, tokenBudget, strategy.name()));
        }

        if (strategy == MemoryStrategy.BASELINE) {
            int tokensUsed = turns.stream().mapToInt(t -> t.estimatedTokens).sum();
            return new MemoryResult(flatten(turns), null,
                new CompactionMetrics(turns.size(), 0, tokensUsed, tokenBudget, strategy.name()));
        }

        if (strategy == MemoryStrategy.SEMANTIC_EVIDENCE) {
            List<String> queryKeywords = extractKeywords(currentUserQuery == null ? "" : currentUserQuery, 15);
            int recencyAnchor = Math.min(3, turns.size());
            List<Turn> recentAnchor = turns.subList(turns.size() - recencyAnchor, turns.size());
            List<Turn> historyPool = turns.subList(0, turns.size() - recencyAnchor);

            Map<Turn, Double> scores = new LinkedHashMap<>();
            for (Turn t : historyPool) scores.put(t, scoreTurnRelevance(t, queryKeywords));
            List<Turn> scored = historyPool.stream()
                .filter(t -> scores.get(t) > 0)
                .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                .toList();

            int accTokens = 0;
            List<Turn> keptRecent = new ArrayList<>();
            for (int i = 0; i < recentAnchor.size(); i++) {
                Turn t = recentAnchor.get(i);
                if (accTokens + t.estimatedTokens <= tokenBudget) {
                    keptRecent.add(t);
                    accTokens += t.estimatedTokens;
                } else if (keptRecent.isEmpty() && i == recentAnchor.size() - 1) {
                    // Always guarantee the most recent turn even if it individually exceeds budget.
                    keptRecent.add(t);
                    accTokens += t.estimatedTokens;
                }
            }

            List<Turn> keptEvidence = new ArrayList<>();
            for (Turn t : scored) {
                if (keptEvidence.size() + keptRecent.size() >= Math.max(1, DEFAULT_MAX_TURNS)) break;
                if (accTokens + t.estimatedTokens > tokenBudget) continue;
                keptEvidence.add(t);
                accTokens += t.estimatedTokens;
            }

            List<Turn> merged = new ArrayList<>(keptEvidence);
            merged.addAll(keptRecent);
            merged.sort(Comparator.comparingLong(t -> t.createdAtMillis));
            LinkedHashMap<String, Turn> dedup = new LinkedHashMap<>();
            for (Turn t : merged) dedup.putIfAbsent(t.user.getId(), t);
            List<Turn> keptTurns = new ArrayList<>(dedup.values());

            int turnsDropped = turns.size() - keptTurns.size();
            int tokensUsed = keptTurns.stream().mapToInt(t -> t.estimatedTokens).sum();
            return new MemoryResult(flatten(keptTurns), null,
                new CompactionMetrics(keptTurns.size(), turnsDropped, tokensUsed, tokenBudget, strategy.name()));
        }

        // BUDGET_EVICTION (default): newest-first greedy whole-turn pack until budget exhausted.
        List<Turn> keptTurns = new ArrayList<>();
        int accumulated = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            if (keptTurns.size() < DEFAULT_MAX_TURNS && (accumulated + t.estimatedTokens) <= tokenBudget) {
                keptTurns.add(0, t);
                accumulated += t.estimatedTokens;
            }
        }
        int turnsDropped = turns.size() - keptTurns.size();
        return new MemoryResult(flatten(keptTurns), null,
            new CompactionMetrics(keptTurns.size(), turnsDropped, accumulated, tokenBudget, strategy.name()));
    }
}
