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

    public List<JsonNode> availableCatalogueObjectives() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode o : catalogueByToolId.values()) if (o.path("available").asBoolean(false)) out.add(o);
        return out;
    }

    /** catalogue.json's version string, i.e. routing.ts's OBJECTIVE_VERSION (minus the retrieval-strategy suffix, which belonged to the vector-ranking half this port doesn't own). */
    public String catalogueVersion() { return catalogueRoot.path("version").asText(""); }

    public String globalSystemPrompt() { return globalSystemPrompt; }

    // ------------------------------------------------------------------
    // schema.mjs's compileResult()/readinessAllowed() — just enough of the DSL compiler to get
    // top-level result keys (for the RESULT_CONTRACT check and workspaceInstructions()) and parse
    // warnings (for contractBlockers()). The full typed-schema half of compileResult is skipped:
    // this port doesn't send a responseSchema to Gemini (see ObjectiveService), so no JSON Schema
    // needs to be built from the DSL, only its plain-text field names and malformed-annotation warnings.
    // ------------------------------------------------------------------

    public static final class ContractResult {
        public final List<String> completionKeys;
        public final List<String> warnings;
        ContractResult(List<String> completionKeys, List<String> warnings) {
            this.completionKeys = completionKeys;
            this.warnings = warnings;
        }
    }

    private static final Pattern FIELD_PATTERN = Pattern.compile("^([a-zA-Z]\\w*)(\\[])?\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern ENUM_ANNOTATION = Pattern.compile("^[A-Z][A-Z_]*(\\|[A-Z_]+)+$");

    /** Splits a DSL contract string on top-level commas/semicolons, respecting {}-nesting — port of schema.mjs's splitTop. */
    private static List<String> splitTop(String text) {
        int depth = 0, start = 0;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (depth < 0) throw new IllegalArgumentException("Unbalanced output contract");
            if (depth == 0 && (c == ',' || c == ';')) {
                out.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced output contract");
        String last = text.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out.stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    /** Port of schema.mjs's compileResult(), minus the typed-schema construction (see class javadoc). */
    public static ContractResult compileResult(String contract) {
        List<String> warnings = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (contract == null || contract.isBlank()) return new ContractResult(keys, warnings);
        for (String segment : splitTop(contract)) {
            var m = FIELD_PATTERN.matcher(segment);
            if (!m.matches()) {
                warnings.add("result: " + segment);
                continue;
            }
            String key = m.group(1);
            String tail = m.group(3) == null ? "" : m.group(3);
            if (keys.contains(key)) {
                warnings.add("result: Duplicate field " + key);
                continue;
            }
            keys.add(key);
            if (tail.startsWith("{")) {
                int depth = 0, end = -1;
                for (int i = 0; i < tail.length(); i++) {
                    if (tail.charAt(i) == '{') depth++;
                    if (tail.charAt(i) == '}' && --depth == 0) { end = i; break; }
                }
                if (end < 0) throw new IllegalArgumentException("Unbalanced object contract");
                if (!tail.substring(end + 1).trim().isEmpty()) {
                    warnings.add("result." + key + ": " + tail.substring(end + 1).trim());
                }
                // Nested object shape isn't compiled to a typed schema here (see class javadoc) —
                // only its top-level field name and brace-balance are needed by this port.
            } else {
                String annotation = tail.trim().replaceFirst("^:\\s*", "");
                if (annotation.isEmpty() || ENUM_ANNOTATION.matcher(annotation).matches() || annotation.equals("=true")) {
                    // recognised: plain scalar/array, an ENUM|LIST annotation, or a =true const — no warning
                } else {
                    warnings.add("result." + key + ": " + annotation);
                }
            }
        }
        return new ContractResult(keys, warnings);
    }

    private static final Pattern READINESS_BANNED = Pattern.compile(
        "^(NONE\\b|No psychometric score|Goal-state routing only|Exploration only)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READINESS_NO_SCORE = Pattern.compile(
        "no (?:numeric )?readiness score", Pattern.CASE_INSENSITIVE);

    /** Port of schema.mjs's readinessAllowed(). */
    public static boolean readinessAllowed(JsonNode workbookEntry) {
        String policy = workbookEntry.path("qa_readiness_policy_v2").asText("");
        return !READINESS_BANNED.matcher(policy).find() && !READINESS_NO_SCORE.matcher(policy).find();
    }

    /** Port of service.ts's contractBlockers(id): DSL parse warnings that must block running the objective. */
    public List<String> contractBlockers(String toolId) {
        JsonNode entry = workbookByToolId.get(toolId);
        if (entry == null) return List.of("No workbook entry for " + toolId);
        return compileResult(entry.path("qa_output_contract_v2").asText("")).warnings;
    }

    // ------------------------------------------------------------------
    // routing.ts — shortlist / fusion / selection-boundary. The vector-ranking half
    // (rankObjectivesFromVectors/mergeObjectiveRanks) is the client-side embedding work owned
    // elsewhere; only the parts that operate on already-scored candidates are ported here.
    // ------------------------------------------------------------------

    /** Port of routing.ts's objectiveShortlist(): preview retrieval gate, not a probability. */
    public List<ObjectiveMatch> shortlist(List<ObjectiveMatch> matches) {
        if (matches == null) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ObjectiveMatch> out = new ArrayList<>();
        for (ObjectiveMatch m : matches) {
            if (m == null || m.getId() == null) continue;
            JsonNode cat = catalogueByToolId.get(m.getId());
            if (cat == null || !cat.path("available").asBoolean(false)) continue;
            double score = m.getScore();
            if (!Double.isFinite(score) || score < 0.5 || score > 1.001) continue;
            if (!seen.add(m.getId())) continue;
            out.add(m);
            if (out.size() >= SHORTLIST_LIMIT) break;
        }
        return out;
    }

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
        "^(both|either|that one|this one|the first|the second|same|yes|no)(?:[,.! ]|$)", Pattern.CASE_INSENSITIVE);

    /** Port of routing.ts's fuseObjectiveMatches() — reciprocal-rank fusion across the user-text and context-derived rankings. */
    public List<ObjectiveMatch> fuse(List<ObjectiveMatch> user, List<ObjectiveMatch> context, String userText) {
        return fuse(user, context, SHORTLIST_LIMIT, userText);
    }

    public List<ObjectiveMatch> fuse(List<ObjectiveMatch> user, List<ObjectiveMatch> context, int limit, String userText) {
        user = user == null ? List.of() : user;
        context = context == null ? List.of() : context;
        Map<String, ObjectiveMatch> byId = new LinkedHashMap<>();
        Map<String, Double> fusion = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> sources = new LinkedHashMap<>();

        List<ObjectiveMatch> combinedOrder = new ArrayList<>();
        combinedOrder.addAll(user);
        combinedOrder.addAll(context);
        for (String sourceName : List.of("user", "context")) {
            List<ObjectiveMatch> ranking = "user".equals(sourceName) ? user : context;
            for (int i = 0; i < ranking.size(); i++) {
                ObjectiveMatch c = ranking.get(i);
                ObjectiveMatch row = byId.get(c.getId());
                if (row == null) {
                    row = new ObjectiveMatch(c.getId(), c.getScore());
                    byId.put(c.getId(), row);
                }
                row.setScore(Math.max(row.getScore(), c.getScore()));
                sources.computeIfAbsent(c.getId(), k -> new LinkedHashSet<>()).add(sourceName);
                double weight = ("user".equals(sourceName) ? 1.2 : 1.0) / (60.0 + i + 1);
                fusion.merge(c.getId(), weight, Double::sum);
            }
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : sources.entrySet()) {
            byId.get(e.getKey()).setSources(new ArrayList<>(e.getValue()));
        }

        List<ObjectiveMatch> fused = new ArrayList<>(byId.values());
        fused.sort((a, b) -> Double.compare(fusion.getOrDefault(b.getId(), 0.0), fusion.getOrDefault(a.getId(), 0.0)));

        boolean reference = userText != null && userText.trim().length() < 80
            && REFERENCE_PATTERN.matcher(userText.trim()).find();

        List<ObjectiveMatch> ordered;
        if (reference && !context.isEmpty()) {
            ordered = new ArrayList<>();
            context.stream().limit(Math.max(1, limit - 4)).forEach(c -> ordered.add(byId.get(c.getId())));
            user.stream().limit(4).forEach(c -> ordered.add(byId.get(c.getId())));
            ordered.addAll(fused);
        } else {
            ordered = fused;
        }

        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        List<ObjectiveMatch> result = new ArrayList<>();
        for (ObjectiveMatch m : ordered) {
            if (m != null && dedup.add(m.getId())) result.add(m);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static final Pattern SOCIAL_PATTERN = Pattern.compile(
        "^(hi|hello|hey|thanks?|thank you|ok|okay|yes|no|stop|cancel|never mind)[!. ]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLINED_PATTERN_1 = Pattern.compile(
        "\\b(?:do not|don['’]?t)\\s+(?:need|want|open|suggest|offer|show|start|run)\\b.{0,45}\\b(?:activit(?:y|ies)|tools?|workspace|suggestions?)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLINED_PATTERN_2 = Pattern.compile(
        "\\b(stop suggesting|no more (tools|suggestions)|do not suggest|don.t suggest)\\b", Pattern.CASE_INSENSITIVE);

    /** Port of routing.ts's objectiveSelectionBoundary(): returns a reason code, or null to allow selection to proceed. */
    public String selectionBoundary(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 16000) return "input-limit";
        if (SOCIAL_PATTERN.matcher(t).matches()) return "conversation";
        if (DECLINED_PATTERN_1.matcher(t).find()) return "declined";
        if (DECLINED_PATTERN_2.matcher(t).find()) return "declined";
        return null;
    }
}
