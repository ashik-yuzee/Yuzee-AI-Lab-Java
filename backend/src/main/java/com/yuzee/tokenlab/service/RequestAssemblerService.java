package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.yuzee.tokenlab.model.DialogueTurn;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim;

/**
 * Java port of YuzeeRequestAssembler.ts: prompt/schema identity, thinking resolution, the bypass
 * classifier, user-event formatting and assembleRequest().
 */
@Service
public class RequestAssemblerService {

    private static final String RESPONSE_SCHEMA_CLASSPATH = "prompts/response-schema-v1.3.json";
    private static final String EXPERIENCE_RULES_CLASSPATH = "prompts/experience-rules.md";

    private final SystemPromptService systemPromptService;
    private final MultiTurnRequestBuilder contentBuilder;
    private final TokenService tokenService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> sanitizedSchema = new AtomicReference<>();
    private volatile String schemaHash = "";
    private volatile String experienceRules = "";

    public RequestAssemblerService(SystemPromptService systemPromptService, MultiTurnRequestBuilder contentBuilder,
                                   TokenService tokenService) {
        this.systemPromptService = systemPromptService;
        this.contentBuilder = contentBuilder;
        this.tokenService = tokenService;
    }

    @PostConstruct
    void init() {
        sanitizedSchema.set(loadSanitizedSchema());
        experienceRules = readResource(EXPERIENCE_RULES_CLASSPATH);
    }

    public String getPromptContent() { return systemPromptService.getPrompt(); }
    public String getPromptHash() { return systemPromptService.getHash(); }
    public String getSchemaHash() { return schemaHash; }

    // ------------------------------------------------------------------
    // assembleRequest
    // ------------------------------------------------------------------

    /** assembleRequest() params. */
    public static class Params {
        public String model;
        public String messageText;
        public String microToolInstruction;
        public String oalaInstruction;
        public JsonNode userEvent;
        public Map<String, Object> careerContext;
        public String summaryText;
        public String recentHistoryText;
        public String responseMode;
        public String thinkingLevel;
        public String customSystemPrompt;
        public String systemPromptMode;
        public Double temperature;
        public Double topP;
        public Integer maxOutputTokens;
        public Boolean useMultiTurn;
        public List<DialogueTurn> keptTurns;
        public Boolean useStructuredOutput;
    }

