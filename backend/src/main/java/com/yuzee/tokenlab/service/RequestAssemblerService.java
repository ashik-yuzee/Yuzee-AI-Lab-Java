package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Java port of YuzeeRequestAssembler.ts. Top-level orchestrator: runs the bypass classifier,
 * then (when not bypassed) assembles memory, builds multi-turn contents, resolves the thinking
 * config, and attaches the sanitized structured-output schema.
 */
@Service
public class RequestAssemblerService {

    /** dynamicBudgetTokens default from TokenBudgetMemoryManager.assembleMemory (TS). */
    private static final int DEFAULT_TOKEN_BUDGET = 2000;
    /** resolveOutputBudget() in the old app always returns 65536 regardless of mode/model. */
    private static final int MAX_OUTPUT_TOKENS = 65536;
    private static final String RESPONSE_SCHEMA_CLASSPATH = "prompts/response-schema-v1.3.json";

    public enum BypassCategory { GREETING, FAREWELL, IDLE, RUBBISH, CAREER }

    private final ConversationMemoryService memoryService;
    private final MultiTurnRequestBuilder contentBuilder;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> sanitizedSchema = new AtomicReference<>();

    public RequestAssemblerService(ConversationMemoryService memoryService, MultiTurnRequestBuilder contentBuilder) {
        this.memoryService = memoryService;
        this.contentBuilder = contentBuilder;
    }

    @PostConstruct
    void init() {
        sanitizedSchema.set(loadSanitizedSchema());
    }

    // ------------------------------------------------------------------
    // Top-level orchestration
    // ------------------------------------------------------------------

    public AssembledRequest assembleRequest(Conversation conversation, Object currentUserInput,
                                             String baseSystemPrompt, String modelId) {
        AssembledRequest result = new AssembledRequest();

        boolean hasConversation = conversation != null && conversation.getMessages() != null
            && !conversation.getMessages().isEmpty();
        boolean hasActiveQuestion = hasActiveQuestion(conversation);

        String rawText = currentUserInput instanceof String s ? s : null;
        // A structured (non-string) currentUserInput is a real interaction (option click, form
        // submit, etc) — the bypass classifier only ever ran against plain text in the old app,
        // so structured input always routes straight to CAREER here.
        BypassCategory category = rawText != null
            ? classifyUserMessage(rawText, hasConversation, hasActiveQuestion)
            : BypassCategory.CAREER;

        if (category != BypassCategory.CAREER) {
            result.bypassResponseText = bypassCopy(category);
            return result;
        }

        String careerCapsuleText = formatCareerContext(conversation != null ? conversation.getCareerContext() : null);
        String summaryText = conversation != null ? conversation.getSummaryText() : null;
        MemoryStrategy strategy = resolveStrategy(conversation != null ? conversation.getStrategy() : null);

        MemoryResult memoryResult = memoryService.assembleMemory(conversation, DEFAULT_TOKEN_BUDGET, strategy, rawText);
        ArrayNode contents = contentBuilder.buildMultiTurnContents(
            memoryResult.retainedTurns, careerCapsuleText, summaryText, currentUserInput);

        ThinkingResolution thinking = resolveThinkingConfig(modelId, "adaptive", rawText != null ? rawText : "");

        ObjectNode extras = mapper.createObjectNode();
        if (thinking.thinkingConfig != null) extras.set("thinkingConfig", thinking.thinkingConfig);
        JsonNode schema = getSanitizedResponseSchema();
        if (schema != null) extras.set("responseSchema", schema);
        extras.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        extras.put("responseMimeType", "application/json");

        String systemInstruction = baseSystemPrompt == null ? "" : baseSystemPrompt;
        String guidance = shortReplyGuidance(rawText, conversation);
        if (!guidance.isEmpty()) {
            systemInstruction = systemInstruction + "\n\n" + guidance;
        }

        result.systemInstruction = systemInstruction;
        result.contents = contents;
        result.generationConfigExtras = extras;
        result.compactionMetrics = memoryResult.metrics;
        return result;
    }

    private MemoryStrategy resolveStrategy(String raw) {
        if (raw == null || raw.isBlank()) return MemoryStrategy.BUDGET_EVICTION;
        try {
            return MemoryStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryStrategy.BUDGET_EVICTION;
        }
    }

