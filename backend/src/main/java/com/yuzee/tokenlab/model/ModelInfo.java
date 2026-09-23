package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Gemini model capability + pricing entry. Mirrors ModelCapabilityInfo from
 * yuzee-ai-token-lab/src/data/models.ts (serialized as GEMINI_MODELS in capabilities.modelsList).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelInfo {
    private String id;
    private String name;
    private String shortDescription;
    private String longDescription;
    private String family;            // "flash" | "flash-lite" | "legacy"
    private String categoryGroup;     // "Current" | "Flash-Lite" | "Legacy comparison" | "Retired"
    private String status;            // "current" | "stable" | "legacy" | "retired"
    private boolean available;
    private boolean selectable;
    private boolean freeTierEligible;
    private boolean supportsThinking;
    private String thinkingMechanism; // "level" | "budget" | "none"
    private List<String> supportedThinkingLevels;
    private String defaultThinkingLevel;
    private boolean supportsCaching;
    private boolean supportsInteractionsApi;
    private Boolean isRecommended;
    private Boolean isDefault;
    private String replacementModel;
    private String badge;
    private Double inputPricePerMToken;
    private Double outputPricePerMToken;
    private Double cachedReadPricePerMToken;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }
    public String getLongDescription() { return longDescription; }
    public void setLongDescription(String longDescription) { this.longDescription = longDescription; }
    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }
    public String getCategoryGroup() { return categoryGroup; }
    public void setCategoryGroup(String categoryGroup) { this.categoryGroup = categoryGroup; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public boolean isSelectable() { return selectable; }
    public void setSelectable(boolean selectable) { this.selectable = selectable; }
    public boolean isFreeTierEligible() { return freeTierEligible; }
    public void setFreeTierEligible(boolean freeTierEligible) { this.freeTierEligible = freeTierEligible; }
    public boolean isSupportsThinking() { return supportsThinking; }
    public void setSupportsThinking(boolean supportsThinking) { this.supportsThinking = supportsThinking; }
    public String getThinkingMechanism() { return thinkingMechanism; }
    public void setThinkingMechanism(String thinkingMechanism) { this.thinkingMechanism = thinkingMechanism; }
    public List<String> getSupportedThinkingLevels() { return supportedThinkingLevels; }
    public void setSupportedThinkingLevels(List<String> supportedThinkingLevels) { this.supportedThinkingLevels = supportedThinkingLevels; }
    public String getDefaultThinkingLevel() { return defaultThinkingLevel; }
    public void setDefaultThinkingLevel(String defaultThinkingLevel) { this.defaultThinkingLevel = defaultThinkingLevel; }
    public boolean isSupportsCaching() { return supportsCaching; }
    public void setSupportsCaching(boolean supportsCaching) { this.supportsCaching = supportsCaching; }
    public boolean isSupportsInteractionsApi() { return supportsInteractionsApi; }
    public void setSupportsInteractionsApi(boolean supportsInteractionsApi) { this.supportsInteractionsApi = supportsInteractionsApi; }
    public Boolean getIsRecommended() { return isRecommended; }
    public void setIsRecommended(Boolean isRecommended) { this.isRecommended = isRecommended; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getReplacementModel() { return replacementModel; }
    public void setReplacementModel(String replacementModel) { this.replacementModel = replacementModel; }
    public String getBadge() { return badge; }
    public void setBadge(String badge) { this.badge = badge; }
    public Double getInputPricePerMToken() { return inputPricePerMToken; }
    public void setInputPricePerMToken(Double inputPricePerMToken) { this.inputPricePerMToken = inputPricePerMToken; }
    public Double getOutputPricePerMToken() { return outputPricePerMToken; }
    public void setOutputPricePerMToken(Double outputPricePerMToken) { this.outputPricePerMToken = outputPricePerMToken; }
    public Double getCachedReadPricePerMToken() { return cachedReadPricePerMToken; }
    public void setCachedReadPricePerMToken(Double cachedReadPricePerMToken) { this.cachedReadPricePerMToken = cachedReadPricePerMToken; }
}