    public AssembledRequest assembleRequest(Params params) {
        long requestReceivedAt = System.currentTimeMillis();
        String aiRequestId = "req-" + System.currentTimeMillis() + "-" + randomBase36(5);

        // 1. System instruction
        String systemInstruction = getPromptContent();
        if ("custom".equals(params.systemPromptMode) && params.customSystemPrompt != null
            && !jsTrim(params.customSystemPrompt).isEmpty()) {
            systemInstruction = jsTrim(params.customSystemPrompt) + "\n" + experienceRules;
        }
        if (params.oalaInstruction != null && !params.oalaInstruction.isEmpty()) {
            systemInstruction += "\n\n" + params.oalaInstruction;
        }

        // 2. Dynamic context
        String careerStr = formatCareerContext(params.careerContext);
        List<String> dynamicSections = new ArrayList<>();
        if (!careerStr.isEmpty()) dynamicSections.add(careerStr);
        if (params.summaryText != null && !jsTrim(params.summaryText).isEmpty()) {
            dynamicSections.add("PREVIOUS_CONVERSATION_SUMMARY:\n" + jsTrim(params.summaryText));
        }
        if (params.recentHistoryText != null && !jsTrim(params.recentHistoryText).isEmpty()) {
            dynamicSections.add("RECENT_DIALOGUE_TURNS:\n" + jsTrim(params.recentHistoryText));
        }
        String dynamicContextStr = String.join("\n\n", dynamicSections);
        int dynamicContextTokenCount = tokenService.estimate(dynamicContextStr);

        // 3. Current user input
        String userInput = formatUserEvent(params.messageText, params.userEvent, params.responseMode);
        String currentUserStr = params.microToolInstruction != null && !params.microToolInstruction.isEmpty()
            ? userInput + "\n\n" + params.microToolInstruction : userInput;
        int currentMessageTokenCount = tokenService.estimate(currentUserStr);

        // 4. Contents
        JsonNode contents;
        if (Boolean.TRUE.equals(params.useMultiTurn) && params.keptTurns != null) {
            contents = contentBuilder.buildMultiTurnContents(careerStr,
                params.summaryText != null ? params.summaryText : "", params.keptTurns, currentUserStr, true);
        } else {
            contents = TextNode.valueOf(!dynamicContextStr.isEmpty() ? dynamicContextStr + "\n\n" + currentUserStr : currentUserStr);
        }

        // 5. Config
        String model = params.model != null && !params.model.isEmpty() ? params.model : "gemini-3.5-flash-lite";
        String responseMode = params.responseMode != null && !params.responseMode.isEmpty() ? params.responseMode : "standard";
        int maxOutputTokens = params.maxOutputTokens != null ? params.maxOutputTokens : resolveOutputBudget(responseMode, model);
        ThinkingResolution thinking = resolveThinkingConfig(model,
            params.thinkingLevel != null && !params.thinkingLevel.isEmpty() ? params.thinkingLevel : "adaptive",
            params.messageText);

        boolean structuredOutput = Boolean.TRUE.equals(params.useStructuredOutput);
        ObjectNode geminiConfig = mapper.createObjectNode();
        if (structuredOutput) geminiConfig.put("responseMimeType", "application/json");
        JsonNode schema = getSanitizedResponseSchema();
        if (structuredOutput && schema != null) geminiConfig.set("responseSchema", schema);
        geminiConfig.put("maxOutputTokens", maxOutputTokens);
        if (params.temperature != null) geminiConfig.put("temperature", params.temperature);
        if (params.topP != null) geminiConfig.put("topP", params.topP);
        if (thinking.thinkingConfig != null) geminiConfig.set("thinkingConfig", thinking.thinkingConfig);

        AssembledRequest out = new AssembledRequest();
        out.aiRequestId = aiRequestId;
        out.model = model;
        out.systemInstruction = systemInstruction;
        out.contents = contents;
        out.geminiConfig = geminiConfig;
        out.appliedThinkingLevel = thinking.appliedThinkingLevel;
        out.numericThinkingBudget = thinking.numericBudget;
        out.maxOutputTokens = maxOutputTokens;
        out.dynamicContextTokenCount = dynamicContextTokenCount;
        out.currentMessageTokenCount = currentMessageTokenCount;
        out.careerContext = params.careerContext;
        out.requestReceivedAt = requestReceivedAt;
        out.preProviderLatencyMs = System.currentTimeMillis() - requestReceivedAt;
        return out;
    }

    /** resolveOutputBudget() in the original always returns 65536 regardless of mode/model. */
    public int resolveOutputBudget(String mode, String model) {
        return 65536;
    }

