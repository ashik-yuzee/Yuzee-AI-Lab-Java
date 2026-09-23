package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.model.ObjectiveMatch;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java port of routing.ts + the byId lookup from service.ts + the DSL bits of schema.mjs
 * ({@code compileResult}/{@code readinessAllowed}) that ObjectiveService needs to run an
 * objective from the workbook. Loads catalogue.json, selectionMetadata.json and workbook.json
 * once at startup and caches them in memory — all three are static reference data.
 */
@Service
public class ObjectiveCatalogueService {

    private static final String CATALOGUE_PATH = "config/objectives/catalogue.json";
    private static final String SELECTION_METADATA_PATH = "config/objectives/selectionMetadata.json";
    private static final String WORKBOOK_PATH = "config/objectives/workbook.json";

    /** routing.ts's SHORTLIST_LIMIT. */
    public static final int SHORTLIST_LIMIT = 20;

    private final ObjectMapper mapper;

    private JsonNode catalogueRoot;
    private JsonNode selectionMetadataRoot;
    private JsonNode workbookRoot;
    private final Map<String, JsonNode> catalogueByToolId = new LinkedHashMap<>();
    private final Map<String, JsonNode> workbookByToolId = new LinkedHashMap<>();
    private final Map<String, JsonNode> selectionMetadataByToolId = new LinkedHashMap<>();
    private String globalSystemPrompt = "";

    public ObjectiveCatalogueService() {
        // workbook.json is ~10MB with some individual string fields (qa_prompt_v2, mini_prompt_v1)
        // running to several KB; raise Jackson's StreamReadConstraints well above the defaults so a
        // single large token never trips JsonParser's guardrails.
        StreamReadConstraints constraints = StreamReadConstraints.builder()
            .maxStringLength(100_000_000)
            .maxNumberLength(10_000)
            .maxNestingDepth(2_000)
            .build();
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(constraints).build();
        this.mapper = new ObjectMapper(factory);
    }

    @PostConstruct
    void init() {
        catalogueRoot = readTree(CATALOGUE_PATH);
        selectionMetadataRoot = readTree(SELECTION_METADATA_PATH);
        workbookRoot = readTree(WORKBOOK_PATH);

        for (JsonNode o : catalogueRoot.path("objectives")) {
            catalogueByToolId.put(o.path("tool_id").asText(), o);
        }
        for (JsonNode row : selectionMetadataRoot.path("rows")) {
            selectionMetadataByToolId.put(row.path("id").asText(), row);
        }
        for (JsonNode o : workbookRoot.path("objectives")) {
            workbookByToolId.put(o.path("tool_id").asText(), o);
        }
        globalSystemPrompt = workbookRoot.path("global_rules").path("Global system prompt").asText("");
    }

    private JsonNode readTree(String classpath) {
        try (InputStream is = new ClassPathResource(classpath).getInputStream()) {
            return mapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load objective catalogue resource: " + classpath, e);
        }
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public Map<String, JsonNode> catalogueById() { return catalogueByToolId; }
    public Map<String, JsonNode> workbookById() { return workbookByToolId; }

    public JsonNode findCatalogueObjective(String toolId) { return catalogueByToolId.get(toolId); }
    public JsonNode findWorkbookEntry(String toolId) { return workbookByToolId.get(toolId); }
    public JsonNode findSelectionMetadata(String toolId) { return selectionMetadataByToolId.get(toolId); }

    /** server.ts catalogue route: every catalogue objective with available = no contract blockers. */
    public List<JsonNode> catalogueWithAvailability() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode o : catalogueByToolId.values()) {
            out.add(((com.fasterxml.jackson.databind.node.ObjectNode) o.deepCopy()).put("available", contractBlockers(o.path("tool_id").asText()).isEmpty()));
        }
        return out;
    }

    /** routing.ts OBJECTIVE_VERSION. */
    public String objectiveVersion() { return catalogueRoot.path("version").asText("") + "-retrieval5-rto"; }

    /** workbook.json source_sha256 (service.ts audit records). */
    public String workbookSha256() { return workbookRoot.path("source_sha256").asText(null); }

    public String globalSystemPrompt() { return globalSystemPrompt; }

    // ------------------------------------------------------------------
    // schema.mjs's compileResult()/readinessAllowed(), ported in full in ObjectiveSchema.
    // ------------------------------------------------------------------

    public static final class ContractResult {
        public final List<String> completionKeys;
        public final List<Map<String, Object>> warnings;
        ContractResult(List<String> completionKeys, List<Map<String, Object>> warnings) {
            this.completionKeys = completionKeys;
            this.warnings = warnings;
        }
    }

    /** schema.mjs compileResult(contract): result keys and DSL warnings ({path, text}), nested objects included. */
    public static ContractResult compileResult(String contract) {
        ObjectiveSchema.Compiled c = ObjectiveSchema.compileResult(contract);
        return new ContractResult(c.completionKeys, c.warnings);
    }

    /** schema.mjs readinessAllowed(o). */
    public static boolean readinessAllowed(JsonNode workbookEntry) {
        return ObjectiveSchema.readinessAllowed(workbookEntry);
    }

    /** Port of service.ts's contractBlockers(id): DSL parse warnings that must block running the objective. */
    public List<Map<String, Object>> contractBlockers(String toolId) {
        JsonNode entry = workbookByToolId.get(toolId);
        return compileResult(entry == null ? "" : entry.path("qa_output_contract_v2").asText("")).warnings;
    }

    // ------------------------------------------------------------------
    // routing.ts objectiveSelectionBoundary(). Ranking, fusion and objectiveShortlist() run in the client and in
    // ObjectiveService.select() respectively.
    // ------------------------------------------------------------------

    private static final Pattern SOCIAL_PATTERN = RoutingPolicyService.jsRegex(
        "^(hi|hello|hey|thanks?|thank you|ok|okay|yes|no|stop|cancel|never mind)[!. ]*$", true);
    private static final Pattern DECLINED_PATTERN_1 = RoutingPolicyService.jsRegex(
        "\\b(?:do not|don['’]?t)\\s+(?:need|want|open|suggest|offer|show|start|run)\\b.{0,45}\\b(?:activit(?:y|ies)|tools?|workspace|suggestions?)\\b",
        true);
    private static final Pattern DECLINED_PATTERN_2 = RoutingPolicyService.jsRegex(
        "\\b(stop suggesting|no more (tools|suggestions)|do not suggest|don.t suggest)\\b", true);

    /** Port of routing.ts's objectiveSelectionBoundary(): returns a reason code, or null to allow selection to proceed. */
    public String selectionBoundary(String text) {
        String t = RoutingPolicyService.jsTrim(text == null ? "" : text);
        if (t.isEmpty() || t.length() > 16000) return "input-limit";
        if (SOCIAL_PATTERN.matcher(t).find()) return "conversation";
        if (DECLINED_PATTERN_1.matcher(t).find()) return "declined";
        if (DECLINED_PATTERN_2.matcher(t).find()) return "declined";
        return null;
    }
}
