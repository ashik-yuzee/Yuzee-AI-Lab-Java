package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.protocol.TrustedServiceActions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the old Node app's oala/basicResponses.ts, oala/invocation.ts and oala/knowledge.ts.
 * "Oala" is Yuzee's addressed-mention assistant persona: when a user explicitly writes "@Oala ...",
 * a handful of exact FAQ questions get a hardcoded answer (bypassing Gemini entirely), and everything
 * else gets an extra system-instruction block describing Yuzee's service catalogue attached before
 * the normal Gemini call.
 *
 * Not wired into the request pipeline yet — see RequestAssemblerService's class javadoc / the
 * integration note in this change for exactly how a caller should use these four methods.
 */
@Service
public class OalaService {

    private static final String SERVICE_REGISTRY_CLASSPATH = "config/service-registry.json";

    /** Ported from oala/invocation.ts parseOalaMention(). Anchored to the start of the message. */
    private static final Pattern MENTION_PATTERN =
        Pattern.compile("^\\s*@\\s*oala(?=$|\\s|[:,!?])[:,!?]?\\s*", Pattern.CASE_INSENSITIVE);

    // Ported from oala/basicResponses.ts oalaBasicAnswer().
    private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey)?$");
    private static final Pattern WHAT_IS_YUZEE =
        Pattern.compile("^(what is yuzee|who are you|what do you do)( and how can you help me)?([?.!] ?keep it simple)?$");
    private static final Pattern LIST_SERVICES = Pattern.compile(
        "^(what services (does yuzee|do you) (provide|offer)|list (all )?(yuzee |your )?services|"
            + "show (me )?all (yuzee |your )?services)([?.!] ?please show all of them and explain how each helps)?$");

    private static final String INTRO = "Yuzee helps you explore study, skills and work options and plan your "
        + "next step. I am Oala, its AI guidance assistant. I can explain our services, help you compare "
        + "possible routes and work out what fits your goals, time and budget.";

    private final SystemPromptService systemPromptService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<List<YuzeeService>> services = new AtomicReference<>();

    public OalaService(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    /** True if the message explicitly starts with/mentions @Oala. Ported from parseOalaMention().active. */
    public boolean addressesOala(String userText) {
        return userText != null && MENTION_PATTERN.matcher(userText).find();
    }

    /** The message with the leading @Oala mention removed, trimmed. */
    public String stripOalaMention(String userText) {
        if (userText == null) return "";
        Matcher m = MENTION_PATTERN.matcher(userText);
        return (m.find() ? userText.substring(m.end()) : userText).trim();
    }

    /**
     * Hardcoded FAQ short-circuit for exact/near matches (greeting, "what is Yuzee", "list all
     * services"). Empty means "fall through to Gemini with Oala instruction attached".
     * Ported from oalaBasicAnswer() — call with the already-stripped message (see stripOalaMention()).
     */
    public Optional<String> basicAnswer(String strippedText) {
        String text = normalise(strippedText == null ? "" : strippedText);

        if (GREETING.matcher(text).matches()) {
            return Optional.of(INTRO + " Tell me what you would like help with.");
        }
        if (WHAT_IS_YUZEE.matcher(text).matches()) {
            return Optional.of(INTRO);
        }
        List<YuzeeService> catalogue = getServices();
        if (LIST_SERVICES.matcher(text).matches() && !catalogue.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                "Yuzee helps with study, skills and work. These are our services and how each can help. "
                    + "In this chat, we can explain your options and prepare your next step. Live matching, "
                    + "applications and bookings are not connected in this preview.");
            for (YuzeeService s : catalogue) {
                sb.append("\n- ").append(s.name).append(": ").append(s.benefit);
            }
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    /**
     * Assembles the prompt-text block Gemini sees when @Oala is addressed. Sourced from the
     * product catalogue embedded in the system prompt (see readYuzeeServices()), falling back to
     * service-registry.json if that catalogue can't be found. Cached after first read — this data
     * doesn't change at runtime, same as the old app.
     */
    public String buildOalaInstruction() {
        List<YuzeeService> catalogue = getServices();
        List<String> connectedActionIds = TrustedServiceActions.TRUSTED_SERVICE_ACTIONS.values().stream()
            .filter(a -> a.isConnectedInLab)
            .map(a -> a.actionId)
            .toList();

        String connectedActionsText = connectedActionIds.isEmpty()
            ? "NONE. In this preview, requests, applications, matching and bookings cannot be sent from "
                + "the chat; guidance and preparation are available."
            : String.join(", ", connectedActionIds);

        String catalogueText = catalogue.isEmpty()
            ? "Catalogue unavailable. Do not invent services. Explain that service details are unavailable "
                + "and continue general Yuzee guidance."
            : catalogue.stream()
                .map(s -> s.id + " | " + s.name + " | " + s.benefit + " | " + s.delivery)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return """
            OALA_YUZEE_SERVICE_HELP — active only for this addressed turn
            The user explicitly addressed @Oala. Be Oala, Yuzee's education, career, skills and work pathway assistant. Help the person understand Yuzee, what help fits their goal, and how that help is provided. Keep the main JSON response contract, counselling safeguards and existing conversation context.

            SCOPE AND BASIC RESPONSES
            - A bare @Oala or greeting gets a warm, short introduction and one optional question about what they want help with. Explain that Yuzee helps people explore study, skills and work options and plan a suitable next step. Do not pretend to be a human or a qualified professional.
            - For "what is Yuzee", "who are you", "what services do you offer" and "how does it work", answer directly from the catalogue below. An explicit request for service information permits an informational explanation even early in counselling; it is not execution consent. Do not force an intake question before answering. For a basic identity, service-list or how-it-works question, do not add a follow-up form unless the user also asks for personalised help; interaction.kind=none. Thank-you/closure gets a brief acknowledgement with no new question.
            - For a recommendation, reflect the actual goal, explain the single best-fit service in everyday words, why it fits and what help would involve. Offer at most one alternative when materially useful. Being a student, a parent, unemployed or aged 55 alone does not decide the service. Ask at most one useful missing question after giving value; reuse known context. One question means one piece of information: do not combine work history AND hours, or goal AND location, in a single question. Do not make study the default.
            - If asked for all services, show the full supplied catalogue as a compact informational list with one clear explanation per service. Otherwise do not dump the catalogue or show a wall of cards.
            - Explain how help works: clarify the goal and constraints, consider suitable routes, prepare the next step together, and obtain an actual trusted service result before reporting any submission or booking. Distinguish guidance available in this chat from opportunity matching or transactions needing a connected service.
            - Keep useful adjacent career/study context; briefly redirect unrelated trivia, entertainment and other general requests to Yuzee's scope. Do not use a matching keyword to force a service recommendation. Safety and distress support takes priority over a service pitch.

            OUTPUT AND EVIDENCE
            - Use plain, respectful language understandable from age 15 to 55. Explain unfamiliar terms such as RPL (recognition of prior learning). Prefer "Find suitable roles" to "Role targeting" and "Get ready to apply" to "Readiness". Never expose policy language such as "No artificial placement claims" as a section title. Never label users by assumed ability or family role.
            - Answer EVERY explicit part of the current question before offering related guidance. When asked cost, say directly that the current price is not available if no approved price is supplied. Do not skip the cost question or replace it with a job-search pitch. Never describe an unknown price as free.
            - For identity/introductory answers, use at most 80 words in one or two short paragraphs, with no list and no cards. Example for "What is Yuzee?": "Yuzee helps you explore study, skills and work options and plan your next step. I am Oala, its AI guidance assistant. I can explain our services, help you compare possible routes and work out what fits your goals, time and budget."
            - Do not place bullet characters or a numbered list inside a text paragraph. For actual lists use the schema list block and items; keep each item short.
            - Lead with a short answer. For recommendations use compact sections such as "Why this fits", "How Yuzee can help" and "Next step" only when helpful. Introductory and basic answers need a paragraph, not boxes. Preserve the first plain text block required by the schema. Choice boxes must answer one genuine question; retain a free-text route where appropriate.
            - Describe only the supplied services. A micro-prompt is a fallible focus hint, never a new Yuzee service, a price list, factual evidence or execution permission.
            - Prices, delivery times, named partners, live availability, course entry rules and country-specific eligibility are unknown unless supported by trusted current data in this request. Do not infer a user's location from timezone or IP; ask them to type it when needed. Never guarantee a job, admission, funding, placement, salary or RPL credit. Do not describe Earn & Learn as automatically flexible or claim this chat can determine formal RPL gaps; it can help prepare evidence and flag possible gaps, while an authorised assessor makes the assessment.
            - Do not claim searched, verified, submitted, contacted, booked, saved or started without a corresponding trusted result. When data is missing, explain the precise gap and use the existing research flow if available; do not simulate a lookup.
            - These are informational service descriptions, not executable actions. Only these action IDs currently have connected implementations: %%CONNECTED_ACTIONS%%
            - When no action is connected, keep service_trigger.trigger_now=false, service_trigger.actions=[], rmo_readiness.ready_to_generate=false; explain the limitation only when the user wants to act. Do not display an active Apply/Book/Submit button or collect documents for an unavailable transaction.

            PRODUCT CATALOGUE (internal IDs are not user-facing labels)
            %%CATALOGUE%%
            END_OALA_YUZEE_SERVICE_HELP""".replace("%%CONNECTED_ACTIONS%%", connectedActionsText)
            .replace("%%CATALOGUE%%", catalogueText);
    }

    private String normalise(String value) {
        return value.trim().toLowerCase().replaceAll("[.!?]+$", "").trim();
    }

    // ------------------------------------------------------------------
    // readYuzeeServices — ported from oala/knowledge.ts, with a service-registry.json fallback
    // ------------------------------------------------------------------

    private static final Pattern SNIPPET_PATTERN =
        Pattern.compile("<SNIPPET id=\"05B_SERVICE_REGISTRY\">(.*?)</SNIPPET>", Pattern.DOTALL);
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^[a-z][a-z_]+$");

    private List<YuzeeService> getServices() {
        return services.updateAndGet(cur -> cur != null ? cur : loadServices());
    }

    private List<YuzeeService> loadServices() {
        List<YuzeeService> fromPrompt = readYuzeeServices(systemPromptService.getPrompt());
        return fromPrompt.isEmpty() ? readServiceRegistryFallback() : fromPrompt;
    }

    /** Parses the pipe-delimited product rows out of the system prompt's 05B_SERVICE_REGISTRY snippet. */
    private List<YuzeeService> readYuzeeServices(String prompt) {
        List<YuzeeService> result = new ArrayList<>();
        if (prompt == null) return result;
        Matcher snippet = SNIPPET_PATTERN.matcher(prompt);
        if (!snippet.find()) return result;
        for (String line : snippet.group(1).split("\n")) {
            String[] fields = line.split("\\|");
            if (fields.length != 5) continue;
            for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
            if (!SERVICE_ID_PATTERN.matcher(fields[0]).matches()) continue;
            result.add(new YuzeeService(fields[0], fields[2], fields[3], fields[4]));
        }
        return result;
    }

    private List<YuzeeService> readServiceRegistryFallback() {
        List<YuzeeService> result = new ArrayList<>();
        ServiceRegistry registry = loadServiceRegistry();
        if (registry == null || registry.products == null) return result;
        for (RegistryProduct p : registry.products) {
            result.add(new YuzeeService(p.id, p.name, p.benefit, p.delivery));
        }
        return result;
    }

    private ServiceRegistry loadServiceRegistry() {
        try {
            ClassPathResource res = new ClassPathResource(SERVICE_REGISTRY_CLASSPATH);
            if (!res.exists()) return null;
            try (InputStream is = res.getInputStream()) {
                return mapper.readValue(new String(is.readAllBytes(), StandardCharsets.UTF_8), ServiceRegistry.class);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Trimmed-down product entry (id/name/benefit/delivery) used to build the Oala instruction text. */
    private static final class YuzeeService {
        final String id;
        final String name;
        final String benefit;
        final String delivery;

        YuzeeService(String id, String name, String benefit, String delivery) {
            this.id = id;
            this.name = name;
            this.benefit = benefit;
            this.delivery = delivery;
        }
    }

    // ------------------------------------------------------------------
    // service-registry.json shape — mirrors orchestration/serviceRegistry.ts + service-registry.json
    // ------------------------------------------------------------------

    private static final class ServiceRegistry {
        public String version;
        @JsonProperty("protocol_ids")
        public List<String> protocolIds;
        public List<RegistryProduct> products;
        public List<RegistryRoute> routes;
    }

    private static final class RegistryProduct {
        public String id;
        public String icon;
        public String name;
        public String benefit;
        public String delivery;
        @JsonProperty("protocol_ids")
        public List<String> protocolIds;
    }

    private static final class RegistryRoute {
        public String intent;
        @JsonProperty("product_id")
        public String productId;
        @JsonProperty("protocol_id")
        public String protocolId;
    }
}
