package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.ModelInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for Gemini model capabilities, statuses, thinking
 * mechanisms and pricing. Ported from yuzee-ai-token-lab/src/data/models.ts
 * (GEMINI_MODELS, getModelInfo, calcTurnCost, getValidThinkingLevel, formatCost).
 */
@Service
public class GeminiModelRegistry {

    public static final String DEFAULT_MODEL_ID = "gemini-3.7-flash";

    private final List<ModelInfo> models = buildModels();

    /** GEMINI_MODELS.filter(m => m.selectable). */
    public List<ModelInfo> listModels() {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : models) {
            if (m.isSelectable()) out.add(m);
        }
        return out;
    }

    /** GEMINI_MODELS.filter(m => m.selectable).map(m => m.id). */
    public List<String> selectableModelIds() {
        List<String> out = new ArrayList<>();
        for (ModelInfo m : listModels()) out.add(m.getId());
        return out;
    }

    /** GEMINI_MODELS, including retired entries. */
    public List<ModelInfo> listAllModels() {
        return Collections.unmodifiableList(models);
    }

    /** getModelInfo(): the entry, or null when the id is unknown. */
    public ModelInfo getModel(String modelId) {
        for (ModelInfo m : models) {
            if (m.getId().equals(modelId)) return m;
        }
        return null;
    }

    /** getModelInfo(id)?.supportedThinkingLevels, null when unknown. */
    public List<String> supportedThinkingLevels(String modelId) {
        ModelInfo m = getModel(modelId);
        return m == null ? null : m.getSupportedThinkingLevels();
    }

    /** getModelInfo(id)?.thinkingMechanism, null when unknown. */
    public String thinkingMechanism(String modelId) {
        ModelInfo m = getModel(modelId);
        return m == null ? null : m.getThinkingMechanism();
    }

    /**
     * Exact port of calcTurnCost(modelId, usage). Reads inputTokens, outputTokens,
     * uncachedInputTokens, thinkingTokens and cachedTokens from the usage map. Returns null
     * where the TS returns null (unknown model or no/zero pricing) and where the TS result is
     * NaN (a missing inputTokens/outputTokens it needs), since JSON.stringify(NaN) is null.
     */
    public Double calcTurnCost(String modelId, Map<String, ?> usage) {
        ModelInfo model = getModel(modelId);
        if (model == null || !truthy(model.getInputPricePerMToken()) || !truthy(model.getOutputPricePerMToken())) return null;
        Double uncachedInputTokens = nullish(usage, "uncachedInputTokens");
        Double cachedTokens = nullish(usage, "cachedTokens");
        Double thinkingTokens = nullish(usage, "thinkingTokens");
        double uncachedInput = uncachedInputTokens != null
            ? uncachedInputTokens
            : jsNumber(usage, "inputTokens") - (cachedTokens != null ? cachedTokens : 0);
        double outputTotal = jsNumber(usage, "outputTokens") + (thinkingTokens != null ? thinkingTokens : 0);
        double cached = cachedTokens != null ? cachedTokens : 0;
        double cachedReadPrice = model.getCachedReadPricePerMToken() != null ? model.getCachedReadPricePerMToken() : 0;
        double cost = (jsMax0(uncachedInput) / 1_000_000) * model.getInputPricePerMToken()
            + (outputTotal / 1_000_000) * model.getOutputPricePerMToken()
            + (cached / 1_000_000) * cachedReadPrice;
        return Double.isNaN(cost) ? null : cost;
    }

    /**
     * Pre-existing shape kept for other callers: calcTurnCost(modelId, {inputTokens: promptTokens,
     * outputTokens, cachedTokens}) ?? 0. Callers pass output + thinking tokens as outputTokens.
     */
    public double calcTurnCost(String modelId, int promptTokens, int outputTokens, int cachedTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", promptTokens);
        usage.put("outputTokens", outputTokens);
        usage.put("cachedTokens", cachedTokens);
        Double cost = calcTurnCost(modelId, usage);
        return cost != null ? cost : 0.0;
    }

    /** Ported from formatCost() in models.ts. */
    public String formatCost(double usd) {
        if (usd < 0.00001) return "~<$0.00001";
        if (usd < 0.01) return String.format("~$%.5f", usd);
        return String.format("~$%.4f", usd);
    }

    /** Ported from getValidThinkingLevel() in models.ts. */
    public String getValidThinkingLevel(String modelId, String requestedLevel) {
        ModelInfo info = getModel(modelId);
        if (info == null || !info.isSupportsThinking()) return "low";
        if ("adaptive".equals(requestedLevel)) return "medium";
        if (info.getSupportedThinkingLevels() != null && info.getSupportedThinkingLevels().contains(requestedLevel)) {
            return requestedLevel;
        }
        return info.getDefaultThinkingLevel();
    }

    private static boolean truthy(Double price) {
        return price != null && price != 0 && !price.isNaN();
    }

    /** usage[key] for a `?? fallback` read: null when missing or null. */
    private static Double nullish(Map<String, ?> usage, String key) {
        Object v = usage == null ? null : usage.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** usage[key] used directly in JS arithmetic: null counts as 0, missing (undefined) as NaN. */
    private static double jsNumber(Map<String, ?> usage, String key) {
        if (usage == null || !usage.containsKey(key)) return Double.NaN;
        Object v = usage.get(key);
        return v == null ? 0 : v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    /** Math.max(0, x): NaN stays NaN. */
    private static double jsMax0(double x) {
        return Double.isNaN(x) ? Double.NaN : Math.max(0, x);
    }

    private static ModelInfo model(String id, String name, String shortDescription, String longDescription,
                                    String family, String categoryGroup, String status,
                                    boolean available, boolean selectable, boolean freeTierEligible,
                                    boolean supportsThinking, String thinkingMechanism, List<String> supportedThinkingLevels,
                                    String defaultThinkingLevel, boolean supportsCaching, boolean supportsInteractionsApi,
                                    Boolean isRecommended, Boolean isDefault, String replacementModel, String badge,
                                    Double inputPrice, Double outputPrice, Double cachedReadPrice) {
        ModelInfo m = new ModelInfo();
        m.setId(id);
        m.setName(name);
        m.setShortDescription(shortDescription);
        m.setLongDescription(longDescription);
        m.setFamily(family);
        m.setCategoryGroup(categoryGroup);
        m.setStatus(status);
        m.setAvailable(available);
        m.setSelectable(selectable);
        m.setFreeTierEligible(freeTierEligible);
        m.setSupportsThinking(supportsThinking);
        m.setThinkingMechanism(thinkingMechanism);
        m.setSupportedThinkingLevels(supportedThinkingLevels);
        m.setDefaultThinkingLevel(defaultThinkingLevel);
        m.setSupportsCaching(supportsCaching);
        m.setSupportsInteractionsApi(supportsInteractionsApi);
        m.setIsRecommended(isRecommended);
        m.setIsDefault(isDefault);
        m.setReplacementModel(replacementModel);
        m.setBadge(badge);
        m.setInputPricePerMToken(inputPrice);
        m.setOutputPricePerMToken(outputPrice);
        m.setCachedReadPricePerMToken(cachedReadPrice);
        return m;
    }

    private static List<String> levels(String... levels) {
        return Arrays.asList(levels);
    }

    private static List<ModelInfo> buildModels() {
        List<ModelInfo> list = new ArrayList<>();

        list.add(model("gemini-3.7-flash", "Gemini 3.7 Flash",
            "Newest, most capable Flash model for complex reasoning and multi-step tasks.",
            "Newest and most capable current Flash model. Strong for complex reasoning, coding and multi-step execution.",
            "flash", "Current", "current",
            true, true, true, true, "level", levels("low", "medium", "high"), "medium",
            true, true, null, true, null, "Default", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.8-flash", "Gemini 3.8 Flash",
            "Latest Flash model — faster and more efficient than 3.7.",
            "Latest Flash generation with improved speed and efficiency over 3.7 Flash.",
            "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, null, null, null, "New", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.6-flash", "Gemini 3.6 Flash",
            "Fast, high-quality general model with balanced intelligence and token efficiency.",
            "Fast, high-quality general Flash model with a strong balance between capability and token efficiency.",
            "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, true, null, null, "Recommended", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.5-flash", "Gemini 3.5 Flash",
            "Balanced Flash model — strong quality with full thinking support.",
            "High-capability Flash model with a strong balance between quality and cost.",
            "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, null, null, null, "Stable", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite",
            "Fast and efficient Flash-Lite model for lower-latency and high-throughput workloads.",
            "Fast and efficient Flash-Lite model intended for lower-latency and high-throughput workloads.",
            "flash-lite", "Flash-Lite", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "minimal",
            true, true, null, null, null, "Fast", 0.075, 0.30, 0.019));

        list.add(model("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite",
            "Earlier Flash-Lite generation useful as an efficiency and migration baseline.",
            "Earlier Flash-Lite generation useful as an efficiency and migration baseline.",
            "flash-lite", "Flash-Lite", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "low",
            true, true, null, null, null, null, 0.075, 0.30, 0.019));

        list.add(model("gemini-2.5-flash", "Gemini 2.5 Flash",
            "Legacy hybrid-reasoning model for comparing older thinking-budget behavior.",
            "Legacy hybrid-reasoning Flash model. Useful for comparing token usage against Gemini 3.x.",
            "legacy", "Legacy comparison", "legacy",
            true, true, true, true, "budget", levels("minimal", "low", "medium", "high"), "low",
            true, false, null, null, null, "Legacy", 0.10, 0.40, 0.025));

        list.add(model("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite",
            "Legacy lightweight Gemini 2.5 model useful as an older efficiency baseline.",
            "Legacy lightweight Gemini 2.5 model useful as an older efficiency baseline.",
            "legacy", "Legacy comparison", "legacy",
            true, true, true, true, "budget", levels("minimal", "low", "medium", "high"), "minimal",
            true, false, null, null, null, "Legacy", 0.038, 0.15, 0.010));

        list.add(model("gemini-2.0-flash", "Gemini 2.0 Flash",
            "Retired. No longer callable. Replacement: Gemini 3.6 Flash.",
            "Retired model generation. Displayed for historical comparison only; not callable.",
            "legacy", "Retired", "retired",
            false, false, false, false, "none", Collections.emptyList(), "low",
            false, false, null, null, "gemini-3.6-flash", "Retired", null, null, null));

        list.add(model("gemini-2.0-flash-lite", "Gemini 2.0 Flash-Lite",
            "Retired. No longer callable. Replacement: Gemini 3.5 Flash-Lite.",
            "Retired model generation. Displayed for historical comparison only; not callable.",
            "legacy", "Retired", "retired",
            false, false, false, false, "none", Collections.emptyList(), "minimal",
            false, false, null, null, "gemini-3.5-flash-lite", "Retired", null, null, null));

        return list;
    }
}