    private static String randomBase36(int len) {
        StringBuilder sb = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) sb.append(Character.forDigit(r.nextInt(36), 36));
        return sb.toString();
    }

    public String formatCareerContext(Map<String, Object> capsule) {
        if (capsule == null) return "";
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : capsule.entrySet()) {
            if (!(e.getValue() instanceof String v) || jsTrim(v).isEmpty()) continue;
            lines.add("- [" + e.getKey() + "]: " + jsTrim(v));
        }
        if (lines.isEmpty()) return "";
        return "YUZEE_STRUCTURED_MEMORY_CAPSULE:\n" + String.join("\n", lines);
    }

    /** normalizeModeCapitalization() */
    public String normalizeModeCapitalization(String mode) {
        String m = jsTrim(mode == null || mode.isEmpty() ? "Standard" : mode).toLowerCase(Locale.ROOT);
        return switch (m) {
            case "quick" -> "Quick";
            case "explain" -> "Explain";
            case "explore" -> "Explore";
            case "detail" -> "Detail";
            case "decide" -> "Decide";
            default -> "Standard";
        };
    }

    private static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (n.isTextual()) return !n.asText().isEmpty();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asDouble() != 0;
        return true;
    }

    private static JsonNode path(JsonNode n, String... keys) {
        JsonNode cur = n;
        for (String k : keys) {
            if (cur == null || !cur.isObject()) return null;
            cur = cur.get(k);
        }
        return cur;
    }

    /** formatUserEvent(): raw text for plain messages, a USER_EVENT JSON block for structured interactions. */
    public String formatUserEvent(String messageText, JsonNode userEvent, String selectedMode) {
        String text = messageText == null ? "" : messageText;
        boolean hasInteraction = truthy(path(userEvent, "interaction")) || truthy(path(userEvent, "userEvent", "interaction"))
            || truthy(path(userEvent, "type"));
        JsonNode explicitModeNode = truthy(path(userEvent, "ui", "selected_mode")) ? path(userEvent, "ui", "selected_mode")
            : path(userEvent, "userEvent", "ui", "selected_mode");
        boolean explicitMode = truthy(explicitModeNode);

        if (!hasInteraction && !explicitMode) return jsTrim(text);

        ObjectNode eventPayload = mapper.createObjectNode();
        ObjectNode ui = mapper.createObjectNode();
        JsonNode srcUi = truthy(path(userEvent, "ui")) ? path(userEvent, "ui") : path(userEvent, "userEvent", "ui");
        if (truthy(srcUi) && srcUi.isObject()) ui.setAll((ObjectNode) srcUi.deepCopy());
        eventPayload.set("ui", ui);
        if (explicitMode) ui.put("selected_mode", normalizeModeCapitalization(explicitModeNode.asText()));

        if (truthy(path(userEvent, "interaction"))) {
            eventPayload.set("interaction", path(userEvent, "interaction"));
        } else if (truthy(path(userEvent, "userEvent", "interaction"))) {
            eventPayload.set("interaction", path(userEvent, "userEvent", "interaction"));
        } else if (truthy(path(userEvent, "type"))) {
            ObjectNode inter = mapper.createObjectNode();
            JsonNode iid = path(userEvent, "interaction_id");
            inter.set("question_id", truthy(iid) ? iid : TextNode.valueOf("active_question"));
            JsonNode optionId = path(userEvent, "option_id");
            if (truthy(optionId)) inter.set("selected_option_ids", mapper.createArrayNode().add(optionId));
            else if (truthy(path(userEvent, "selected_option_ids"))) inter.set("selected_option_ids", path(userEvent, "selected_option_ids"));
            // JSON.stringify drops undefined (absent) values but keeps null
            JsonNode ranked = truthy(path(userEvent, "ranked_ids")) ? path(userEvent, "ranked_ids") : path(userEvent, "ranked_option_ids");
            if (ranked != null) inter.set("ranked_option_ids", ranked);
            JsonNode fields = path(userEvent, "fields");
            if (fields != null) inter.set("fields", fields);
            JsonNode selfInput = truthy(path(userEvent, "value")) ? path(userEvent, "value") : path(userEvent, "self_input");
            if (selfInput != null) inter.set("self_input", selfInput);
            JsonNode actionId = path(userEvent, "action_id");
            if (actionId != null) inter.set("action_id", actionId);
            eventPayload.set("interaction", inter);
        }

        if (!jsTrim(text).isEmpty() && !eventPayload.has("interaction")) {
            eventPayload.put("user_text", jsTrim(text));
        } else if (!jsTrim(text).isEmpty() && eventPayload.has("interaction")) {
            eventPayload.put("supplementary_text", jsTrim(text));
        }
        return "USER_EVENT:\n" + stringifyIndented(eventPayload, "  ", "");
    }

    /** JSON.stringify(value, null, 2) formatting (": " separators, one element per line, "[]"/"{}" when empty). */
    static String stringifyIndented(JsonNode node, String step, String indent) {
        if (node == null || node.isNull() || node.isMissingNode()) return "null";
        if (node.isObject()) {
            if (node.isEmpty()) return "{}";
            String inner = indent + step;
            StringBuilder sb = new StringBuilder("{\n");
            var it = node.fields();
            boolean first = true;
            while (it.hasNext()) {
                var e = it.next();
                if (!first) sb.append(",\n");
                first = false;
                sb.append(inner).append(JsJson.quote(e.getKey())).append(": ")
                    .append(stringifyIndented(e.getValue(), step, inner));
            }
            return sb.append('\n').append(indent).append('}').toString();
        }
        if (node.isArray()) {
            if (node.isEmpty()) return "[]";
            String inner = indent + step;
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append(inner).append(stringifyIndented(node.get(i), step, inner));
            }
            return sb.append('\n').append(indent).append(']').toString();
        }
        return JsJson.stringify(node);
    }

    // ------------------------------------------------------------------
    // classifyUserMessage
    // ------------------------------------------------------------------

    private static final Pattern[] GREETING_PATTERNS = {
        jsRegex("^(hi|hey|hello|howdy|hiya|sup|yo)(\\s+(there|oala|yuzee|bot|ai|friend))?$", false),
        jsRegex("^good\\s+(morning|afternoon|evening|day)(\\s+(oala|yuzee))?$", false),
        jsRegex("^how are you(\\s+(doing|going|today))?$", false),
        jsRegex("^(are you there|you there|you working|is this working|test|testing|hello\\?)$", false),
        jsRegex("^what('s| is) up(\\s+with you)?$", false),
    };

    private static final Pattern[] FAREWELL_PATTERNS = {
        jsRegex("^(bye|goodbye|see you|see ya|cya|ttyl|later|take care)(\\s+(later|soon|then|now))?$", false),
        jsRegex("^(thanks|thank you|thx|ty|cheers|great|awesome|perfect|got it|ok|okay|cool|nice|sounds good)(\\s+(for (that|everything|your help|the help)))?$", false),
        jsRegex("^(that('s| is) (great|helpful|perfect|all|enough)|no (more )?questions?|i('m| am) (done|good|all set|all good))$", false),
    };

    private static final Pattern[] IDLE_PATTERNS = {
        jsRegex("^(lo+l+o*|lmao|lmfao|rofl|ha(ha)+|he(he)+|hah|lel|lulz|xd|😂|🤣|omg|omfg|wtf|smh|fml)$", false),
        jsRegex("^(meh|whatever|whatevs|idc|i don'?t care|boring|ugh|bleh|mmmh?|hmm+)$", false),
        jsRegex("^i('m| am) bored(\\s+(rn|right now|today|tbh))?$", false),
        jsRegex("^you('re| are) (funny|hilarious|great|amazing|cool|nice|smart|the best)$", false),
        jsRegex("^(nice one|good one|haha nice|that('s| is) funny|made me (laugh|smile))$", false),
        jsRegex("^(what('s| is) the (time|weather|date|temp(erature)?)|what day is it)$", false),
        jsRegex("^(tell me a joke|say something funny|make me laugh|entertain me)$", false),
        jsRegex("^(sing( me a song)?|dance|do a trick|flip a coin|roll (a )?d(ice|6|20))$", false),
        jsRegex("^(what('s| is) (your (name|age|favourite|favorite|hobby|hobbies))|do you (like|love|hate|eat|sleep|dream))$", false),
        jsRegex("^(are you (a robot|an ai|sentient|alive|human|real)|who (made|built|created) you)$", false),
        jsRegex("^(how old are you|where are you from|what are you|who are you)$", false),
        jsRegex("^(nothing|never ?mind|no ?thing|just (browsing|looking|chilling|vibing|kidding|joking)|jk|nm|nvm|nevermind)$", false),
    };

    private static final Pattern LETTER_OR_NUMBER = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern TRAILING_PUNCTUATION = jsRegex("[!?.,']+$", false);

    /** Returns greeting | farewell | rubbish | idle | career. */
    public String classifyUserMessage(String text, boolean hasConversation, boolean hasActiveQuestion) {
        String raw = text == null ? "" : text;
        if (!jsTrim(raw).isEmpty() && (hasConversation || hasActiveQuestion)) return "career";
        String t = jsTrim(TRAILING_PUNCTUATION.matcher(jsTrim(raw).toLowerCase(Locale.ROOT)).replaceFirst(""));
        for (Pattern p : GREETING_PATTERNS) if (p.matcher(t).find()) return "greeting";
        for (Pattern p : FAREWELL_PATTERNS) if (p.matcher(t).find()) return "farewell";
        for (Pattern p : IDLE_PATTERNS) if (p.matcher(t).find()) return "idle";
        if (t.isEmpty() || !LETTER_OR_NUMBER.matcher(t).find()) return "rubbish";
        return "career";
    }

    /** ux/bypassCopy.ts */
    public static String bypassCopy(String kind) {
        return switch (kind) {
            case "greeting" -> "Hi, I'm Oala. I can help you explore courses, skills and career options. What would you like help with?";
            case "farewell" -> "You're welcome. You can return whenever you want to explore your next step.";
            case "idle" -> "I can help with courses, skills, career choices and Yuzee services. What would you like to explore?";
            default -> "I couldn't tell what you meant from that message. You can use a few words, such as ‘course quality’, ‘study costs’ or ‘finding a job’. What would you like help with?";
        };
    }

    // ------------------------------------------------------------------
    // shortReplyGuidance (ux/shortReplyGuidance.ts)
    // ------------------------------------------------------------------

    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    private static final Pattern REPLY_END = jsRegex("[.!?]+$", false);
    private static final Pattern JS_SPACES = jsRegex("\\s+", false);

    private static String normaliseReply(String value) {
        return jsTrim(REPLY_END.matcher(jsTrim(value == null ? "" : value).toLowerCase(Locale.ROOT)).replaceFirst(""));
    }

    /** history: [{role, content}] in conversation order. */
    public String shortReplyGuidance(String text, List<Map.Entry<String, String>> history, boolean structured) {
        String current = normaliseReply(text);
        if (structured || current.isEmpty() || JS_SPACES.split(current).length > 5 || !HAS_LETTER.matcher(current).find()) return "";
        String lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getKey())) { lastUser = history.get(i).getValue(); break; }
        }
        if (lastUser == null || !normaliseReply(lastUser).equals(current)) return "";
        return "CURRENT TURN CLARITY GUIDANCE (application-owned): The user has repeated the same short follow-up after the last explanation. The earlier explanation has not resolved their need. Keep the existing subject and named options. Re-explain in everyday words: one direct definition, two or three useful checks with why they matter, and one simple illustrative example. Do not reproduce the prior checklist, table or intake question. Avoid trade jargon and unsupported provider claims. If the precise concern is still unclear, ask one focused clarification only after the useful explanation. Prefer a few short paragraphs to another multi-section report.";
    }

    // ------------------------------------------------------------------
    // resolveThinkingConfig
    // ------------------------------------------------------------------

    public static final class ThinkingResolution {
        public final ObjectNode thinkingConfig; // null when omitted (thinkingBudget 0 => INVALID_ARGUMENT upstream)
        public final String appliedThinkingLevel;
        public final int numericBudget;

        ThinkingResolution(ObjectNode thinkingConfig, String appliedThinkingLevel, int numericBudget) {
            this.thinkingConfig = thinkingConfig;
            this.appliedThinkingLevel = appliedThinkingLevel;
            this.numericBudget = numericBudget;
        }
    }

    private record ModelThinkingInfo(String mechanism, List<String> supportedLevels) {
    }

    /** data/models.ts: thinkingMechanism and supportedThinkingLevels. */
    private static final Map<String, ModelThinkingInfo> MODEL_REGISTRY = Map.ofEntries(
        Map.entry("gemini-3.7-flash", new ModelThinkingInfo("level", List.of("low", "medium", "high"))),
        Map.entry("gemini-3.8-flash", new ModelThinkingInfo("level", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-3.6-flash", new ModelThinkingInfo("level", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-3.5-flash", new ModelThinkingInfo("level", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-3.5-flash-lite", new ModelThinkingInfo("level", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-3.1-flash-lite", new ModelThinkingInfo("level", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-2.5-flash", new ModelThinkingInfo("budget", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-2.5-flash-lite", new ModelThinkingInfo("budget", List.of("minimal", "low", "medium", "high"))),
        Map.entry("gemini-2.0-flash", new ModelThinkingInfo("none", List.of())),
        Map.entry("gemini-2.0-flash-lite", new ModelThinkingInfo("none", List.of()))
    );

    private static final String[] COMPARISON_KEYWORDS = {
        "compare", "pathway", "trade-off", "plan", "roadmap", "vs", "architect", "recommend",
        "matrix", "trade off", "pros and cons", "which is better", "difference between",
    };

    private static final String[] GUIDANCE_KEYWORDS = {
        "how to", "how do", "how can", "help me", "guide me", "explain", "should i", "career",
        "certif", "skill", "course", "study", "learn", "job", "role", "salary", "interview",
        "prepare", "resume", "cv", "portfolio", "next step", "start", "begin", "advice",
        "suggest", "want to become", "want to be", "want to get into", "looking for",
        "looking to", "interested in", "thinking about", "switching to", "switch to",
        "transition", "developer", "engineer", "programmer", "analyst", "data science",
        "machine learning", "software", "tech", "coding", "junior", "senior", "entry level",
        "i am", "i'm", "my goal", "my career",
    };

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    public ThinkingResolution resolveThinkingConfig(String modelId, String thinkingLevel, String userPrompt) {
        String model = modelId == null ? "gemini-3.5-flash-lite" : modelId;
        String level = thinkingLevel == null ? "adaptive" : thinkingLevel;
        String appliedLevel;

        if ("minimal".equals(level) || "low".equals(level) || "medium".equals(level) || "high".equals(level)) {
            appliedLevel = level;
        } else {
            String lower = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
            boolean isFlashLite = model.contains("flash-lite");
            if (containsAny(lower, COMPARISON_KEYWORDS)) {
                appliedLevel = isFlashLite ? "low" : "medium";
            } else if (containsAny(lower, GUIDANCE_KEYWORDS)) {
                appliedLevel = isFlashLite ? "low" : "medium";
            } else if (lower.startsWith("what is ") || lower.startsWith("hi ") || lower.equals("hi")
                || lower.startsWith("hello") || lower.startsWith("format") || lower.startsWith("thank")
                || lower.startsWith("ok") || lower.startsWith("great") || lower.startsWith("got it")
                || (lower.length() < 20 && !lower.contains("?"))) {
                appliedLevel = "minimal";
            } else {
                appliedLevel = isFlashLite ? "minimal" : "low";
            }
        }

        ModelThinkingInfo modelInfo = MODEL_REGISTRY.get(model);
        if (modelInfo != null && !modelInfo.supportedLevels().contains(appliedLevel)) {
            appliedLevel = modelInfo.supportedLevels().isEmpty() ? "low" : modelInfo.supportedLevels().get(0);
        }

        int numericBudget = switch (appliedLevel) {
            case "minimal" -> 0;
            case "low" -> 128;
            case "medium" -> 512;
            default -> model.contains("flash-lite") ? 128 : 1024;
        };

        String mechanism = modelInfo != null ? modelInfo.mechanism() : "budget";
        ObjectNode thinkingConfig;
        if (numericBudget == 0) {
            thinkingConfig = null;
        } else if ("level".equals(mechanism)) {
            thinkingConfig = mapper.createObjectNode().put("thinkingLevel", appliedLevel);
        } else {
            thinkingConfig = mapper.createObjectNode().put("thinkingBudget", numericBudget);
        }
        return new ThinkingResolution(thinkingConfig, appliedLevel, numericBudget);
    }

    // ------------------------------------------------------------------
    // sanitizeSchemaForGemini
    // ------------------------------------------------------------------

    /**
     * Gemini-compatible Schema via a strict whitelist: type, description, nullable, format, enum,
     * properties, required, items, anyOf. Enum keeps only non-empty strings; "" marks nullable.
     */
    public JsonNode sanitizeSchemaForGemini(JsonNode node) {
        if (node == null || node.isNull() || !node.isContainerNode()) return node;
        if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            for (JsonNode n : node) arr.add(sanitizeSchemaForGemini(n));
            return arr;
        }
        ObjectNode out = mapper.createObjectNode();
        if (truthy(node.get("type"))) out.set("type", node.get("type"));
        if (truthy(node.get("description"))) out.set("description", node.get("description"));
        if (truthy(node.get("format"))) out.set("format", node.get("format"));
        if (node.has("nullable") && node.get("nullable").isBoolean()) out.set("nullable", node.get("nullable"));

        String type = node.path("type").isTextual() ? node.path("type").asText() : "";
        if (node.path("enum").isArray() && !"integer".equals(type) && !"number".equals(type)) {
            boolean hadEmptyString = false;
            ArrayNode filtered = mapper.createArrayNode();
            for (JsonNode e : node.get("enum")) {
                if (e.isTextual() && e.asText().isEmpty()) { hadEmptyString = true; continue; }
                if (e.isTextual()) filtered.add(e.asText());
            }
            if (filtered.size() > 0) out.set("enum", filtered);
            if (hadEmptyString) out.put("nullable", true);
        }

        if (node.path("properties").isObject()) {
            ObjectNode props = mapper.createObjectNode();
            node.get("properties").fields().forEachRemaining(e -> props.set(e.getKey(), sanitizeSchemaForGemini(e.getValue())));
            out.set("properties", props);
        }
        if (node.path("required").isArray()) out.set("required", node.get("required"));
        if (truthy(node.get("items"))) out.set("items", sanitizeSchemaForGemini(node.get("items")));
        if (node.path("anyOf").isArray()) {
            ArrayNode anyOf = mapper.createArrayNode();
            for (JsonNode n : node.get("anyOf")) anyOf.add(sanitizeSchemaForGemini(n));
            out.set("anyOf", anyOf);
        }
        return out;
    }

    private JsonNode loadSanitizedSchema() {
        try {
            ClassPathResource res = new ClassPathResource(RESPONSE_SCHEMA_CLASSPATH);
            if (!res.exists()) return null;
            byte[] bytes;
            try (InputStream is = res.getInputStream()) { bytes = is.readAllBytes(); }
            schemaHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return sanitizeSchemaForGemini(mapper.readTree(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readResource(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            if (!res.exists()) return "";
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** getGeminiResponseSchema(): the sanitized v1.3 response schema. */
    public JsonNode getSanitizedResponseSchema() {
        return sanitizedSchema.updateAndGet(cur -> cur != null ? cur : loadSanitizedSchema());
    }
}
