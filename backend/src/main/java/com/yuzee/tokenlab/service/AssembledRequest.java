package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.CompactionMetrics;

/** Result of {@link RequestAssemblerService#assembleRequest}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssembledRequest {
    /** Final system prompt text (base + Oala/context additions — currently base + short-reply guidance only). */
    public String systemInstruction;
    /** From MultiTurnRequestBuilder. Null when bypassResponseText is set. */
    public ArrayNode contents;
    /** thinkingConfig, responseSchema (sanitized), maxOutputTokens, responseMimeType — merge-ready for GeminiService. */
    public ObjectNode generationConfigExtras;
    /** Non-null => caller should short-circuit and NOT call Gemini; use this literal response text as-is. */
    public String bypassResponseText;
    /** From ConversationMemoryService, for an SSE 'compaction' event. Null when bypassed. */
    public CompactionMetrics compactionMetrics;
}
