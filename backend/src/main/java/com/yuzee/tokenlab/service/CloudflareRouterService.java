package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of server.ts's POST /api/routing/llm handler: an alternative, server-side router that
 * calls Cloudflare Workers AI's llama-3.1-8b-instruct-fast model for task=route|needs
 * classification, used when the user picks "Llama 3.1-8b Fast" as the router model in the
 * Navbar (see models.ts's CLOUDFLARE_ROUTER_MODELS). This is an optional fallback -- the default
 * router is still the client-side BGE worker -- so a missing config returns a clear "not
 * configured" result rather than a generic 500.
 */
@Service
public class CloudflareRouterService {

    private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
        "route", "You are a routing classifier for an educational guidance app. Given a user message, "
            + "pick the best matching tool or return \"none\".\n"
            + "Tools: SKILL_001=CV/resume help, SKILL_002=interview prep, SKILL_003=career change, "
            + "SKILL_004=study options, SKILL_005=job search, SKILL_006=visa/immigration, SKILL_007=course info, "
            + "SKILL_008=funding/scholarships, SKILL_009=workplace skills, SKILL_010=general\n"
            + "Respond with JSON only: {\"toolId\":\"SKILL_XXX\",\"score\":0.85,\"reason\":\"brief reason\"} or "
            + "{\"toolId\":null,\"score\":0,\"reason\":\"no match\"}.",
        "needs", "You are a classifier for an educational guidance app. Classify what the user needs.\n"
            + "Options: \"answer\" (they want information or explanation), \"clarify\" (they need clarifying "
            + "questions first), \"research\" (specific facts need checking like fees, dates, requirements).\n"
            + "Respond with JSON only: {\"kind\":\"answer\"|\"clarify\"|\"research\",\"score\":0.85,\"reason\":\"brief reason\"}."
    );

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*?\\}");

    @Value("${cloudflare.account-id:}")
    private String accountId;

    @Value("${cloudflare.api-token:}")
    private String apiToken;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build();

    /** Result of a classify() call, carrying the HTTP status the controller should reply with. */
    public static final class Result {
        public final int status;
        public final Map<String, Object> body;
        Result(int status, Map<String, Object> body) { this.status = status; this.body = body; }
    }

    public boolean isConfigured() {
        return accountId != null && !accountId.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public Result classify(String task, String text) {
        if (!isConfigured()) {
            return new Result(503, Map.of("error", "Cloudflare credentials not configured"));
        }
        if (text == null || text.isEmpty()) {
            return new Result(400, Map.of("error", "text required"));
        }
        String systemPrompt = task == null ? null : SYSTEM_PROMPTS.get(task); // Map.of rejects a null key
        if (systemPrompt == null) {
            return new Result(400, Map.of("error", "unknown task"));
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode messages = mapper.createArrayNode();
            messages.add(mapper.createObjectNode().put("role", "system").put("content", systemPrompt));
            messages.add(mapper.createObjectNode().put("role", "user").put("content", text.substring(0, Math.min(1800, text.length()))));
            body.set("messages", messages);

            String url = "https://api.cloudflare.com/client/v4/accounts/" + accountId
                + "/ai/run/@cf/meta/llama-3.1-8b-instruct-fast";
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiToken)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new Result(502, Map.of("error", "Cloudflare error " + response.code()));
                }
                JsonNode cfData = mapper.readTree(response.body().string());
                String raw = cfData.path("result").path("response").asText("");
                Matcher m = JSON_OBJECT.matcher(raw);
                // JSON.parse failure propagates to the 500 handler, as in server.ts.
                Object parsed = m.find() ? mapper.readValue(m.group(), Object.class) : null;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("task", task);
                out.put("result", parsed);
                return new Result(200, out);
            }
        } catch (Exception e) {
            return new Result(500, Map.of("error", e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : "routing failed"));
        }
    }
}
