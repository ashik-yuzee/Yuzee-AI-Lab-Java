package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.yuzee.tokenlab.protocol.TrustedServiceActions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex;
import static com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim;

/** Port of src/oala/invocation.ts, basicResponses.ts and knowledge.ts. */
@Service
public class OalaService {

    public record OalaMention(boolean active, String message) {}

    @JsonPropertyOrder({"id", "name", "benefit", "delivery"})
    public record YuzeeService(String id, String name, String benefit, String delivery) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OalaBasicAnswer(String text, List<YuzeeService> services) {}

    // invocation.ts: explicit, per-message addressing. Quoted mentions, email addresses and old turns do not activate Oala.
    private static final Pattern MENTION = jsRegex("^\\s*@\\s*oala(?=$|\\s|[:,!?])[:,!?]?\\s*", true);

    // basicResponses.ts
    private static final Pattern TRAILING_PUNCT = jsRegex("[.!?]+$", false);
    private static final Pattern GREETING = jsRegex("^(hi|hello|hey)?$", false);
    private static final Pattern WHAT_IS_YUZEE = jsRegex(
        "^(what is yuzee|who are you|what do you do)( and how can you help me)?([?.!] ?keep it simple)?$", false);
    private static final Pattern LIST_SERVICES = jsRegex(
        "^(what services (does yuzee|do you) (provide|offer)|list (all )?(yuzee |your )?services|"
            + "show (me )?all (yuzee |your )?services)([?.!] ?please show all of them and explain how each helps)?$", false);
    private static final String INTRO = "Yuzee helps you explore study, skills and work options and plan your next step. "
        + "I am Oala, its AI guidance assistant. I can explain our services, help you compare possible routes and work "
        + "out what fits your goals, time and budget.";

    // knowledge.ts
    private static final Pattern SNIPPET = jsRegex("<SNIPPET id=\"05B_SERVICE_REGISTRY\">([\\s\\S]*?)<\\/SNIPPET>", false);
    private static final Pattern SERVICE_ID = jsRegex("^[a-z][a-z_]+$", false);

    private final SystemPromptService systemPromptService;

