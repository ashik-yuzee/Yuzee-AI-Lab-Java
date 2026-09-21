package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File-backed ConversationLogService, used when no DATABASE_URL is configured. Appends one JSON
 * object per line to a local log file (never pruned/rewritten, matching "logs are kept forever"),
 * and folds it in memory on read for the stats endpoints.
 * <p>
 * // ponytail: appends are synchronized (blocking) rather than queued, same tradeoff as
 * // FileConversationRepository — simplest thing that keeps concurrent writers from
 * // interleaving lines. Revisit only under real write concurrency.
 */
public class FileConversationLogService implements ConversationLogService {

    private final Path file;
    private final ObjectMapper mapper;

    public FileConversationLogService(ObjectMapper mapper) {
        this(mapper, Paths.get("data", "conversation_logs.jsonl"));
    }

    FileConversationLogService(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    @Override
    public synchronized void logTurn(String ip, String conversationId, String messageId, String modelId,
                                      Integer promptTokens, Integer uncachedInputTokens, Integer cachedTokens,
                                      Integer outputTokens, Integer thinkingTokens, Double estimatedCostUsd, Integer latencyMs,
                                      String finishReason, boolean isMock, boolean isWhiteboard,
                                      String userInput, String assistantOutput, String errorCode) {
        Map<String, Object> row = new HashMap<>();
        row.put("loggedAt", Instant.now().toString());
        row.put("ip", ip);
        row.put("conversationId", conversationId);
        row.put("messageId", messageId);
        row.put("model", modelId);
        row.put("inputTokens", promptTokens);
        row.put("uncachedInputTokens", uncachedInputTokens);
        row.put("cachedTokens", cachedTokens);
        row.put("outputTokens", outputTokens);
        row.put("thinkingTokens", thinkingTokens);
        row.put("estimatedCostUsd", estimatedCostUsd);
        row.put("latencyMs", latencyMs);
        row.put("finishReason", finishReason);
        row.put("isMock", isMock);
        row.put("isWhiteboard", isWhiteboard);
        row.put("userInput", truncate(userInput, 2000));
        row.put("assistantOutput", truncate(assistantOutput, 4000));
        row.put("errorCode", errorCode);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            String line = mapper.writeValueAsString(row) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed appending to conversation log: " + file, e);
        }
    }

    @Override
    public synchronized Map<String, Object> loadLifetimeStats() {
        long calls = 0, inputTokens = 0, outputTokens = 0, cachedTokens = 0, thinkingTokens = 0;
        double costUsd = 0;
        long wbCalls = 0, wbInput = 0, wbOutput = 0;
        double wbCost = 0;
        for (Map<String, Object> row : readAll()) {
            boolean isMock = bool(row.get("isMock"));
            boolean isWhiteboard = bool(row.get("isWhiteboard"));
            if (isMock) continue;
            if (isWhiteboard) {
                wbCalls++;
                wbInput += num(row.get("inputTokens"));
                wbOutput += num(row.get("outputTokens"));
                wbCost += dbl(row.get("estimatedCostUsd"));
            } else {
                calls++;
                inputTokens += num(row.get("inputTokens"));
                outputTokens += num(row.get("outputTokens"));
                cachedTokens += num(row.get("cachedTokens"));
                thinkingTokens += num(row.get("thinkingTokens"));
                costUsd += dbl(row.get("estimatedCostUsd"));
            }
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("calls", calls);
        stats.put("inputTokens", inputTokens);
        stats.put("outputTokens", outputTokens);
        stats.put("cachedTokens", cachedTokens);
        stats.put("thinkingTokens", thinkingTokens);
        stats.put("costUsd", costUsd);
        stats.put("whiteboard", Map.of(
            "calls", wbCalls, "inputTokens", wbInput, "outputTokens", wbOutput, "costUsd", wbCost));
        return stats;
    }

    @Override
    public synchronized double loadDailyCost() {
        LocalDate today = LocalDate.now();
        double total = 0;
        for (Map<String, Object> row : readAll()) {
            if (bool(row.get("isMock"))) continue;
            Object loggedAt = row.get("loggedAt");
            if (loggedAt == null) continue;
            LocalDate day = Instant.parse(loggedAt.toString()).atZone(ZoneId.systemDefault()).toLocalDate();
            if (day.equals(today)) total += dbl(row.get("estimatedCostUsd"));
        }
        return total;
    }

    @Override
    public synchronized Map<String, Object> loadSessionStats() {
        long calls = 0, modelInput = 0, uncachedInput = 0, modelOutput = 0, thinking = 0, cached = 0;
        for (Map<String, Object> row : readAll()) {
            if (bool(row.get("isMock"))) continue;
            calls++;
            modelInput += num(row.get("inputTokens"));
            uncachedInput += num(row.get("uncachedInputTokens"));
            modelOutput += num(row.get("outputTokens"));
            thinking += num(row.get("thinkingTokens"));
            cached += num(row.get("cachedTokens"));
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("calls", calls);
        stats.put("modelInput", modelInput);
        stats.put("uncachedInput", uncachedInput);
        stats.put("modelOutput", modelOutput);
        stats.put("thinking", thinking);
        stats.put("cached", cached);
        return stats;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readAll() {
        if (!Files.exists(file)) return List.of();
        try {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                rows.add(mapper.readValue(line, Map.class));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading conversation log: " + file, e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b && b;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
