package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.ModelInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for Gemini model capabilities, statuses, thinking
 * mechanisms and pricing. Ported from yuzee-ai-token-lab/src/data/models.ts
 * (GEMINI_MODELS, calcTurnCost, getValidThinkingLevel, formatCost).
 */
@Service
public class GeminiModelRegistry {

    public static final String DEFAULT_MODEL_ID = "gemini-3.7-flash";

    private final List<ModelInfo> models = buildModels();

    /** Non-retired, selectable models — what the UI's model picker should offer. */
    public List<ModelInfo> listModels() {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : models) {
            if (m.isSelectable()) out.add(m);
        }
        return out;
    }

    /** All 10 entries, including retired ones (e.g. for a "legacy comparison" view). */
    public List<ModelInfo> listAllModels() {
        return Collections.unmodifiableList(models);
    }

    /**
     * Looks up a model by id.
     * @return the ModelInfo, or null if modelId is unknown/unrecognized (callers that need
     *         pricing/thinking-level behavior for an unknown id should fall back to
     *         DEFAULT_MODEL_ID themselves, same as this registry's own helpers below do).
     */
    public ModelInfo getModel(String modelId) {
        for (ModelInfo m : models) {
            if (m.getId().equals(modelId)) return m;
        }
        return null;
    }

    /**
     * Ported from calcTurnCost() in models.ts. The TS version takes a usage object with an
     * optional uncachedInputTokens/thinkingTokens; this Java port folds thinkingTokens into
     * outputTokens (callers should add them together) and derives uncached input as
     * promptTokens - cachedTokens, matching the TS default when uncachedInputTokens is omitted.
     * @return the cost in USD, or 0.0 if the model is unknown or has no pricing configured.
     */
    public double calcTurnCost(String modelId, int promptTokens, int outputTokens, int cachedTokens) {
        ModelInfo model = getModel(modelId);
        if (model == null || model.getInputPricePerMToken() == null || model.getOutputPricePerMToken() == null) {
            return 0.0;
        }
        int uncachedInput = Math.max(0, promptTokens - cachedTokens);
        double cachedPrice = model.getCachedReadPricePerMToken() != null ? model.getCachedReadPricePerMToken() : 0.0;
        return (uncachedInput / 1_000_000.0) * model.getInputPricePerMToken()
            + (outputTokens / 1_000_000.0) * model.getOutputPricePerMToken()
            + (cachedTokens / 1_000_000.0) * cachedPrice;
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

    private static ModelInfo model(String id, String name, String family, String categoryGroup, String status,
                                    boolean available, boolean selectable, boolean freeTierEligible,
                                    boolean supportsThinking, String thinkingMechanism, List<String> supportedThinkingLevels,
                                    String defaultThinkingLevel, boolean supportsCaching, boolean supportsInteractionsApi,
                                    Boolean isRecommended, Boolean isDefault, String replacementModel, String badge,
                                    Double inputPrice, Double outputPrice, Double cachedReadPrice) {
        ModelInfo m = new ModelInfo();
        m.setId(id);
        m.setName(name);
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

        list.add(model("gemini-3.7-flash", "Gemini 3.7 Flash", "flash", "Current", "current",
            true, true, true, true, "level", levels("low", "medium", "high"), "medium",
            true, true, null, true, null, "Default", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.8-flash", "Gemini 3.8 Flash", "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, null, null, null, "New", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.6-flash", "Gemini 3.6 Flash", "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, true, null, null, "Recommended", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.5-flash", "Gemini 3.5 Flash", "flash", "Current", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "medium",
            true, true, null, null, null, "Stable", 0.10, 0.40, 0.025));

        list.add(model("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "flash-lite", "Flash-Lite", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "minimal",
            true, true, null, null, null, "Fast", 0.075, 0.30, 0.019));

        list.add(model("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", "flash-lite", "Flash-Lite", "stable",
            true, true, true, true, "level", levels("minimal", "low", "medium", "high"), "low",
            true, true, null, null, null, null, 0.075, 0.30, 0.019));

        list.add(model("gemini-2.5-flash", "Gemini 2.5 Flash", "legacy", "Legacy comparison", "legacy",
            true, true, true, true, "budget", levels("minimal", "low", "medium", "high"), "low",
            true, false, null, null, null, "Legacy", 0.10, 0.40, 0.025));

        list.add(model("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", "legacy", "Legacy comparison", "legacy",
            true, true, true, true, "budget", levels("minimal", "low", "medium", "high"), "minimal",
            true, false, null, null, null, "Legacy", 0.038, 0.15, 0.010));

        list.add(model("gemini-2.0-flash", "Gemini 2.0 Flash", "legacy", "Retired", "retired",
            false, false, false, false, "none", Collections.emptyList(), "low",
            false, false, null, null, "gemini-3.6-flash", "Retired", null, null, null));

        list.add(model("gemini-2.0-flash-lite", "Gemini 2.0 Flash-Lite", "legacy", "Retired", "retired",
            false, false, false, false, "none", Collections.emptyList(), "minimal",
            false, false, null, null, "gemini-3.5-flash-lite", "Retired", null, null, null));

        return list;
    }
}