    public OalaService(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    public static OalaMention parseOalaMention(String value) {
        String text = value == null ? "" : value;
        Matcher m = MENTION.matcher(text);
        boolean active = m.find();
        return new OalaMention(active, active ? jsTrim(text.substring(m.end())) : text);
    }

    /** Exact basic FAQs use product-owned copy. Personal requests and ambiguous questions stay with Gemini. */
    public OalaBasicAnswer oalaBasicAnswer(String input, List<YuzeeService> services) {
        OalaMention mention = parseOalaMention(input);
        if (!mention.active()) return null;
        String text = jsTrim(TRAILING_PUNCT.matcher(jsTrim(mention.message()).toLowerCase(Locale.ROOT)).replaceAll(""));
        if (GREETING.matcher(text).find()) return new OalaBasicAnswer(INTRO + " Tell me what you would like help with.", null);
        if (WHAT_IS_YUZEE.matcher(text).find()) return new OalaBasicAnswer(INTRO, null);
        if (LIST_SERVICES.matcher(text).find() && !services.isEmpty()) {
            return new OalaBasicAnswer("Yuzee helps with study, skills and work. These are our services and how each can help. "
                + "In this chat, we can explain your options and prepare your next step. Live matching, applications and "
                + "bookings are not connected in this preview.", services);
        }
        return null;
    }

    /** Read the existing product-owned catalogue instead of maintaining a second list of services. */
    public List<YuzeeService> readYuzeeServices(String prompt) {
        Matcher m = SNIPPET.matcher(prompt == null ? "" : prompt);
        String section = m.find() ? m.group(1) : "";
        List<YuzeeService> result = new ArrayList<>();
        for (String line : section.split("\n", -1)) {
            String[] fields = line.split("\\|", -1);
            for (int i = 0; i < fields.length; i++) fields[i] = jsTrim(fields[i]);
            if (fields.length != 5 || !SERVICE_ID.matcher(fields[0]).find()) continue;
            result.add(new YuzeeService(fields[0], fields[2], fields[3], fields[4]));
        }
        return result;
    }

    public String buildOalaInstruction(List<YuzeeService> services, List<String> connectedActionIds) {
        List<String> connected = connectedActionIds == null ? List.of() : connectedActionIds;
        return "OALA_YUZEE_SERVICE_HELP — active only for this addressed turn\n"
            + "The user explicitly addressed @Oala. Be Oala, Yuzee's education, career, skills and work pathway assistant. Help the person understand Yuzee, what help fits their goal, and how that help is provided. Keep the main JSON response contract, counselling safeguards and existing conversation context.\n"
            + "\n"
            + "SCOPE AND BASIC RESPONSES\n"
            + "- A bare @Oala or greeting gets a warm, short introduction and one optional question about what they want help with. Explain that Yuzee helps people explore study, skills and work options and plan a suitable next step. Do not pretend to be a human or a qualified professional.\n"
            + "- For \"what is Yuzee\", \"who are you\", \"what services do you offer\" and \"how does it work\", answer directly from the catalogue below. An explicit request for service information permits an informational explanation even early in counselling; it is not execution consent. Do not force an intake question before answering. For a basic identity, service-list or how-it-works question, do not add a follow-up form unless the user also asks for personalised help; interaction.kind=none. Thank-you/closure gets a brief acknowledgement with no new question.\n"
            + "- For a recommendation, reflect the actual goal, explain the single best-fit service in everyday words, why it fits and what help would involve. Offer at most one alternative when materially useful. Being a student, a parent, unemployed or aged 55 alone does not decide the service. Ask at most one useful missing question after giving value; reuse known context. One question means one piece of information: do not combine work history AND hours, or goal AND location, in a single question. Do not make study the default.\n"
            + "- If asked for all services, show the full supplied catalogue as a compact informational list with one clear explanation per service. Otherwise do not dump the catalogue or show a wall of cards.\n"
            + "- Explain how help works: clarify the goal and constraints, consider suitable routes, prepare the next step together, and obtain an actual trusted service result before reporting any submission or booking. Distinguish guidance available in this chat from opportunity matching or transactions needing a connected service.\n"
            + "- Keep useful adjacent career/study context; briefly redirect unrelated trivia, entertainment and other general requests to Yuzee's scope. Do not use a matching keyword to force a service recommendation. Safety and distress support takes priority over a service pitch.\n"
            + "\n"
            + "OUTPUT AND EVIDENCE\n"
            + "- Use plain, respectful language understandable from age 15 to 55. Explain unfamiliar terms such as RPL (recognition of prior learning). Prefer \"Find suitable roles\" to \"Role targeting\" and \"Get ready to apply\" to \"Readiness\". Never expose policy language such as \"No artificial placement claims\" as a section title. Never label users by assumed ability or family role.\n"
            + "- Answer EVERY explicit part of the current question before offering related guidance. When asked cost, say directly that the current price is not available if no approved price is supplied. Do not skip the cost question or replace it with a job-search pitch. Never describe an unknown price as free.\n"
            + "- For identity/introductory answers, use at most 80 words in one or two short paragraphs, with no list and no cards. Example for \"What is Yuzee?\": \"Yuzee helps you explore study, skills and work options and plan your next step. I am Oala, its AI guidance assistant. I can explain our services, help you compare possible routes and work out what fits your goals, time and budget.\"\n"
            + "- Do not place bullet characters or a numbered list inside a text paragraph. For actual lists use the schema list block and items; keep each item short.\n"
            + "- Lead with a short answer. For recommendations use compact sections such as \"Why this fits\", \"How Yuzee can help\" and \"Next step\" only when helpful. Introductory and basic answers need a paragraph, not boxes. Preserve the first plain text block required by the schema. Choice boxes must answer one genuine question; retain a free-text route where appropriate.\n"
            + "- Describe only the supplied services. A micro-prompt is a fallible focus hint, never a new Yuzee service, a price list, factual evidence or execution permission.\n"
            + "- Prices, delivery times, named partners, live availability, course entry rules and country-specific eligibility are unknown unless supported by trusted current data in this request. Do not infer a user's location from timezone or IP; ask them to type it when needed. Never guarantee a job, admission, funding, placement, salary or RPL credit. Do not describe Earn & Learn as automatically flexible or claim this chat can determine formal RPL gaps; it can help prepare evidence and flag possible gaps, while an authorised assessor makes the assessment.\n"
            + "- Do not claim searched, verified, submitted, contacted, booked, saved or started without a corresponding trusted result. When data is missing, explain the precise gap and use the existing research flow if available; do not simulate a lookup.\n"
            + "- These are informational service descriptions, not executable actions. Only these action IDs currently have connected implementations: "
            + (!connected.isEmpty() ? String.join(", ", connected) : "NONE. In this preview, requests, applications, matching and bookings cannot be sent from the chat; guidance and preparation are available.") + "\n"
            + "- When no action is connected, keep service_trigger.trigger_now=false, service_trigger.actions=[], rmo_readiness.ready_to_generate=false; explain the limitation only when the user wants to act. Do not display an active Apply/Book/Submit button or collect documents for an unavailable transaction.\n"
            + "\n"
            + "PRODUCT CATALOGUE (internal IDs are not user-facing labels)\n"
            + (!services.isEmpty()
                ? services.stream().map(s -> s.id() + " | " + s.name() + " | " + s.benefit() + " | " + s.delivery()).collect(Collectors.joining("\n"))
                : "Catalogue unavailable. Do not invent services. Explain that service details are unavailable and continue general Yuzee guidance.") + "\n"
            + "END_OALA_YUZEE_SERVICE_HELP";
    }

    /** server.ts: Object.values(TRUSTED_SERVICE_ACTIONS).filter(a=>a.enabled&&a.isConnectedInLab).map(a=>a.actionId). */
    public static List<String> connectedActionIds() {
        return TrustedServiceActions.TRUSTED_SERVICE_ACTIONS.values().stream()
            .filter(a -> a.enabled && a.isConnectedInLab)
            .map(a -> a.actionId)
            .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Legacy helpers kept for existing callers; they delegate to the exact ports above.
    // ------------------------------------------------------------------

    public boolean addressesOala(String userText) { return parseOalaMention(userText).active(); }

    public String stripOalaMention(String userText) { return parseOalaMention(userText).message(); }

    public Optional<String> basicAnswer(String strippedText) {
        OalaBasicAnswer answer = oalaBasicAnswer("@Oala " + (strippedText == null ? "" : strippedText),
            readYuzeeServices(systemPromptService.getPrompt()));
        return answer == null ? Optional.empty() : Optional.of(answer.text());
    }

    public String buildOalaInstruction() {
        return buildOalaInstruction(readYuzeeServices(systemPromptService.getPrompt()), connectedActionIds());
    }
}