    private boolean hasActiveQuestion(Conversation conversation) {
        if (conversation == null || conversation.getMessages() == null || conversation.getMessages().isEmpty()) return false;
        ChatMessage last = conversation.getMessages().get(conversation.getMessages().size() - 1);
        if (!"assistant".equals(last.getRole()) || last.getParsedResponse() == null) return false;
        Object interaction = last.getParsedResponse().get("interaction");
        if (!(interaction instanceof Map<?, ?> map)) return false;
        Object question = map.get("question");
        return question instanceof String q && !q.trim().isEmpty();
    }

    private String formatCareerContext(Map<String, Object> capsule) {
        if (capsule == null || capsule.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : capsule.entrySet()) {
            if (e.getValue() == null) continue;
            String v = String.valueOf(e.getValue()).trim();
            if (v.isEmpty()) continue;
            lines.add("- [" + e.getKey() + "]: " + v);
        }
        if (lines.isEmpty()) return "";
        return "YUZEE_STRUCTURED_MEMORY_CAPSULE:\n" + String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // classifyUserMessage — ported from YuzeeRequestAssembler.classifyUserMessage
    // ------------------------------------------------------------------

    private static final Pattern[] GREETING_PATTERNS = {
        Pattern.compile("^(hi|hey|hello|howdy|hiya|sup|yo)(\\s+(there|oala|yuzee|bot|ai|friend))?$"),
        Pattern.compile("^good\\s+(morning|afternoon|evening|day)(\\s+(oala|yuzee))?$"),
        Pattern.compile("^how are you(\\s+(doing|going|today))?$"),
        Pattern.compile("^(are you there|you there|you working|is this working|test|testing|hello\\?)$"),
        Pattern.compile("^what('s| is) up(\\s+with you)?$"),
    };

    private static final Pattern[] FAREWELL_PATTERNS = {
        Pattern.compile("^(bye|goodbye|see you|see ya|cya|ttyl|later|take care)(\\s+(later|soon|then|now))?$"),
        Pattern.compile("^(thanks|thank you|thx|ty|cheers|great|awesome|perfect|got it|ok|okay|cool|nice|sounds good)(\\s+(for (that|everything|your help|the help)))?$"),
        Pattern.compile("^(that('s| is) (great|helpful|perfect|all|enough)|no (more )?questions?|i('m| am) (done|good|all set|all good))$"),
    };

    private static final Pattern[] IDLE_PATTERNS = {
        Pattern.compile("^(lo+l+o*|lmao|lmfao|rofl|ha(ha)+|he(he)+|hah|lel|lulz|xd|😂|🤣|omg|omfg|wtf|smh|fml)$"),
        Pattern.compile("^(meh|whatever|whatevs|idc|i don'?t care|boring|ugh|bleh|mmmh?|hmm+)$"),
        Pattern.compile("^i('m| am) bored(\\s+(rn|right now|today|tbh))?$"),
        Pattern.compile("^you('re| are) (funny|hilarious|great|amazing|cool|nice|smart|the best)$"),
        Pattern.compile("^(nice one|good one|haha nice|that('s| is) funny|made me (laugh|smile))$"),
        Pattern.compile("^(what('s| is) the (time|weather|date|temp(erature)?)|what day is it)$"),
        Pattern.compile("^(tell me a joke|say something funny|make me laugh|entertain me)$"),
        Pattern.compile("^(sing( me a song)?|dance|do a trick|flip a coin|roll (a )?d(ice|6|20))$"),
        Pattern.compile("^(what('s| is) (your (name|age|favourite|favorite|hobby|hobbies))|do you (like|love|hate|eat|sleep|dream))$"),
        Pattern.compile("^(are you (a robot|an ai|sentient|alive|human|real)|who (made|built|created) you)$"),
        Pattern.compile("^(how old are you|where are you from|what are you|who are you)$"),
        Pattern.compile("^(nothing|never ?mind|no ?thing|just (browsing|looking|chilling|vibing|kidding|joking)|jk|nm|nvm|nevermind)$"),
    };

    /**
     * Classifies a user message into a bypass category, or CAREER for normal routing to Gemini.
     * Short replies and typed answers mid-conversation always fall through to CAREER — this
     * bypass classifier is not the embedding router and must not gate understanding.
     */
    public BypassCategory classifyUserMessage(String text, boolean hasConversation, boolean hasActiveQuestion) {
        String textTrim = text == null ? "" : text.trim();
        if (!textTrim.isEmpty() && (hasConversation || hasActiveQuestion)) return BypassCategory.CAREER;

        String t = textTrim.toLowerCase().replaceAll("[!?.,']+$", "").trim();

        for (Pattern p : GREETING_PATTERNS) if (p.matcher(t).matches()) return BypassCategory.GREETING;
        for (Pattern p : FAREWELL_PATTERNS) if (p.matcher(t).matches()) return BypassCategory.FAREWELL;
        for (Pattern p : IDLE_PATTERNS) if (p.matcher(t).matches()) return BypassCategory.IDLE;

        if (isRubbish(t)) return BypassCategory.RUBBISH;
        return BypassCategory.CAREER;
    }

    private static final Pattern LETTER_OR_NUMBER = Pattern.compile("[\\p{L}\\p{N}]");

    private boolean isRubbish(String t) {
        return t.isEmpty() || !LETTER_OR_NUMBER.matcher(t).find();
    }

    /** Local social responses only. Meaningful messages and follow-ups go to Gemini. Ported from ux/bypassCopy.ts. */
    private String bypassCopy(BypassCategory kind) {
        return switch (kind) {
            case GREETING -> "Hi, I'm Oala. I can help you explore courses, skills and career options. What would you like help with?";
            case FAREWELL -> "You're welcome. You can return whenever you want to explore your next step.";
            case IDLE -> "I can help with courses, skills, career choices and Yuzee services. What would you like to explore?";
            case RUBBISH -> "I couldn't tell what you meant from that message. You can use a few words, such as ‘course quality’, ‘study costs’ or ‘finding a job’. What would you like help with?";
            case CAREER -> null;
        };
    }

    // ------------------------------------------------------------------
    // shortReplyGuidance — ported from ux/shortReplyGuidance.ts
    // ------------------------------------------------------------------

    private static final Pattern HAS_LETTER = Pattern.compile("[\\p{L}]");

    /** A repeated short follow-up signals an unresolved explanation, not a new intake. */
    private String shortReplyGuidance(String text, Conversation conversation) {
        if (text == null) return "";
        String current = normalise(text);
        if (current.isEmpty() || current.split("\\s+").length > 5 || !HAS_LETTER.matcher(current).find()) return "";
        if (conversation == null || conversation.getMessages() == null) return "";

        ChatMessage lastUser = null;
        List<ChatMessage> msgs = conversation.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).getRole())) {
                lastUser = msgs.get(i);
                break;
            }
        }
        if (lastUser == null) return "";
        Object lastContent = lastUser.getContent();
        String lastText = lastContent instanceof String s ? s : String.valueOf(lastContent);
        if (!normalise(lastText).equals(current)) return "";

        return "CURRENT TURN CLARITY GUIDANCE (application-owned): The user has repeated the same short "
            + "follow-up after the last explanation. The earlier explanation has not resolved their need. "
            + "Keep the existing subject and named options. Re-explain in everyday words: one direct "
            + "definition, two or three useful checks with why they matter, and one simple illustrative "
            + "example. Do not reproduce the prior checklist, table or intake question. Avoid trade jargon "
            + "and unsupported provider claims. If the precise concern is still unclear, ask one focused "
            + "clarification only after the useful explanation. Prefer a few short paragraphs to another "
            + "multi-section report.";
    }

    private String normalise(String value) {
        return value.trim().toLowerCase().replaceAll("[.!?]+$", "").trim();
    }

    // ------------------------------------------------------------------
    // resolveThinkingConfig — ported from YuzeeRequestAssembler.resolveThinkingConfig
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

    /** Ported from data/models.ts — only the fields resolveThinkingConfig needs. */
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
        String model = modelId == null || modelId.isBlank() ? "gemini-3.5-flash-lite" : modelId;
        boolean isFlashLite = model.contains("flash-lite");
        String appliedLevel;

        if ("minimal".equals(thinkingLevel) || "low".equals(thinkingLevel)
            || "medium".equals(thinkingLevel) || "high".equals(thinkingLevel)) {
            appliedLevel = thinkingLevel;
        } else {
            // Adaptive deterministic local classifier (zero latency, no extra provider request).
            String lower = userPrompt == null ? "" : userPrompt.toLowerCase();
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

        // Model restrictions (e.g. Gemini 3.7 Flash doesn't support 'minimal' thinking level).
        ModelThinkingInfo modelInfo = MODEL_REGISTRY.get(model);
        if (modelInfo != null && !modelInfo.supportedLevels().isEmpty() && !modelInfo.supportedLevels().contains(appliedLevel)) {
            appliedLevel = modelInfo.supportedLevels().get(0);
        }

        int numericBudget = switch (appliedLevel) {
            case "minimal" -> 0;
            case "low" -> 128;
            case "medium" -> 512;
            case "high" -> isFlashLite ? 128 : 1024;
            default -> 512;
        };

        String mechanism = modelInfo != null ? modelInfo.mechanism() : "budget";
        ObjectNode thinkingConfig;
        if (numericBudget == 0) {
            thinkingConfig = null; // omit entirely — sending thinkingBudget:0 causes INVALID_ARGUMENT
        } else if ("level".equals(mechanism)) {
            thinkingConfig = mapper.createObjectNode();
            thinkingConfig.put("thinkingLevel", appliedLevel);
        } else {
            thinkingConfig = mapper.createObjectNode();
            thinkingConfig.put("thinkingBudget", numericBudget);
        }

        return new ThinkingResolution(thinkingConfig, appliedLevel, numericBudget);
    }

    // ------------------------------------------------------------------
    // sanitizeSchemaForGemini — ported from YuzeeRequestAssembler.sanitizeSchemaForGemini
    // ------------------------------------------------------------------

    /**
     * Converts a JSON Schema node to a Gemini-compatible Schema using a strict whitelist.
     * Gemini's responseSchema is a proto-defined subset of JSON Schema.
     * Whitelist: type, description, nullable, format, enum, properties, required, items, anyOf.
     * Everything else (additionalProperties, minimum, maximum, maxItems, minItems, title, etc.) is stripped.
     * Enum: only non-empty string values are kept; integer/number type enums are dropped entirely.
     */
    public JsonNode sanitizeSchemaForGemini(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            for (JsonNode n : node) arr.add(sanitizeSchemaForGemini(n));
            return arr;
        }
        if (!node.isObject()) return node;

        ObjectNode out = mapper.createObjectNode();
        if (node.has("type")) out.set("type", node.get("type"));
        if (node.has("description")) out.set("description", node.get("description"));
        if (node.has("format")) out.set("format", node.get("format"));
        if (node.has("nullable") && node.get("nullable").isBoolean()) out.set("nullable", node.get("nullable"));

        // Only string enums with non-empty values; drop enums on integer/number types entirely.
        // When "" was a valid option, mark nullable so Gemini knows the field can be absent/empty.
        if (node.has("enum") && node.get("enum").isArray()) {
            String type = node.path("type").asText("");
            if (!"integer".equals(type) && !"number".equals(type)) {
                boolean hadEmptyString = false;
                ArrayNode filtered = mapper.createArrayNode();
                for (JsonNode e : node.get("enum")) {
                    if (e.isTextual() && e.asText().isEmpty()) {
                        hadEmptyString = true;
                        continue;
                    }
                    if (e.isTextual()) filtered.add(e.asText());
                }
                if (filtered.size() > 0) out.set("enum", filtered);
                if (hadEmptyString) out.put("nullable", true);
            }
        }

        if (node.has("properties") && node.get("properties").isObject()) {
            ObjectNode props = mapper.createObjectNode();
            node.get("properties").fields().forEachRemaining(e -> props.set(e.getKey(), sanitizeSchemaForGemini(e.getValue())));
            out.set("properties", props);
        }

        if (node.has("required") && node.get("required").isArray()) out.set("required", node.get("required"));
        if (node.has("items")) out.set("items", sanitizeSchemaForGemini(node.get("items")));
        if (node.has("anyOf") && node.get("anyOf").isArray()) {
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
            try (InputStream is = res.getInputStream()) {
                return sanitizeSchemaForGemini(mapper.readTree(is));
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** The sanitized v1.3 response schema, loaded once at startup and cached. */
    public JsonNode getSanitizedResponseSchema() {
        return sanitizedSchema.updateAndGet(cur -> cur != null ? cur : loadSanitizedSchema());
    }
}
