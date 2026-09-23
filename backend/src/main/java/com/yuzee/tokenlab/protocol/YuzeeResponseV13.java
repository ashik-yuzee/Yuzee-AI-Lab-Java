package com.yuzee.tokenlab.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * POJO mirroring the TS YuzeeResponseV13 top-level envelope
 * (src/protocol/v1.3/Yuzee_Response_Protocol_v1.3.ts).
 *
 * content_blocks deliberately stays untyped (List&lt;Map&lt;String,Object&gt;&gt;) rather than
 * one class per block type: v1.3 has 15+ block shapes and v1.4 adds a typed `data`
 * discriminated union on top, so validation (see ProtocolValidator) reads
 * block-type-specific fields dynamically off the parsed JsonNode, exactly like
 * validator.ts does in JS. This class exists for callers that want a typed handle
 * on the envelope fields that do NOT vary by block type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class YuzeeResponseV13 {

    private String schemaVersion = "1.3";
    private String currentMode;
    private String responseIntent;
    private List<Map<String, Object>> contentBlocks = new ArrayList<>();
    private YuzeeInteraction interaction;
    private YuzeeService serviceTrigger;
    private YuzeeRmoReadiness rmoReadiness;
    private YuzeeState state;
    private YuzeeFollowups followups;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getCurrentMode() { return currentMode; }
    public void setCurrentMode(String currentMode) { this.currentMode = currentMode; }
    public String getResponseIntent() { return responseIntent; }
    public void setResponseIntent(String responseIntent) { this.responseIntent = responseIntent; }
    public List<Map<String, Object>> getContentBlocks() { return contentBlocks; }
    public void setContentBlocks(List<Map<String, Object>> contentBlocks) { this.contentBlocks = contentBlocks; }
    public YuzeeInteraction getInteraction() { return interaction; }
    public void setInteraction(YuzeeInteraction interaction) { this.interaction = interaction; }
    public YuzeeService getServiceTrigger() { return serviceTrigger; }
    public void setServiceTrigger(YuzeeService serviceTrigger) { this.serviceTrigger = serviceTrigger; }
    public YuzeeRmoReadiness getRmoReadiness() { return rmoReadiness; }
    public void setRmoReadiness(YuzeeRmoReadiness rmoReadiness) { this.rmoReadiness = rmoReadiness; }
    public YuzeeState getState() { return state; }
    public void setState(YuzeeState state) { this.state = state; }
    public YuzeeFollowups getFollowups() { return followups; }
    public void setFollowups(YuzeeFollowups followups) { this.followups = followups; }

    // ---- interaction ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeInteraction {
        private String kind = "none";
        private String inputType = "none";
        private String questionId = "";
        private String question = "";
        private List<YuzeeOption> options = new ArrayList<>();
        private boolean allowOtherInput;
        private String otherInputLabel = "";
        private List<YuzeeField> fields = new ArrayList<>();
        private List<RecommendedAction> recommendedActions = new ArrayList<>();

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getInputType() { return inputType; }
        public void setInputType(String inputType) { this.inputType = inputType; }
        public String getQuestionId() { return questionId; }
        public void setQuestionId(String questionId) { this.questionId = questionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public List<YuzeeOption> getOptions() { return options; }
        public void setOptions(List<YuzeeOption> options) { this.options = options; }
        public boolean isAllowOtherInput() { return allowOtherInput; }
        public void setAllowOtherInput(boolean allowOtherInput) { this.allowOtherInput = allowOtherInput; }
        public String getOtherInputLabel() { return otherInputLabel; }
        public void setOtherInputLabel(String otherInputLabel) { this.otherInputLabel = otherInputLabel; }
        public List<YuzeeField> getFields() { return fields; }
        public void setFields(List<YuzeeField> fields) { this.fields = fields; }
        public List<RecommendedAction> getRecommendedActions() { return recommendedActions; }
        public void setRecommendedActions(List<RecommendedAction> recommendedActions) { this.recommendedActions = recommendedActions; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeOption {
        private String id;
        private String label;
        private String description = "";
        private String value;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    /** field.id is one of goal|location|residency; input_type is text|australian_location|single_select. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeField {
        private String id;
        private String label;
        private String inputType;
        private boolean required;
        private List<YuzeeOption> options = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getInputType() { return inputType; }
        public void setInputType(String inputType) { this.inputType = inputType; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public List<YuzeeOption> getOptions() { return options; }
        public void setOptions(List<YuzeeOption> options) { this.options = options; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RecommendedAction {
        private String id;
        private String label;
        private String message;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    // ---- service_trigger ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeService {
        private boolean serviceIntentDetected;
        private String primaryRequestedService = "NONE";
        private String confidence;
        private String reason = "";
        private boolean triggerNow;
        private boolean needsMoreClarity;
        private List<ServiceAction> actions = new ArrayList<>();

        public boolean isServiceIntentDetected() { return serviceIntentDetected; }
        public void setServiceIntentDetected(boolean serviceIntentDetected) { this.serviceIntentDetected = serviceIntentDetected; }
        public String getPrimaryRequestedService() { return primaryRequestedService; }
        public void setPrimaryRequestedService(String primaryRequestedService) { this.primaryRequestedService = primaryRequestedService; }
        public String getConfidence() { return confidence; }
        public void setConfidence(String confidence) { this.confidence = confidence; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public boolean isTriggerNow() { return triggerNow; }
        public void setTriggerNow(boolean triggerNow) { this.triggerNow = triggerNow; }
        public boolean isNeedsMoreClarity() { return needsMoreClarity; }
        public void setNeedsMoreClarity(boolean needsMoreClarity) { this.needsMoreClarity = needsMoreClarity; }
        public List<ServiceAction> getActions() { return actions; }
        public void setActions(List<ServiceAction> actions) { this.actions = actions; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ServiceAction {
        private String id;
        private String title;
        private String description;
        private String actionId;
        private String rmoType = "";
        private boolean requiresConfirmation;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getActionId() { return actionId; }
        public void setActionId(String actionId) { this.actionId = actionId; }
        public String getRmoType() { return rmoType; }
        public void setRmoType(String rmoType) { this.rmoType = rmoType; }
        public boolean isRequiresConfirmation() { return requiresConfirmation; }
        public void setRequiresConfirmation(boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }
    }

    // ---- rmo_readiness ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeRmoReadiness {
        private String readiness;
        private boolean readyToGenerate;
        private List<String> missingInputs = new ArrayList<>();
        private boolean verificationRequired;

        public String getReadiness() { return readiness; }
        public void setReadiness(String readiness) { this.readiness = readiness; }
        public boolean isReadyToGenerate() { return readyToGenerate; }
        public void setReadyToGenerate(boolean readyToGenerate) { this.readyToGenerate = readyToGenerate; }
        public List<String> getMissingInputs() { return missingInputs; }
        public void setMissingInputs(List<String> missingInputs) { this.missingInputs = missingInputs; }
        public boolean isVerificationRequired() { return verificationRequired; }
        public void setVerificationRequired(boolean verificationRequired) { this.verificationRequired = verificationRequired; }
    }

    // ---- state ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeState {
        private Map<String, Object> activityContext;
        private String activeResponseMode;
        private String effectiveResponseMode;
        private String modeSource;
        private boolean safetyOverrideApplied;
        private UserConfidenceState userConfidence;
        private YuzeeProgress progress;

        public Map<String, Object> getActivityContext() { return activityContext; }
        public void setActivityContext(Map<String, Object> activityContext) { this.activityContext = activityContext; }
        public String getActiveResponseMode() { return activeResponseMode; }
        public void setActiveResponseMode(String activeResponseMode) { this.activeResponseMode = activeResponseMode; }
        public String getEffectiveResponseMode() { return effectiveResponseMode; }
        public void setEffectiveResponseMode(String effectiveResponseMode) { this.effectiveResponseMode = effectiveResponseMode; }
        public String getModeSource() { return modeSource; }
        public void setModeSource(String modeSource) { this.modeSource = modeSource; }
        public boolean isSafetyOverrideApplied() { return safetyOverrideApplied; }
        public void setSafetyOverrideApplied(boolean safetyOverrideApplied) { this.safetyOverrideApplied = safetyOverrideApplied; }
        public UserConfidenceState getUserConfidence() { return userConfidence; }
        public void setUserConfidence(UserConfidenceState userConfidence) { this.userConfidence = userConfidence; }
        public YuzeeProgress getProgress() { return progress; }
        public void setProgress(YuzeeProgress progress) { this.progress = progress; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class UserConfidenceState {
        private int score = -1;
        private String band = "unknown";
        private String evidenceStrength = "none";
        private String trend = "unknown";
        private List<String> reasonCodes = new ArrayList<>();

        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public String getBand() { return band; }
        public void setBand(String band) { this.band = band; }
        public String getEvidenceStrength() { return evidenceStrength; }
        public void setEvidenceStrength(String evidenceStrength) { this.evidenceStrength = evidenceStrength; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
        public List<String> getReasonCodes() { return reasonCodes; }
        public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
    }

    /** security_breach_count/active_security_penalty are server-authoritative — see SecurityStateService. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeProgress {
        private boolean explained;
        private int failedAttempts;
        private int loopCountSameIssue;
        private int securityBreachCount;
        private String activeSecurityPenalty = "";

        public boolean isExplained() { return explained; }
        public void setExplained(boolean explained) { this.explained = explained; }
        public int getFailedAttempts() { return failedAttempts; }
        public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
        public int getLoopCountSameIssue() { return loopCountSameIssue; }
        public void setLoopCountSameIssue(int loopCountSameIssue) { this.loopCountSameIssue = loopCountSameIssue; }
        public int getSecurityBreachCount() { return securityBreachCount; }
        public void setSecurityBreachCount(int securityBreachCount) { this.securityBreachCount = securityBreachCount; }
        public String getActiveSecurityPenalty() { return activeSecurityPenalty; }
        public void setActiveSecurityPenalty(String activeSecurityPenalty) { this.activeSecurityPenalty = activeSecurityPenalty; }
    }

    // ---- followups ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class YuzeeFollowups {
        private boolean enabled;
        private boolean cancelOnUserMessage;
        private boolean topicLock;
        private String topicKey = "";
        private List<FollowupTrigger> triggers = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isCancelOnUserMessage() { return cancelOnUserMessage; }
        public void setCancelOnUserMessage(boolean cancelOnUserMessage) { this.cancelOnUserMessage = cancelOnUserMessage; }
        public boolean isTopicLock() { return topicLock; }
        public void setTopicLock(boolean topicLock) { this.topicLock = topicLock; }
        public String getTopicKey() { return topicKey; }
        public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
        public List<FollowupTrigger> getTriggers() { return triggers; }
        public void setTriggers(List<FollowupTrigger> triggers) { this.triggers = triggers; }
    }

    /** delay_seconds is one of 10|300|600 in the TS type; kept as int here. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class FollowupTrigger {
        private int delaySeconds;
        private String message;

        public int getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(int delaySeconds) { this.delaySeconds = delaySeconds; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
