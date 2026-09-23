package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** YuzeeRequestAssembler.ts AssembledGeminiRequest. */
public class AssembledRequest {
    public String aiRequestId;
    public String model;
    public String systemInstruction;
    /** A TextNode (single-text contents) or an ArrayNode of Gemini Content objects. */
    public JsonNode contents;
    /** geminiConfig minus systemInstruction: responseMimeType, responseSchema, maxOutputTokens, temperature, topP, thinkingConfig. */
    public ObjectNode geminiConfig;
    public String appliedThinkingLevel;
    public int numericThinkingBudget;
    public int maxOutputTokens;
    public int dynamicContextTokenCount;
    public int currentMessageTokenCount;
    public Map<String, Object> careerContext;
    public long requestReceivedAt;
    public long preProviderLatencyMs;
}
