package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzee.tokenlab.model.warehouse.CourseQuality;
import com.yuzee.tokenlab.model.warehouse.ExplorationChoice;
import com.yuzee.tokenlab.model.warehouse.ProviderMatch;
import com.yuzee.tokenlab.model.warehouse.WarehouseComparison;
import com.yuzee.tokenlab.model.warehouse.WarehouseConnections;
import com.yuzee.tokenlab.model.warehouse.WarehouseCourse;
import com.yuzee.tokenlab.model.warehouse.WarehouseExploration;
import com.yuzee.tokenlab.model.warehouse.WarehousePack;
import com.yuzee.tokenlab.model.warehouse.WarehouseQueryPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.yuzee.tokenlab.service.warehouse.WarehouseText.cleanText;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.jsString;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.linkedList;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.list;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.number;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.oppList;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.or;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.text;
import static com.yuzee.tokenlab.service.warehouse.WarehouseText.truthy;

/**
 * Read-only lookups against the source {@code training_gov.db} and the derived index. Line-by-line
 * port of catalogue-worker.cjs (lookup handler, {@code search()}), linked-data.cjs
 * ({@code createLinkedReader}), opportunities.cjs ({@code createOpportunityReader}),
 * provider-data.cjs ({@code createProviderReader}), normalize.ts, comparison.ts and choices.ts.
 * A source table that does not exist reads as no rows, as in the old readers' {@code query()}.
 */
@Service
public class WarehouseQueryService {

    private static final String COURSE_FIELDS = "id,course_name,institution_id,institution_name,national_code,course_code,aqf_level,course_type,description,duration_text,delivery_modes_json,locations_json,entry_requirements,domestic_fee,international_fee,skills_json,quality_scores_json,work_readiness_score,future_readiness_score,yuzee_readiness_score,career_outcomes_json,intelligence_json,canonical_url,web_collect_url,website,intelligence_enriched_at,updated_at";
    private static final Set<String> STATES = Set.of("ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA");
    /** JS String#localeCompare. */
    private static final Collator COLLATOR = Collator.getInstance(Locale.ROOT);

    private final WarehouseIndexBuilder indexBuilder;

    public WarehouseQueryService(WarehouseIndexBuilder indexBuilder) {
        this.indexBuilder = indexBuilder;
    }

    /** The source and index connections of one lookup (the worker's two DatabaseSync handles). */
    private final class Session implements AutoCloseable {
        final Connection sourceConn;
        final Connection indexConn;
        final JdbcTemplate source;
        final JdbcTemplate index;
        final Set<String> tables = new HashSet<>();

        Session() throws SQLException {
            SQLiteConfig readOnly = new SQLiteConfig();
            readOnly.setReadOnly(true);
            sourceConn = DriverManager.getConnection("jdbc:sqlite:" + indexBuilder.getSourcePath(), readOnly.toProperties());
            indexConn = DriverManager.getConnection("jdbc:sqlite:" + indexBuilder.getIndexPath());
            source = new JdbcTemplate(new SingleConnectionDataSource(sourceConn, true));
            index = new JdbcTemplate(new SingleConnectionDataSource(indexConn, true));
            for (Map<String, Object> row : source.queryForList("SELECT name FROM sqlite_master WHERE type='table'")) tables.add(String.valueOf(row.get("name")));
        }

        /** linked-data.cjs query(t, sql, args): no rows when the table is absent. */
        List<Map<String, Object>> query(String table, String sql, Object... args) {
            return tables.contains(table) ? source.queryForList(sql, args) : List.of();
        }

        Map<String, Object> get(String table, String sql, Object... args) {
            List<Map<String, Object>> rows = query(table, sql, args);
            return rows.isEmpty() ? null : rows.get(0);
        }

        Map<String, Object> indexGet(String sql, Object... args) {
            List<Map<String, Object>> rows = index.queryForList(sql, args);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public void close() {
            try { sourceConn.close(); } catch (SQLException ignored) {}
            try { indexConn.close(); } catch (SQLException ignored) {}
        }
    }

    /** The worker's reply: {@code {rows, connected, providerMatches, qualifications}}, rows normalized. */
    public record LookupResult(List<WarehouseCourse> courses, WarehouseConnections connected,
                                List<ProviderMatch> providerMatches, List<WarehouseComparison.Qualification> qualifications) {}

    /** catalogue-worker.cjs's lookup message handler. Any failure surfaces as an exception. */
    public LookupResult lookup(List<String> queries, List<String> ids, WarehouseQueryPlan plan) throws SQLException {
        List<String> qs = queries == null ? List.of() : queries;
        WarehouseQueryPlan p = plan == null ? new WarehouseQueryPlan() : plan;
        try (Session s = new Session()) {
            List<String> chosen = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String id : (ids == null ? List.<String>of() : ids).stream().limit(4).toList()) {
                if (id != null && id.matches("\\d+") && seen.add(id)) chosen.add(id);
            }
            // Interleave queries so one option cannot consume an entire comparison shortlist.
            List<ProviderMatch> providerMatches = providerMatches(s, p.getProviderQueries());
            // Duplicate legal names can represent distinct registrations. Resolve only
            // when the user's requested course actually belongs to exactly one record.
            for (ProviderMatch match : providerMatches) {
                if (!"AMBIGUOUS".equals(match.getStatus()) || qs.isEmpty()) continue;
                List<ProviderMatch.ProviderRecord> offering = match.getProviders().stream()
                    .filter(pr -> qs.stream().anyMatch(q -> !search(s, q, pr.getId()).isEmpty())).collect(Collectors.toList());
                if (offering.size() == 1) {
                    match.setStatus("MATCHED");
                    match.setProviders(offering);
                    match.setResolution("Unique requested course offering");
                }
            }
            List<ProviderMatch.ProviderRecord> resolved = providerMatches.stream()
                .filter(m -> "MATCHED".equals(m.getStatus())).flatMap(m -> m.getProviders().stream()).toList();
            List<String> first3 = qs.stream().limit(3).toList();
            List<List<Map<String, Object>>> groups = new ArrayList<>();
            if (!providerMatches.isEmpty()) { for (var pr : resolved) for (String q : first3) groups.add(search(s, q, pr.getId())); }
            else for (String q : first3) groups.add(search(s, q, null));
            int depth = !providerMatches.isEmpty() && qs.stream().allMatch(q -> com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim(q).matches("(?i)[A-Z]{3}[0-9]{5}")) ? 1 : 16;
            for (int n = 0; n < depth && chosen.size() < 4; n++) {
                for (var group : groups) {
                    if (n >= group.size()) continue;
                    String courseId = jsString(group.get(n).get("course_id"));
                    if (!seen.contains(courseId) && chosen.size() < 4) { seen.add(courseId); chosen.add(courseId); }
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String id : chosen) {
                var hit = s.source.queryForList("SELECT " + COURSE_FIELDS + " FROM live_courses WHERE id=?", id);
                if (!hit.isEmpty()) rows.add(hit.get(0));
            }
            boolean comparing = p.isComparison() || !providerMatches.isEmpty();
            WarehouseConnections connected = new Linked(s, p).read(rows);
            List<ProviderMatch> matches = !comparing ? List.of() : !providerMatches.isEmpty() ? providerMatches
                : providerMatches(s, rows.stream().map(r -> r.get("institution_name")).filter(WarehouseText::truthy).map(WarehouseText::jsString).distinct().toList());
            return new LookupResult(rows.stream().map(this::normalizeCourse).toList(), connected, matches,
                comparing ? qualifications(s, rows) : List.of());
        }
    }

    /** catalogue-worker.cjs search(): all terms must match. */
    private List<Map<String, Object>> search(Session s, String text, String providerId) {
        String clause = WarehouseText.catalogueClause(text);
        if (clause.isEmpty()) return List.of();
        String sql = "SELECT course_id,course_name,institution_name,bm25(catalogue,0,5,3,8,8,0) AS rank FROM catalogue WHERE catalogue MATCH ?"
            + (providerId != null ? " AND institution_id=?" : "") + " ORDER BY rank LIMIT 16";
        return providerId != null ? s.index.queryForList(sql, clause, providerId) : s.index.queryForList(sql, clause);
    }

    // ---- provider-data.cjs -------------------------------------------------------------------

    private static final List<String> PROVIDER_FIELDS = List.of("id", "legal_name", "rto_code", "rto_type", "city", "state", "about_us_description", "website_url",
        "has_student_support", "has_disability_support", "has_indigenous_support", "has_library", "has_apprenticeships", "updated_at");
    private static final Map<String, String> SUPPORT_LABELS = new LinkedHashMap<>();
    static {
        SUPPORT_LABELS.put("has_student_support", "Student support");
        SUPPORT_LABELS.put("has_disability_support", "Disability support");
        SUPPORT_LABELS.put("has_indigenous_support", "Indigenous learner support");
        SUPPORT_LABELS.put("has_library", "Library");
        SUPPORT_LABELS.put("has_apprenticeships", "Apprenticeship support");
    }

    private Set<String> institutionColumns(Session s) {
        Set<String> cols = new HashSet<>();
        for (var r : s.source.queryForList("PRAGMA table_info(live_institutions)")) cols.add(String.valueOf(r.get("name")));
        return cols;
    }

    private List<ProviderMatch> providerMatches(Session s, List<String> queries) {
        List<ProviderMatch> out = new ArrayList<>();
        if (queries == null || queries.isEmpty()) return out;
        Set<String> cols = institutionColumns(s);
        String select = PROVIDER_FIELDS.stream().map(k -> cols.contains(k) ? k : "NULL AS " + k).collect(Collectors.joining(","));
        for (String q : queries.stream().limit(3).toList()) {
            ProviderMatch match = new ProviderMatch();
            match.setQuery(q);
            List<String> words = WarehouseText.providerWords(String.valueOf(q));
            if (!cols.contains("legal_name") || words.isEmpty()) { match.setStatus("NOT_FOUND"); out.add(match); continue; }
            List<Map<String, Object>> rows = cols.contains("rto_code")
                ? s.source.queryForList("SELECT " + select + " FROM live_institutions WHERE lower(legal_name)=lower(?) OR rto_code=? LIMIT 4", q, q)
                : s.source.queryForList("SELECT " + select + " FROM live_institutions WHERE lower(legal_name)=lower(?) LIMIT 4", q);
            if (rows.isEmpty()) {
                rows = s.source.queryForList("SELECT " + select + " FROM live_institutions WHERE "
                    + words.stream().map(w -> "legal_name LIKE ? ESCAPE '\\'").collect(Collectors.joining(" AND ")) + " ORDER BY legal_name LIMIT 4",
                    words.stream().map(w -> "%" + WarehouseText.safeLike(w) + "%").toArray());
            }
            match.setStatus(rows.size() == 1 ? "MATCHED" : rows.isEmpty() ? "NOT_FOUND" : "AMBIGUOUS");
            for (var r : rows) match.getProviders().add(projectProvider(r));
            out.add(match);
        }
        return out;
    }

    private ProviderMatch.ProviderRecord projectProvider(Map<String, Object> r) {
        ProviderMatch.ProviderRecord p = new ProviderMatch.ProviderRecord();
        p.setId(jsString(r.get("id")));
        p.setEvidenceId("warehouse_rto_" + jsString(r.get("id")));
        String name = WarehouseText.providerText(r.get("legal_name"));
        p.setName(name != null ? name : "Provider");
        p.setRtoCode(WarehouseText.providerText(r.get("rto_code")));
        p.setType(WarehouseText.providerText(r.get("rto_type")));
        p.setArea(joinTruthy(r.get("city"), r.get("state")));
        p.setDescription(WarehouseText.providerText(r.get("about_us_description")));
        List<String> support = new ArrayList<>();
        SUPPORT_LABELS.forEach((k, label) -> { if (WarehouseText.isOne(r.get(k))) support.add(label); });
        p.setSupport(support);
        p.setUpdatedAt(WarehouseText.providerText(r.get("updated_at")));
        p.setScope("Provider record; general locations and support do not establish course-specific delivery.");
        return p;
    }

    private List<WarehouseComparison.Qualification> qualifications(Session s, List<Map<String, Object>> rows) {
        boolean hasUnits = s.tables.contains("qualification_units");
        List<WarehouseComparison.Qualification> out = new ArrayList<>();
        for (String code : rows.stream().map(r -> r.get("national_code")).filter(WarehouseText::truthy).map(WarehouseText::jsString).distinct().limit(4).toList()) {
            WarehouseComparison.Qualification q = new WarehouseComparison.Qualification();
            q.setCode(code);
            q.setEvidenceId("warehouse_qualification_" + code);
            if (hasUnits) {
                for (var u : s.source.queryForList("SELECT unit_code,unit_title,unit_type FROM qualification_units WHERE qualification_code=? ORDER BY unit_type,unit_code LIMIT 30", code)) {
                    q.getUnits().add(new WarehouseComparison.Unit(WarehouseText.providerText(u.get("unit_code")), WarehouseText.providerText(u.get("unit_title")), WarehouseText.providerText(u.get("unit_type"))));
                }
            }
            q.setScope("National qualification content (up to 30 units), not a provider-specific elective or delivery plan.");
            out.add(q);
        }
        return out;
    }

    // ---- normalize.ts --------------------------------------------------------------------------

    private static JsonNode at(JsonNode node, String... path) {
        JsonNode v = node;
        for (String p : path) v = v.path(p);
        return v;
    }

    private static JsonNode orEmpty(JsonNode v) { return truthy(v) ? v : WarehouseText.MAPPER.createObjectNode(); }

    public WarehouseCourse normalizeCourse(Map<String, Object> r) {
        JsonNode i = WarehouseText.json(r.get("intelligence_json"));
        JsonNode facts = orEmpty(i.path("verified_facts"));
        JsonNode outcome = orEmpty(i.path("yuzee_outcome_layer"));
        JsonNode skills = WarehouseText.json(r.get("skills_json"));
        ObjectNode q = WarehouseText.MAPPER.createObjectNode();
        if (i.path("trust_and_quality").isObject()) q.setAll((ObjectNode) i.path("trust_and_quality"));
        JsonNode scores = WarehouseText.json(r.get("quality_scores_json"));
        if (scores.isObject()) q.setAll((ObjectNode) scores);
        Integer frameworkLevel = null;
        for (Object candidate : new Object[]{at(i, "classification", "framework_level"), at(i, "course_identity", "framework_level"), r.get("aqf_level")}) {
            Double n = WarehouseText.jsNumberValue(candidate);
            if (n != null && n == Math.rint(n) && n >= 1 && n <= 10) { frameworkLevel = n.intValue(); break; }
        }
        List<CourseQuality> quality = new ArrayList<>();
        addQuality(quality, "work", "Work readiness", WarehouseText.coalesce(r.get("work_readiness_score"), q.path("work_readiness_score")),
            "The catalogue’s assessment of preparation for the course’s intended work or progression outcome.");
        addQuality(quality, "future", "Future relevance", WarehouseText.coalesce(r.get("future_readiness_score"), q.path("future_relevance_score")),
            "The catalogue’s assessment of relevance to changing work and skills.");
        addQuality(quality, "overall", "Overall course readiness", WarehouseText.coalesce(r.get("yuzee_readiness_score"), q.path("overall_yuzee_readiness_score")),
            "The stored Yuzee course assessment. This is not your personal readiness or a job-success probability.");

        WarehouseCourse course = new WarehouseCourse();
        course.setId(jsString(r.get("id")));
        course.setProviderId(r.get("institution_id") == null ? "" : jsString(r.get("institution_id")));
        course.setEvidenceId("warehouse_course_" + jsString(r.get("id")));
        String name = cleanText(r.get("course_name"));
        course.setName(name != null ? name : "Course");
        String provider = cleanText(r.get("institution_name"));
        course.setProvider(provider != null ? provider : "Provider not supplied");
        course.setCode(cleanText(or(r.get("national_code"), r.get("course_code"))));
        course.setLevel(frameworkLevel == null ? null : String.valueOf(frameworkLevel));
        course.setType(cleanText(or(r.get("course_type"), at(i, "classification", "recognition_class"))));
        course.setDescription(cleanText(or(outcome.path("student_outcome_headline"), at(i, "rendered_content", "short_summary"), r.get("description"))));
        course.setDuration(cleanText(or(r.get("duration_text"), facts.path("duration"))));
        JsonNode delivery = WarehouseText.json(r.get("delivery_modes_json"));
        course.setDelivery(list(hasLength(delivery) ? delivery : facts.path("delivery_modes")));
        JsonNode locations = WarehouseText.json(r.get("locations_json"));
        course.setLocations(list(hasLength(locations) ? locations : facts.path("campuses")));
        course.setEntry(list(or(r.get("entry_requirements"), facts.path("entry_requirements"))));
        WarehouseCourse.Fees fees = new WarehouseCourse.Fees();
        fees.setDomestic(cleanText(WarehouseText.coalesce(r.get("domestic_fee"), facts.path("domestic_fee"))));
        fees.setInternational(cleanText(WarehouseText.coalesce(r.get("international_fee"), facts.path("international_fee"))));
        fees.setDetails(list(at(i, "cost_full_picture", "funding_options")));
        course.setFees(fees);
        course.setSkills(list(or(skills.path("technical_skills"), at(i, "skills", "technical_skills"), at(i, "learning_content", "core_skills"))));
        course.setOutcomes(list(or(at(i, "credit_and_pathways", "pathway_to_next_level"), WarehouseText.json(r.get("career_outcomes_json")))));
        course.setAssessments(list(at(i, "assessment_model", "assessment_types")));
        course.setBestFor(list(outcome.path("best_suited_for")));
        course.setConsiderations(considerations(outcome));
        course.setQuality(quality);
        course.setQualityExplanation(cleanText(q.path("scoring_reason")));
        course.setIntelligence(intelligenceSections(i));
        WarehouseCourse.ComparisonDetails cd = new WarehouseCourse.ComparisonDetails();
        cd.setLearning(list(at(i, "learning_content", "core_skills")));
        List<String> practice = new ArrayList<>(list(at(facts, "work_placement", "model")));
        JsonNode hours = at(facts, "work_placement", "hours");
        if (!hours.isMissingNode() && !hours.isNull()) practice.add("Recorded placement hours: " + jsString(hours));
        for (String t : list(at(i, "work_readiness", "portfolio_artifacts"), 3)) practice.add("Yuzee portfolio guidance: " + t);
        cd.setPractice(practice);
        List<String> attendance = new ArrayList<>(list(at(i, "delivery_detail", "attendance_requirement")));
        attendance.addAll(list(at(i, "delivery_detail", "mode")));
        cd.setAttendance(attendance);
        List<String> credit = new ArrayList<>();
        JsonNode rpl = at(i, "credit_and_pathways", "rpl_available");
        if (rpl.isBoolean()) credit.add("Recognition of prior learning: " + (rpl.booleanValue() ? "recorded as available" : "recorded as unavailable"));
        credit.addAll(list(at(i, "credit_and_pathways", "articulation_pathways"), 3));
        cd.setCredit(credit);
        cd.setStrengths(list(outcome.path("best_suited_for")));
        cd.setLimitations(considerations(outcome));
        cd.setOutcome(cleanText(outcome.path("student_outcome_headline")));
        course.setComparisonDetails(cd);
        WarehouseCourse.Source source = new WarehouseCourse.Source();
        String url = WarehouseText.url(r.get("canonical_url"));
        if (url == null) url = WarehouseText.url(r.get("web_collect_url"));
        if (url == null) url = WarehouseText.url(r.get("website"));
        source.setUrl(url);
        source.setUpdatedAt(cleanText(or(r.get("intelligence_enriched_at"), r.get("updated_at"))));
        course.setSource(source);
        // Respect a conflict already identified by the warehouse itself. Do not silently
        // pick one of the contradictory interpretations or expose it as provider fact.
        String issueText = course.getQualityExplanation() != null ? course.getQualityExplanation() : "";
        if (CONFLICT.matcher(issueText).find() && !NO_CONFLICT.matcher(issueText).find()) {
            course.setEvidenceIssues(List.of(course.getProvider() + ": the catalogue flags conflicting course information. Course-specific duration, delivery, entry requirements and analysis are not established by this record.",
                course.getQualityExplanation()));
            course.setDescription(null);
            course.setDuration(null);
            course.setDelivery(new ArrayList<>());
            course.setLocations(new ArrayList<>());
            course.setEntry(new ArrayList<>());
            course.setFees(new WarehouseCourse.Fees());
            course.setSkills(new ArrayList<>());
            course.setOutcomes(new ArrayList<>());
            course.setAssessments(new ArrayList<>());
            course.setBestFor(new ArrayList<>());
            course.setConsiderations(new ArrayList<>());
            course.setQuality(new ArrayList<>());
            course.setIntelligence(new ArrayList<>());
            course.setComparisonDetails(new WarehouseCourse.ComparisonDetails());
        }
        return course;
    }

    private static final Pattern CONFLICT = Pattern.compile("critical conflict|unresolved conflict|conflicting (?:source|course|information|descriptions)|contradict(?:ory|ion)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_CONFLICT = Pattern.compile("\\b(?:no|without|not an?) (?:critical )?(?:conflict|contradiction)", Pattern.CASE_INSENSITIVE);

    /** JS {@code json(x)?.length} truthiness. */
    private static boolean hasLength(JsonNode v) {
        return (v.isArray() && v.size() > 0) || (v.isTextual() && !v.asText().isEmpty());
    }

    private List<String> considerations(JsonNode outcome) {
        List<String> out = new ArrayList<>();
        String weakness = cleanText(outcome.path("honest_weakness"));
        if (weakness != null) out.add(weakness);
        out.addAll(list(outcome.path("not_ideal_for"), 3));
        return out;
    }

    private void addQuality(List<CourseQuality> out, String key, String label, Object raw, String explanation) {
        Double v = WarehouseText.score(raw);
        if (v != null) out.add(new CourseQuality(key, label, v, explanation));
    }

    // Only decision-useful sections are projected. Administrative fields stay in the warehouse.
    private static final Pattern ADMIN_KEY = Pattern.compile("(?:source|url|confidence|verified|audit|status|score|id)$");

    private List<WarehouseCourse.IntelligenceSection> intelligenceSections(JsonNode i) {
        List<Object[]> definitions = new ArrayList<>();
        definitions.add(new Object[]{"learning", "Learning and practical work", i.path("learning_content")});
        definitions.add(new Object[]{"gaps", "Skills to build further", or(i.path("skill_gap_map"), at(i, "skills", "skills_not_fully_closed"))});
        definitions.add(new Object[]{"practice", "Practical experience and portfolio", object("internships", at(i, "work_readiness", "internship_plan"),
            "portfolio", at(i, "work_readiness", "portfolio_artifacts"), "placement", at(i, "work_readiness", "placement_status"))});
        definitions.add(new Object[]{"roles", "Roles and progression", object("entry_roles", at(i, "career_pathways", "entry_roles"),
            "roles_with_further_experience", at(i, "career_pathways", "stretch_roles"), "not_immediate_roles", at(i, "career_pathways", "not_immediate_roles"))});
        definitions.add(new Object[]{"role_basis", "How career possibilities are described", object("provider_claimed_roles", at(i, "outcome_evidence", "provider_claimed_roles"),
            "yuzee_inferred_roles", at(i, "outcome_evidence", "yuzee_inferred_roles"))});
        definitions.add(new Object[]{"progression", "Credit and further study", i.path("credit_and_pathways")});
        ObjectNode costs = WarehouseText.MAPPER.createObjectNode();
        JsonNode cost = i.path("cost_full_picture");
        if (cost.isObject()) cost.fields().forEachRemaining(e -> { if (!Set.of("tuition_fee", "confidence", "funding_options").contains(e.getKey())) costs.set(e.getKey(), e.getValue()); });
        definitions.add(new Object[]{"costs", "Costs beyond tuition", costs});
        definitions.add(new Object[]{"professional", "Professional and placement requirements", object("registration_body", at(i, "regulated_profession", "registration_body"),
            "placement_clearances", at(i, "regulated_profession", "placement_clearances"), "licensing_steps", at(i, "regulated_profession", "licensing_steps_after_graduation"))});
        definitions.add(new Object[]{"delivery", "Attendance and study setup", i.path("delivery_detail")});
        definitions.add(new Object[]{"ai", "AI and changing work", object("context", at(i, "ai_readiness", "context"),
            "portfolio_suggestions", at(i, "ai_readiness", "ai_portfolio_suggestions"))});
        List<WarehouseCourse.IntelligenceSection> out = new ArrayList<>();
        for (Object[] d : definitions) {
            List<String> items = readable((JsonNode) d[2], 0).stream().limit(4).map(t -> t.length() > 300 ? t.substring(0, 300) : t).toList();
            if (!items.isEmpty()) out.add(new WarehouseCourse.IntelligenceSection((String) d[0], (String) d[1], items));
        }
        return out;
    }

    /** A JS object literal whose missing values stay as (empty) keys. */
    private static ObjectNode object(Object... kv) {
        ObjectNode o = WarehouseText.MAPPER.createObjectNode();
        for (int k = 0; k < kv.length; k += 2) {
            JsonNode v = (JsonNode) kv[k + 1];
            o.set((String) kv[k], v.isMissingNode() ? NullNode.getInstance() : v);
        }
        return o;
    }

    private List<String> readable(JsonNode v, int depth) {
        if (depth > 2 || v == null || v.isMissingNode() || v.isNull()) return List.of();
        List<String> out = new ArrayList<>();
        if (v.isArray()) {
            for (int k = 0; k < Math.min(4, v.size()); k++) out.addAll(readable(v.get(k), depth + 1));
            return out;
        }
        if (v.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            v.fields().forEachRemaining(e -> { if (!ADMIN_KEY.matcher(e.getKey()).find()) entries.add(e); });
            for (var e : entries.stream().limit(4).toList()) for (String t : readable(e.getValue(), depth + 1)) out.add(WarehouseText.label(e.getKey()) + ": " + t);
            return out;
        }
        if (v.isBoolean()) return List.of(v.booleanValue() ? "Yes" : "No");
        String t = cleanText(v, 240);
        return t == null ? List.of() : List.of(t);
    }

    // ---- comparison.ts -------------------------------------------------------------------------

    public WarehouseComparison buildComparison(List<WarehouseCourse> courses, List<ProviderMatch> providerMatches,
                                                List<WarehouseComparison.Qualification> qualifications, boolean courseRequested) {
        Map<String, ProviderMatch.ProviderRecord> byId = new LinkedHashMap<>();
        for (ProviderMatch m : providerMatches) if ("MATCHED".equals(m.getStatus())) for (var p : m.getProviders()) byId.put(p.getId(), p);
        List<ProviderMatch.ProviderRecord> providers = new ArrayList<>(byId.values());
        java.util.function.Function<WarehouseCourse, ProviderMatch.ProviderRecord> provider = c -> providers.stream()
            .filter(p -> p.getId().equals(c.getProviderId()) || p.getName().equals(c.getProvider())).findFirst().orElse(null);
        List<WarehouseComparison.Option> options = new ArrayList<>();
        if (!courses.isEmpty()) {
            for (var c : courses) options.add(new WarehouseComparison.Option(c.getId(), c.getProvider(),
                java.util.stream.Stream.of(c.getName(), c.getCode()).filter(x -> x != null && !x.isEmpty()).collect(Collectors.joining(" · "))));
        } else {
            for (var p : providers) options.add(new WarehouseComparison.Option(p.getId(), p.getName(), p.getRtoCode() != null && !p.getRtoCode().isEmpty() ? "RTO " + p.getRtoCode() : "Provider"));
        }
        List<String> codes = courses.stream().map(WarehouseCourse::getCode).filter(c -> c != null && !c.isEmpty()).distinct().toList();
        boolean same = courses.size() > 1 && codes.size() == 1 && courses.stream().allMatch(c -> codes.get(0).equals(c.getCode()));
        List<WarehouseComparison.Row> rows = new ArrayList<>();
        if (!courses.isEmpty()) {
            addRow(rows, "duration", "Duration", "COURSE_RECORD", courses.stream().map(c -> single(c.getDuration())).toList(), "Compare the time commitment alongside study load and attendance; a shorter course is not automatically better.");
            addRow(rows, "delivery", "How and where you study", "COURSE_RECORD", courses.stream().map(c -> { List<String> v = new ArrayList<>(c.getDelivery()); v.addAll(c.getLocations().stream().limit(4).toList()); return v; }).toList(), "Use the course locations shown. Other campuses do not establish delivery at that campus.");
            addRow(rows, "attendance", "Attendance and practical setup", "YUZEE_ANALYSIS", courses.stream().map(c -> c.getComparisonDetails() == null ? null : c.getComparisonDetails().getAttendance()).toList(), "Consider whether the recorded attendance pattern fits your work and other commitments.");
            addRow(rows, "assessment", "Assessment approach", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getAssessments).toList(), "Look at how learning can be demonstrated. Broad assessment descriptions may be shared across the qualification.");
            addRow(rows, "practice", "Practical experience", "YUZEE_ANALYSIS", courses.stream().map(c -> c.getComparisonDetails() == null ? null : c.getComparisonDetails().getPractice()).toList(), "Separate a recorded placement arrangement from Yuzee suggestions for building practical evidence.");
            addRow(rows, "support", "Learner support", "PROVIDER_RECORD", courses.stream().map(c -> { var p = provider.apply(c); return p == null ? null : p.getSupport(); }).toList(), "Recorded provider services may help you study; the level of support for this course is not established by a tick alone.");
            addRow(rows, "credit", "Credit and recognition", "YUZEE_ANALYSIS", courses.stream().map(c -> c.getComparisonDetails() == null ? null : c.getComparisonDetails().getCredit()).toList(), "Recognition and credit depend on your evidence and the provider decision.");
            addRow(rows, "cost", "Recorded tuition", "COURSE_RECORD", courses.stream().map(c -> {
                List<String> v = new ArrayList<>();
                if (c.getFees().getDomestic() != null) v.add("Domestic: " + c.getFees().getDomestic());
                if (c.getFees().getInternational() != null) v.add("International: " + c.getFees().getInternational());
                return v;
            }).toList(), "Compare like student categories and funding conditions. Not supplied never means free.");
            addRow(rows, "strengths", "Who it may suit", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getBestFor).toList(), "Use Yuzee analysis against your priorities; this is not an established provider advantage.");
            addRow(rows, "limits", "Trade-offs and further learning", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getConsiderations).toList(), "Common qualification limits apply to all comparable options; do not treat them as a weakness unique to one RTO.");
        } else {
            addRow(rows, "area", "Provider base", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getArea())).toList(), "An institution address does not establish where a particular course runs.");
            addRow(rows, "type", "Provider type", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getType())).toList(), "Provider type describes the institution, not a ranking of teaching quality.");
            addRow(rows, "support", "Learner support", "PROVIDER_RECORD", providers.stream().map(ProviderMatch.ProviderRecord::getSupport).toList(), "Use these recorded services as discussion points for your study needs.");
            addRow(rows, "about", "Provider overview", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getDescription())).toList(), "Compare the stated focus without treating description length as quality.");
        }
        List<String> notes = new ArrayList<>();
        if (courseRequested) for (var p : providers) {
            if (courses.stream().noneMatch(c -> p.getId().equals(c.getProviderId()) || p.getName().equals(c.getProvider())))
                notes.add("No matching course was returned for " + p.getName() + "; their provider details alone do not establish that they offer the requested course.");
        }
        for (var c : courses) if (c.getEvidenceIssues() != null && !c.getEvidenceIssues().isEmpty()) notes.add(c.getEvidenceIssues().get(0));
        WarehouseComparison out = new WarehouseComparison();
        out.setNotes(notes);
        out.setTitle(!courses.isEmpty() ? "Compare the learning experience" : "Compare these providers");
        out.setOptions(options);
        out.setRows(rows);
        out.setProviderMatches(providerMatches);
        out.setQualifications(qualifications);
        out.setBaseline(same ? "These options share " + codes.get(0) + ". The national qualification is the common starting point; compare the recorded delivery and learner experience below."
            : courses.size() > 1 ? "These records are not all the same qualification. Compare level and intended outcome before interpreting differences as provider quality."
            : "Use the available provider details now. A named qualification enables a more specific comparison of learning and delivery.");
        return out;
    }

    private List<String> single(String v) { return v == null || v.isEmpty() ? List.of() : List.of(v); }

    private static final Pattern JS_SPACES = com.yuzee.tokenlab.service.RoutingPolicyService.jsRegex("\\s+", false);

    private void addRow(List<WarehouseComparison.Row> rows, String key, String label, String basis, List<List<String>> values, String meaning) {
        List<List<String>> cells = values.stream().map(v -> v == null ? List.<String>of() : v.stream().filter(x -> x != null && !x.isEmpty()).toList()).toList();
        List<String> canonical = cells.stream().map(v -> v.stream().map(x -> JS_SPACES.matcher(com.yuzee.tokenlab.service.RoutingPolicyService.jsTrim(x).toLowerCase(java.util.Locale.ROOT)).replaceAll(" ")).sorted().collect(Collectors.joining("|"))).toList();
        String status = cells.stream().allMatch(List::isEmpty) ? "UNKNOWN" : cells.stream().anyMatch(List::isEmpty) ? "INCOMPLETE"
            : new HashSet<>(canonical).size() == 1 && cells.size() > 1 ? "SHARED" : "DIFFERENT_RECORDS";
        WarehouseComparison.Row row = new WarehouseComparison.Row();
        row.setKey(key); row.setLabel(label); row.setBasis(basis); row.setStatus(status); row.setValues(cells); row.setMeaning(meaning);
        rows.add(row);
    }

    // ---- choices.ts ----------------------------------------------------------------------------

    public record ChoiceResult(ExplorationChoice choice, String text) {}

    public ChoiceResult explorationChoice(WarehousePack pack, List<String> roleIds, List<ExplorationChoice.SkillState> requested) {
        WarehouseExploration e = pack == null || pack.getConnected() == null ? null : pack.getConnected().getExploration();
        if (e == null || roleIds == null || requested == null || roleIds.size() > 3 || requested.size() > 12) throw new IllegalArgumentException("Choose roles and skills shown in this workspace.");
        if (new HashSet<>(roleIds).size() != roleIds.size()
            || requested.stream().map(sk -> sk == null ? null : sk.getId()).collect(Collectors.toCollection(HashSet::new)).size() != requested.size())
            throw new IllegalArgumentException("Choose each role or skill once.");
        List<WarehouseExploration.Role> roles = new ArrayList<>();
        for (String id : roleIds) roles.add(e.getRoles().stream().filter(x -> x.getId().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Choose a role shown in this workspace.")));
        List<ExplorationChoice.SkillState> skills = new ArrayList<>();
        for (var sk : requested) {
            var skill = sk == null ? null : e.getSkills().stream().filter(k -> k.getId().equals(sk.getId())).findFirst().orElse(null);
            if (skill == null || !Set.of("HAVE", "LEARN", "UNSURE").contains(sk.getState())) throw new IllegalArgumentException("Choose a skill shown in this workspace.");
            skills.add(new ExplorationChoice.SkillState(skill.getId(), skill.getName(), sk.getState()));
        }
        ExplorationChoice choice = new ExplorationChoice();
        choice.setRoleIds(roles.stream().map(WarehouseExploration.Role::getId).toList());
        choice.setSkills(skills);
        List<String> phrases = new ArrayList<>();
        phrases.add(!roles.isEmpty() ? "I want to explore these roles: " + roles.stream().map(WarehouseExploration.Role::getTitle).collect(Collectors.joining("; ")) + "." : "I have not selected a target role yet.");
        String[][] labels = {{"HAVE", "I say I have experience using"}, {"LEARN", "I want to learn"}, {"UNSURE", "I am unsure about my experience with"}};
        for (String[] l : labels) {
            List<String> names = skills.stream().filter(sk -> l[0].equals(sk.getState())).map(ExplorationChoice.SkillState::getName).toList();
            if (!names.isEmpty()) phrases.add(l[1] + ": " + String.join("; ", names) + ".");
        }
        return new ChoiceResult(choice, String.join(" ", phrases) + " These replace my previous workspace skill selections. Unmarked skills remain unknown. My reported skills are not verified competence. Help me connect the work, relevant learning options and the recorded demand in my area.");
    }

    /** choices.ts workspaceCourses(): pack courses plus the learning links' course references. */
    public List<Object> workspaceCourses(WarehousePack pack) {
        List<Object> out = new ArrayList<>();
        if (pack == null) return out;
        out.addAll(pack.getCourses());
        if (pack.getConnected() != null && pack.getConnected().getExploration() != null)
            for (var l : pack.getConnected().getExploration().getLearning()) out.addAll(l.getCourses());
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Raw column value as JS would put it in JSON text: null stays null, numbers lose ".0". */
    private static String str(Object v) { return v == null ? null : jsString(v); }

    /** {@code [a,b,...].filter(Boolean).join(', ')}. */
    private static String joinTruthy(Object... values) {
        return Arrays.stream(values).filter(WarehouseText::truthy).map(WarehouseText::jsString).collect(Collectors.joining(", "));
    }

    private static <T> List<T> limit(List<T> v, int n) { return v == null ? List.of() : v.stream().limit(n).toList(); }

    private static List<String> likeWords(String phrase) {
        return Arrays.stream(text(phrase, 100).split("\\s+")).filter(w -> !w.isEmpty()).limit(4).toList();
    }

    private static String likeWhere(String column, List<String> words) {
        return words.stream().map(w -> column + " LIKE ? ESCAPE '\\'").collect(Collectors.joining(" AND "));
    }

    private static Object[] likeArgs(List<String> words) {
        return words.stream().map(w -> "%" + WarehouseText.safeLike(w) + "%").toArray();
    }

    private static Map<String, Object> metrics(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    // ---- linked-data.cjs -----------------------------------------------------------------------

    private static final class Location {
        String requested = "";
        Map<String, Object> region;
        List<Map<String, Object>> candidates = List.of();
        List<Map<String, Object>> chain = List.of();

        String regionKey() { return region == null ? null : str(region.get("region_key")); }
        String regionState() { return region == null ? null : str(region.get("state")); }
        String regionTier() { return region == null ? null : str(region.get("tier")); }
    }

    private static WarehouseConnections.RegionRef regionRef(Map<String, Object> r) {
        return new WarehouseConnections.RegionRef(str(r.get("region_key")), str(r.get("name")), str(r.get("tier")), str(r.get("state")));
    }

    private final class Linked {
        final Session s;
        final WarehouseQueryPlan plan;
        final Set<String> facets;
        final boolean all;
        final List<WarehouseConnections.Career> careers = new ArrayList<>();
        final Map<String, WarehouseConnections.Career> careerMap = new HashMap<>();
        final List<WarehouseConnections.Signal> signals = new ArrayList<>();
        final Set<String> signalIds = new HashSet<>();
        final List<WarehouseConnections.Industry> industries = new ArrayList<>();
        final List<WarehouseConnections.Relationship> relationships = new ArrayList<>();

        Linked(Session s, WarehouseQueryPlan plan) {
            this.s = s;
            this.plan = plan;
            this.facets = new HashSet<>(plan.getFacets() == null ? List.of() : plan.getFacets());
            this.all = !plan.hasExplicitFacets();
        }

        boolean has(String facet) { return all || facets.contains(facet); }

        Map<String, Object> spine(Object key) { return s.get("region_spine", "SELECT * FROM region_spine WHERE region_key=?", key); }

        Location resolveLocation(WarehouseQueryPlan.LocationQuery input) {
            String name = text(input == null ? null : input.getName(), 100);
            String postcode = input != null && input.getPostcode() != null && input.getPostcode().matches("\\d{4}") ? input.getPostcode() : "";
            String state = input != null && STATES.contains(input.getState()) ? input.getState() : "";
            Location out = new Location();
            if (name.isEmpty() && postcode.isEmpty() && state.isEmpty()) return out;
            List<Map<String, Object>> matches = new ArrayList<>();
            if (!name.isEmpty()) matches = new ArrayList<>(state.isEmpty()
                ? s.query("region_spine", "SELECT * FROM region_spine WHERE lower(name)=lower(?) LIMIT 6", name)
                : s.query("region_spine", "SELECT * FROM region_spine WHERE lower(name)=lower(?) AND state=? LIMIT 6", name, state));
            if (matches.isEmpty() && !postcode.isEmpty()) {
                var p = state.isEmpty()
                    ? s.query("dim_location_asgs", "SELECT DISTINCT sa2_code,sa4_code,state_code FROM dim_location_asgs WHERE postcode=? LIMIT 6", postcode)
                    : s.query("dim_location_asgs", "SELECT DISTINCT sa2_code,sa4_code,state_code FROM dim_location_asgs WHERE postcode=? AND state_code=? LIMIT 6", postcode, state);
                for (var r : p) {
                    var m = spine(or(r.get("sa2_code"), r.get("sa4_code")));
                    if (m != null && matches.stream().noneMatch(x -> String.valueOf(x.get("region_key")).equals(String.valueOf(m.get("region_key"))))) matches.add(m);
                }
            }
            if (matches.isEmpty() && !name.isEmpty()) matches = new ArrayList<>(state.isEmpty()
                ? s.query("region_spine", "SELECT * FROM region_spine WHERE name LIKE ? ESCAPE '\\' LIMIT 6", WarehouseText.safeLike(name) + " -%")
                : s.query("region_spine", "SELECT * FROM region_spine WHERE name LIKE ? ESCAPE '\\' AND state=? LIMIT 6", WarehouseText.safeLike(name) + " -%", state));
            if (name.isEmpty() && postcode.isEmpty() && !state.isEmpty())
                matches = new ArrayList<>(s.query("region_spine", "SELECT * FROM region_spine WHERE tier='STATE' AND (state=? OR region_key=?) LIMIT 2", state, state));
            // A town, district and wider region may share a name. Prefer the exact local
            // node only when every other match is its ancestor, never across distinct places.
            if (matches.size() > 1) {
                var locals = matches.stream().filter(r -> "SA2".equals(r.get("tier"))).toList();
                if (locals.size() == 1) {
                    Set<String> ancestorKeys = new HashSet<>();
                    Map<String, Object> parent = locals.get(0);
                    while (parent != null && ancestorKeys.size() < 6 && !ancestorKeys.contains(str(parent.get("region_key")))) {
                        ancestorKeys.add(str(parent.get("region_key")));
                        parent = truthy(parent.get("parent_key")) ? spine(parent.get("parent_key")) : null;
                    }
                    if (matches.stream().allMatch(r -> ancestorKeys.contains(str(r.get("region_key"))))) matches = new ArrayList<>(locals);
                }
            }
            Map<String, Object> region = matches.size() == 1 ? matches.get(0) : null;
            List<Map<String, Object>> chain = new ArrayList<>();
            Map<String, Object> node = region;
            while (node != null && chain.size() < 5) {
                String key = str(node.get("region_key"));
                if (chain.stream().anyMatch(r -> java.util.Objects.equals(str(r.get("region_key")), key))) break;
                chain.add(node);
                node = truthy(node.get("parent_key")) ? spine(node.get("parent_key")) : null;
            }
            out.requested = joinTruthy(name, postcode, state);
            out.region = region;
            out.candidates = matches.size() > 1 ? matches : List.of();
            out.chain = chain;
            return out;
        }

        WarehouseConnections.LocalOverview localOverview(Location location) {
            Map<String, Object> region = location.region;
            if (region == null) return null;
            String regionKey = location.regionKey(), tier = location.regionTier();
            WarehouseConnections.LocalOverview o = new WarehouseConnections.LocalOverview();
            o.setAncestors(location.chain.stream().filter(r -> !java.util.Objects.equals(str(r.get("region_key")), regionKey) && Set.of("SA3", "SA4").contains(str(r.get("tier"))))
                .map(r -> new WarehouseConnections.Ancestor(text(r.get("name")), str(r.get("tier")))).toList());
            for (var r : location.chain.stream().filter(x -> Set.of("SA2", "SA3", "SA4").contains(str(x.get("tier")))).toList()) {
                var p = s.get("abs_region_profile", "SELECT region_name,region_type,population,remoteness_area,source_year,source_name FROM abs_region_profile WHERE region_key=? ORDER BY source_year DESC LIMIT 1", r.get("region_key"));
                if (p != null) {
                    WarehouseConnections.Profile profile = new WarehouseConnections.Profile();
                    profile.setArea(text(p.get("region_name")));
                    profile.setScope(text(p.get("region_type")));
                    profile.setPopulation(number(p.get("population")));
                    profile.setSetting(text(p.get("remoteness_area")));
                    profile.setPeriod(jsString(or(p.get("source_year"), "")));
                    profile.setSource(text(p.get("source_name")));
                    o.setProfile(profile);
                    break;
                }
            }
            String column = tier == null ? null : switch (tier) { case "SA2" -> "sa2_code"; case "SA3" -> "sa3_code"; case "SA4" -> "sa4_code"; default -> null; };
            List<Map<String, Object>> organisations = List.of();
            if (column != null) {
                String sql = "SELECT id,name,organisation_type,suburb,state,source,last_verified_at,updated_at,gemini_proposed FROM local_market_organisations WHERE (" + column + "=?"
                    + ("SA2".equals(tier) ? " OR (lower(suburb)=lower(?) AND state=?)" : "") + ") AND coalesce(gemini_proposed,0)=0 ORDER BY lower(name),updated_at DESC LIMIT 251";
                organisations = "SA2".equals(tier)
                    ? s.query("local_market_organisations", sql, region.get("region_key"), region.get("name"), region.get("state"))
                    : s.query("local_market_organisations", sql, region.get("region_key"));
            }
            Map<String, String[]> categories = Map.ofEntries(
                Map.entry("school", new String[]{"learning", "Learning and training"}), Map.entry("rto", new String[]{"learning", "Learning and training"}),
                Map.entry("university", new String[]{"learning", "Learning and training"}), Map.entry("tafe", new String[]{"learning", "Learning and training"}),
                Map.entry("employment_service", new String[]{"employment", "Help getting into work"}), Map.entry("community_centre", new String[]{"community", "Community activities"}),
                Map.entry("sport_club", new String[]{"community", "Community activities"}), Map.entry("library", new String[]{"community", "Community activities"}),
                Map.entry("support_service", new String[]{"support", "Community support"}), Map.entry("employer", new String[]{"business", "Recorded businesses"}),
                Map.entry("company", new String[]{"business", "Recorded businesses"}), Map.entry("business", new String[]{"business", "Recorded businesses"}));
            List<String> typeOrder = List.of("community_centre", "rto", "university", "tafe", "employment_service", "library", "sport_club", "school", "support_service", "employer", "company", "business");
            List<Map<String, Object>> sorted = new ArrayList<>(organisations.stream().limit(250).toList());
            sorted.sort(Comparator.<Map<String, Object>>comparingInt(a -> typeOrder.indexOf(str(a.get("organisation_type"))))
                .thenComparing((a, b) -> COLLATOR.compare(jsString(a.get("name")), jsString(b.get("name")))));
            Set<String> seen = new HashSet<>();
            Map<String, WarehouseConnections.CommunityGroup> groups = new HashMap<>();
            for (var org : sorted) {
                String[] category = categories.get(str(org.get("organisation_type")));
                if (category == null || text(org.get("name")).isEmpty()) continue;
                String key = java.util.stream.Stream.of(org.get("name"), org.get("organisation_type"), org.get("suburb"), org.get("state"))
                    .map(v -> text(v).toLowerCase(java.util.Locale.ROOT)).collect(Collectors.joining("|"));
                if (!seen.add(key)) continue;
                var group = groups.computeIfAbsent(category[0], k -> { var g = new WarehouseConnections.CommunityGroup(); g.setKey(category[0]); g.setLabel(category[1]); return g; });
                group.setRecordedCount(group.getRecordedCount() + 1);
                if (group.getExamples().size() < 4) {
                    WarehouseConnections.Example ex = new WarehouseConnections.Example();
                    ex.setName(text(org.get("name")));
                    ex.setType(text(org.get("organisation_type")));
                    ex.setSource(text(org.get("source")));
                    ex.setUpdatedAt(text(or(org.get("last_verified_at"), org.get("updated_at"))));
                    ex.setArea(!text(org.get("suburb")).isEmpty() ? text(org.get("suburb")) : str(region.get("name")));
                    group.getExamples().add(ex);
                }
            }
            o.setEvidenceId("warehouse_local_overview_" + jsString(region.get("region_key")));
            o.setArea(str(region.get("name")));
            o.setState(str(region.get("state")));
            o.setScope(str(region.get("tier")));
            for (String k : List.of("community", "learning", "employment", "support", "business")) if (groups.containsKey(k)) o.getCommunity().add(groups.get(k));
            o.setLimited(organisations.size() > 250);
            return o;
        }

        void addSignal(String id, WarehouseConnections.Signal sig) {
            if (!signalIds.contains(id) && signals.size() < 18) {
                signalIds.add(id);
                sig.setId(id);
                sig.setEvidenceId("warehouse_" + id);
                signals.add(sig);
            }
        }

        void addCareer(Object rawCode, Object title, WarehouseConnections.CourseLink link) {
            String code = truthy(rawCode) ? jsString(rawCode) : "";
            if (link == null && careers.stream().anyMatch(c -> code.equals(c.getGroupCode()))) return;
            if (code.isEmpty() || careerMap.size() >= 6 && !careerMap.containsKey(code)) return;
            WarehouseConnections.Career c = careerMap.get(code);
            if (c == null) {
                var exact = s.get("canonical_occupation", "SELECT * FROM canonical_occupation WHERE anzsco_code=?", code);
                var group = exact == null && code.length() > 4 ? s.get("canonical_occupation", "SELECT * FROM canonical_occupation WHERE anzsco_code=?", code.substring(0, 4)) : null;
                var canonical = exact != null ? exact : group;
                String key = canonical != null && truthy(canonical.get("anzsco_code")) ? jsString(canonical.get("anzsco_code")) : code;
                var onet = s.tables.contains("onet_occupation") ? s.get("onet_anzsco_crosswalk",
                    "SELECT x.job_id,x.method,x.confidence,o.job_title,o.description,o.tasks,o.work_styles FROM onet_anzsco_crosswalk x JOIN onet_occupation o ON o.job_id=x.job_id WHERE x.anzsco_code=? ORDER BY x.confidence DESC,o.job_title ASC LIMIT 2", key) : null;
                c = new WarehouseConnections.Career();
                c.setId(code);
                c.setEvidenceId("warehouse_career_" + code);
                c.setTitle(text(or(exact == null ? null : exact.get("anzsco_title"), title, canonical == null ? null : canonical.get("anzsco_title"), code)));
                c.setDescription(text(or(exact == null ? null : exact.get("description"), onet == null ? null : onet.get("description"))));
                c.setTasks(linkedList(onet == null ? null : onet.get("tasks"), 5));
                c.setWorkStyles(linkedList(onet == null ? null : onet.get("work_styles"), 3));
                c.setSkills(onet == null ? List.of() : s.query("onet_job_skill", "SELECT skill_name FROM onet_job_skill WHERE job_id=? LIMIT 6", onet.get("job_id")).stream().map(r -> text(r.get("skill_name"))).toList());
                c.setProfileScope(group != null ? "Occupation-group context: " + jsString(group.get("anzsco_title")) + " (" + key + ")" : "Occupation profile");
                c.setProfileMethod(text(or(onet == null ? null : onet.get("method"), canonical == null ? null : canonical.get("source"))));
                c.setProfileSource(onet != null ? "O*NET occupation profile and stored ANZSCO crosswalk" : text(canonical == null ? null : canonical.get("source")));
                c.setGroupCode(key);
                careerMap.put(code, c);
                careers.add(c);
            }
            if (link != null && c.getCourseLinks().stream().noneMatch(x -> x.getCourseId().equals(link.getCourseId()))) {
                c.getCourseLinks().add(link);
                relationships.add(new WarehouseConnections.Relationship("course:" + link.getCourseId(), "career:" + code, "related_career", link.getMethod(), link.getConfidence()));
            }
        }

        WarehouseConnections read(List<Map<String, Object>> courses) {
            Location location = resolveLocation(plan.getLocation());
            List<WarehouseConnections.ProviderProfile> providers = new ArrayList<>();
            WarehouseExploration exploration = new Opportunities(s).read(plan, location);
            for (var course : courses) {
                String id = jsString(course.get("id"));
                Object rawCode = or(course.get("national_code"), course.get("course_code"), "");
                String code = truthy(rawCode) ? jsString(rawCode) : "";
                if (has("PROVIDER") || has("LOCAL") || has("FUNDING")) {
                    var p = s.get("live_institutions", "SELECT id,legal_name,rto_code,rto_type,higher_education_code,city,state,website_url,has_student_support,has_disability_support,has_library,has_apprenticeships FROM live_institutions WHERE id=?",
                        course.get("institution_id") == null ? "" : course.get("institution_id"));
                    String pid = p == null ? null : jsString(p.get("id"));
                    Object rto = p == null ? null : or(p.get("rto_code"), "");
                    var existing = p == null ? null : providers.stream().filter(x -> x.getId().equals(pid)).findFirst().orElse(null);
                    if (p != null && existing == null) {
                        WarehouseConnections.ProviderProfile pr = new WarehouseConnections.ProviderProfile();
                        pr.setId(pid);
                        pr.setEvidenceId("warehouse_provider_" + pid);
                        pr.setName(text(p.get("legal_name")));
                        pr.setType(text(p.get("rto_type")));
                        pr.setHigherEducationCode(text(p.get("higher_education_code")));
                        pr.setCity(text(p.get("city")));
                        pr.setState(text(p.get("state")));
                        for (var r : s.query("institution_campuses", "SELECT location_name,town,state,postcode FROM institution_campuses WHERE rto_code=? LIMIT 8", rto)) {
                            WarehouseConnections.Campus campus = new WarehouseConnections.Campus();
                            campus.setName(text(r.get("location_name")));
                            campus.setTown(text(r.get("town")));
                            campus.setState(text(r.get("state")));
                            campus.setPostcode(text(r.get("postcode")));
                            pr.getCampuses().add(campus);
                        }
                        List<String> support = new ArrayList<>();
                        if (strictOne(p.get("has_student_support"))) support.add("Student support");
                        if (strictOne(p.get("has_disability_support"))) support.add("Disability support");
                        if (strictOne(p.get("has_library"))) support.add("Library");
                        if (strictOne(p.get("has_apprenticeships"))) support.add("Apprenticeship support");
                        pr.setSupport(support);
                        var he = s.get("he_provider_entitlement_v2", "SELECT csp_undergraduate,csp_postgraduate,hecs_help,fee_help,effective_from,effective_to,current_status FROM he_provider_entitlement_v2 WHERE institution_id=? ORDER BY effective_from DESC LIMIT 1", p.get("id"));
                        if (he != null) pr.setHigherEducationFunding(metrics("cspUndergraduate", WarehouseText.flag(he.get("csp_undergraduate")), "cspPostgraduate", WarehouseText.flag(he.get("csp_postgraduate")),
                            "hecsHelp", WarehouseText.flag(he.get("hecs_help")), "feeHelp", WarehouseText.flag(he.get("fee_help")), "from", he.get("effective_from"), "to", he.get("effective_to"), "status", text(he.get("current_status"))));
                        pr.getCourseIds().add(id);
                        providers.add(pr);
                        existing = pr;
                    } else if (existing != null) existing.getCourseIds().add(id);
                    if (p != null && has("FUNDING") && !code.isEmpty()) {
                        String state = location.regionState();
                        var funding = truthy(state)
                            ? s.query("course_funding_verdict", "SELECT scheme,delivery_state,funding_status,student_tuition_out_of_pocket,currency,effective_period FROM course_funding_verdict WHERE rto_code=? AND national_code=? AND delivery_state=? ORDER BY effective_period DESC LIMIT 3", rto, code, state)
                            : s.query("course_funding_verdict", "SELECT scheme,delivery_state,funding_status,student_tuition_out_of_pocket,currency,effective_period FROM course_funding_verdict WHERE rto_code=? AND national_code=? ORDER BY effective_period DESC LIMIT 3", rto, code);
                        for (var f : funding) {
                            Map<String, Object> entry = new LinkedHashMap<>(f);
                            entry.put("courseId", id);
                            entry.put("courseName", text(course.get("course_name")));
                            existing.getFunding().add(entry);
                        }
                    }
                    if (p != null) relationships.add(new WarehouseConnections.Relationship("course:" + id, "provider:" + pid, "offered_by", "institution_id", null));
                }
                if (has("CAREERS") || has("LOCAL") || has("INDUSTRY")) {
                    List<Map<String, Object>> edges = new ArrayList<>(s.query("course_occupation_edge", "SELECT anzsco_code,anzsco_title,method,confidence_score FROM course_occupation_edge WHERE national_code=? ORDER BY is_primary DESC,confidence_score DESC LIMIT 3", code));
                    edges.addAll(s.index.queryForList("SELECT occupation_code AS anzsco_code,occupation_name AS anzsco_title,match_method AS method,match_confidence AS confidence_score FROM occupation_links WHERE qualification_code=? ORDER BY is_primary DESC LIMIT 3", code));
                    for (var edge : edges) addCareer(or(edge.get("anzsco_code"), ""), edge.get("anzsco_title"),
                        new WarehouseConnections.CourseLink(id, text(course.get("course_name")), text(edge.get("method")), number(edge.get("confidence_score"))));
                    var profile = s.indexGet("SELECT * FROM profile_links WHERE course_id=? LIMIT 1", id);
                    for (String occ : linkedList(profile == null ? null : profile.get("anzsco_codes_json"), 3))
                        addCareer(occ, "", new WarehouseConnections.CourseLink(id, text(course.get("course_name")), text(profile.get("mapping_method")), number(profile.get("mapping_confidence"))));
                    if (has("INDUSTRY")) for (String name : linkedList(profile == null ? null : profile.get("industry_codes_json"), 4)) {
                        if (industries.stream().anyMatch(x -> name.equals(x.getName()) && id.equals(x.getCourseId()))) continue;
                        WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                        ind.setId(id + "_" + industries.size());
                        ind.setEvidenceId("warehouse_industry_" + id + "_" + industries.size());
                        ind.setName(name);
                        ind.setCourseId(id);
                        ind.setMethod(text(profile.get("mapping_method")));
                        ind.setScope("Course-to-industry mapping");
                        industries.add(ind);
                    }
                }
            }
            for (String phrase : limit(plan.getOccupationQueries(), 2)) {
                List<String> words = likeWords(phrase);
                if (words.isEmpty()) continue;
                for (var row : s.query("canonical_occupation", "SELECT anzsco_code,anzsco_title FROM canonical_occupation WHERE " + likeWhere("anzsco_title", words) + " LIMIT 3", likeArgs(words)))
                    addCareer(row.get("anzsco_code"), row.get("anzsco_title"), null);
            }
            for (String phrase : limit(plan.getIndustryQueries(), 2)) {
                List<String> words = likeWords(phrase);
                if (words.isEmpty()) continue;
                for (var r : s.index.queryForList("SELECT * FROM industry_links WHERE " + likeWhere("industry_name", words) + " LIMIT 8", likeArgs(words))) {
                    if (industries.stream().noneMatch(x -> java.util.Objects.equals(x.getName(), str(r.get("industry_name"))))) {
                        WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                        ind.setId("mapped_" + jsString(r.get("course_id")));
                        ind.setEvidenceId("warehouse_industry_mapped_" + jsString(r.get("course_id")));
                        ind.setName(str(r.get("industry_name")));
                        ind.setMethod(str(r.get("mapping_method")));
                        ind.setScope("Stored course-to-industry mapping");
                        industries.add(ind);
                    }
                    var profile = s.indexGet("SELECT * FROM profile_links WHERE course_id=? LIMIT 1", r.get("course_id"));
                    if (courses.isEmpty()) {
                        for (String code : linkedList(profile == null ? null : profile.get("anzsco_codes_json"), 3)) addCareer(code, "", null);
                        for (var occ : s.index.queryForList("SELECT occupation_code,occupation_name FROM occupation_links WHERE qualification_code=? ORDER BY is_primary DESC LIMIT 2", r.get("qualification_code")))
                            addCareer(occ.get("occupation_code"), occ.get("occupation_name"), null);
                    }
                }
                for (var r : s.query("dim_industry", "SELECT anzsic_code,industry_name FROM dim_industry WHERE " + likeWhere("industry_name", words) + " LIMIT 3", likeArgs(words))) {
                    WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                    ind.setId("sector_" + jsString(r.get("anzsic_code")));
                    ind.setEvidenceId("warehouse_industry_sector_" + jsString(r.get("anzsic_code")));
                    ind.setName(text(r.get("industry_name")));
                    ind.setMethod("Named industry match");
                    ind.setScope("Industry catalogue");
                    industries.add(ind);
                    if (courses.isEmpty()) for (var link : s.query("dim_crosswalk_anzsic_anzsco", "SELECT anzsco_code FROM dim_crosswalk_anzsic_anzsco WHERE anzsic_code=? LIMIT 3", r.get("anzsic_code")))
                        addCareer(link.get("anzsco_code"), "", null);
                }
            }
            if (exploration != null) for (var role : exploration.getRoles()) for (var map : limit(role.getMappings(), 1)) addCareer(map.getAnzscoCode(), map.getAnzscoTitle(), null);
            List<Map<String, Object>> chain = location.chain.stream().filter(r -> !"NATIONAL".equals(str(r.get("tier")))).toList();
            // An area-only demand question starts from recorded occupation/region edges.
            // Do not use this fallback to replace a named skill or career with unrelated work.
            if (has("LOCAL") && careers.isEmpty() && size(plan.getOccupationQueries()) + size(plan.getSkillQueries()) + size(plan.getRoleQueries()) + size(plan.getJobQueries()) == 0) {
                for (var region : chain) {
                    var rows = s.query("region_demand_edge", "SELECT * FROM region_demand_edge WHERE region_key=? AND period=(SELECT MAX(period) FROM region_demand_edge WHERE region_key=?) AND active_jobs>0 ORDER BY active_jobs DESC LIMIT 5", region.get("region_key"), region.get("region_key"));
                    for (var r : rows) addCareer(r.get("anzsco_code"), text(linkedJson(r.get("evidence_json")).path("archetype")), null);
                    if (!rows.isEmpty()) break;
                }
            }
            Map<String, Object> sa4 = chain.stream().filter(r -> "SA4".equals(str(r.get("tier")))).findFirst().orElse(null);
            for (var career : careers) {
                if (has("LOCAL") || has("CAREERS")) {
                    boolean found = false;
                    for (var region : chain) {
                        var r = s.get("region_demand_edge", "SELECT * FROM region_demand_edge WHERE region_key=? AND anzsco_code=? ORDER BY period DESC LIMIT 1", region.get("region_key"), career.getGroupCode());
                        if (r != null) {
                            var sig = signal("RECORDED_DEMAND", career.getId(), "Recorded demand for " + career.getTitle(),
                                jsString(r.get("active_jobs")) + " recorded job advertisements; " + jsString(r.get("employer_count")) + " employers in the stored signal.",
                                str(or(r.get("scope"), region.get("tier"))), str(region.get("name")), str(r.get("period")), str(r.get("source_name")), str(r.get("method")),
                                java.util.Objects.equals(str(region.get("region_key")), location.regionKey()), str(r.get("updated_at")));
                            sig.setMetrics(metrics("advertisements", number(r.get("active_jobs")), "employers", number(r.get("employer_count"))));
                            addSignal("demand_" + jsString(r.get("edge_id")), sig);
                            found = true;
                            break;
                        }
                    }
                    if (!found && truthy(location.regionState())) {
                        var r = s.get("ivi_monthly_sa4", "SELECT * FROM ivi_monthly_sa4 WHERE sa4_code=? AND anzsco_code=? ORDER BY period DESC LIMIT 1", sa4 == null ? "" : or(sa4.get("region_key"), ""), career.getGroupCode());
                        if (r != null) {
                            var sig = signal("RECORDED_DEMAND", career.getId(), "Recorded vacancies for " + career.getTitle(), jsString(r.get("vacancy_count")) + " vacancies in the stored regional series.",
                                "SA4", str(r.get("region_name")), str(r.get("period")), str(r.get("source")), "occupation and SA4", "SA4".equals(location.regionTier()), str(r.get("imported_at")));
                            sig.setMetrics(metrics("advertisements", number(r.get("vacancy_count")), "employers", null));
                            addSignal("ivi_" + jsString(r.get("row_id")), sig);
                        }
                    }
                    for (var r : s.query("jsa_employment_projection", "SELECT * FROM jsa_employment_projection WHERE anzsco_code=? AND (state_code=? OR state_code IN ('AUS','ALL')) ORDER BY release_date DESC,projection_horizon ASC LIMIT 2",
                            career.getGroupCode(), truthy(location.regionState()) ? location.regionState() : "AUS")) {
                        var sig = signal("PROJECTION", career.getId(), "Employment outlook for " + career.getTitle(),
                            "Stored projection: " + jsString(r.get("growth_pct")) + "% employment change over " + jsString(r.get("projection_horizon")) + " years from " + jsString(r.get("projection_year")) + ".",
                            Set.of("AUS", "ALL").contains(str(r.get("state_code"))) ? "NATIONAL" : "STATE", str(r.get("state_code")), str(r.get("release_date")),
                            "Jobs and Skills Australia", "Stored employment projection", false, str(r.get("updated_at")));
                        sig.setMetrics(metrics("growthPercent", number(r.get("growth_pct")), "horizonYears", number(r.get("projection_horizon")), "baseYear", number(r.get("projection_year"))));
                        addSignal("projection_" + jsString(r.get("projection_id")), sig);
                    }
                    // Employer signals are dated observations, not a promise of an open vacancy.
                    if (truthy(location.regionState())) for (var r : s.query("employer_job_edge", "SELECT edge_id,employer_name,scope,period,state_code,region_key,updated_at,method FROM employer_job_edge WHERE anzsco_code=? AND state_code=? AND is_active=1 ORDER BY period DESC LIMIT 3", career.getGroupCode(), location.regionState())) {
                        addSignal("employer_" + jsString(r.get("edge_id")), signal("EMPLOYER_SIGNAL", career.getId(), text(r.get("employer_name")), "Employer recorded against " + career.getTitle() + ".",
                            str(or(r.get("scope"), "STATE")), str(r.get("state_code")), str(r.get("period")), "Stored employer–occupation relationship", str(r.get("method")),
                            truthy(r.get("region_key")) && java.util.Objects.equals(str(r.get("region_key")), location.regionKey()) && java.util.Objects.equals(str(r.get("scope")), location.regionTier()),
                            str(r.get("updated_at"))));
                    }
                }
                if (has("INDUSTRY") && s.tables.contains("dim_industry")) for (var r : s.query("dim_crosswalk_anzsic_anzsco", "SELECT x.anzsic_code,d.industry_name,x.source FROM dim_crosswalk_anzsic_anzsco x JOIN dim_industry d ON d.anzsic_code=x.anzsic_code WHERE x.anzsco_code=? LIMIT 3", career.getGroupCode())) {
                    WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                    ind.setId(career.getId() + "_" + jsString(r.get("anzsic_code")));
                    ind.setEvidenceId("warehouse_industry_" + career.getId() + "_" + jsString(r.get("anzsic_code")));
                    ind.setName(text(r.get("industry_name")));
                    ind.setCareerId(career.getId());
                    ind.setMethod(text(r.get("source")));
                    ind.setScope("Occupation-to-industry mapping");
                    industries.add(ind);
                }
            }
            if (has("LOCAL") && "SA2".equals(location.regionTier())) {
                for (var r : s.query("salm_unemployment_sa2", "SELECT row_id,data_item,quarter,value,source FROM salm_unemployment_sa2 WHERE sa2_code=? AND is_unavailable=0 ORDER BY quarter_date DESC,CASE WHEN lower(data_item) LIKE '%rate%' THEN 0 ELSE 1 END LIMIT 2", location.region.get("region_key"))) {
                    addSignal("local_" + jsString(r.get("row_id")), signal("LOCAL_CONTEXT", null, text(r.get("data_item")),
                        jsString(r.get("value")) + (jsString(r.get("data_item")).toLowerCase(java.util.Locale.ROOT).contains("rate") ? "%" : ""),
                        "SA2", str(location.region.get("name")), str(r.get("quarter")), str(r.get("source")), "Exact region key", true, null));
                }
            }
            WarehouseConnections out = new WarehouseConnections();
            out.getLocation().setRequested(location.requested);
            out.getLocation().setRegion(location.region == null ? null : regionRef(location.region));
            out.getLocation().setCandidates(location.candidates.stream().map(WarehouseQueryService::regionRef).toList());
            out.setLocalOverview(has("LOCAL") ? localOverview(location) : null);
            out.setProviders(providers);
            out.setCareers(careers);
            out.setExploration(exploration);
            out.setIndustries(industries.stream().limit(12).toList());
            out.setSignals(signals);
            out.setRelationships(relationships);
            return out;
        }

        private WarehouseConnections.Signal signal(String kind, String careerId, String title, String text, String scope, String region, String period,
                                                   String source, String method, boolean localMatch, String updatedAt) {
            WarehouseConnections.Signal sig = new WarehouseConnections.Signal();
            sig.setKind(kind); sig.setCareerId(careerId); sig.setTitle(title); sig.setText(text); sig.setScope(scope); sig.setRegion(region);
            sig.setPeriod(period); sig.setSource(source); sig.setMethod(method); sig.setLocalMatch(localMatch); sig.setUpdatedAt(updatedAt);
            return sig;
        }
    }

    /** JS {@code x===1} (JDBC integers only). */
    private static boolean strictOne(Object v) { return v instanceof Number n && !(v instanceof Double) && n.longValue() == 1; }

    private static int size(List<?> v) { return v == null ? 0 : v.size(); }

    /** linked-data.cjs json(): strings parse (else {}), other values pass through ({@code x||{}}). */
    private static JsonNode linkedJson(Object x) {
        return x instanceof String ? WarehouseText.json(x) : truthy(x) ? WarehouseText.MAPPER.valueToTree(x) : WarehouseText.MAPPER.createObjectNode();
    }

    // ---- opportunities.cjs ---------------------------------------------------------------------

    private static final Set<String> ROLE_NOISE = Set.of("officer", "assistant", "technician", "representative", "specialist", "analyst", "worker", "workers", "career");

    private final class Opportunities {
        final Session s;
        Opportunities(Session s) { this.s = s; }

        private final class Hit {
            String roleId, title;
            Double rank;
            int score;
            boolean named, hint, related;
            final List<String> matchedSkills = new ArrayList<>();
        }

        private List<Map<String, Object>> searchRoles(String kind, String q, boolean alias) {
            return s.index.queryForList("SELECT role_id,title,bm25(role_search,0,8,4,1,6) rank FROM role_search WHERE role_search MATCH ? ORDER BY rank LIMIT 12",
                ("skill".equals(kind) ? "skills : " : alias ? "aliases : " : "title : ") + "(" + q + ")");
        }

        WarehouseExploration read(WarehouseQueryPlan plan, Location location) {
            Set<String> facets = new HashSet<>(plan.getFacets() == null ? List.of() : plan.getFacets());
            boolean requested = facets.contains("SKILLS") || facets.contains("JOBS") || facets.contains("LEARNING") || size(plan.getSkillQueries()) > 0;
            if (!requested) return null;
            List<String> skills = (plan.getSkillQueries() == null ? List.<String>of() : plan.getSkillQueries()).stream().map(q -> text(q, 100)).filter(q -> !q.isEmpty()).limit(6).toList();
            List<String> roleTerms = limit(plan.getOccupationQueries(), 3);
            List<String> related = new ArrayList<>();
            if (roleTerms.isEmpty()) { related.addAll(limit(plan.getRoleQueries(), 99)); related.addAll(limit(plan.getJobQueries(), 99)); }
            Map<String, Hit> roles = new LinkedHashMap<>();
            // Match each concept separately: someone can bring several transferable skills, not necessarily every skill in one title.
            Object[][] passes = {{"role", roleTerms}, {"related", related}, {"hint", skills}, {"skill", skills}};
            for (Object[] pass : passes) {
                String kind = (String) pass[0];
                @SuppressWarnings("unchecked") List<String> phrases = (List<String>) pass[1];
                for (String phrase : phrases) {
                    String match = WarehouseText.clause(phrase);
                    if (match.isEmpty()) continue;
                    var found = searchRoles(kind, match, false);
                    if (found.isEmpty() && !"skill".equals(kind) && !"hint".equals(kind)) {
                        List<String> reduced = WarehouseText.tokens(phrase).stream().filter(w -> !ROLE_NOISE.contains(w)).toList();
                        if (reduced.size() >= 2) found = searchRoles(kind, WarehouseText.clause(String.join(" ", reduced)), false);
                        if (found.isEmpty()) found = searchRoles(kind, match, true);
                    }
                    for (var r : found) {
                        String roleId = jsString(r.get("role_id"));
                        Hit hit = roles.computeIfAbsent(roleId, k -> { Hit h = new Hit(); h.roleId = roleId; h.title = str(r.get("title")); h.rank = number(r.get("rank")); return h; });
                        hit.score += "skill".equals(kind) ? 1 : 100;
                        hit.named |= "role".equals(kind);
                        hit.hint |= "hint".equals(kind);
                        hit.related |= "related".equals(kind) || "hint".equals(kind);
                        if ("skill".equals(kind) && !hit.matchedSkills.contains(phrase)) hit.matchedSkills.add(phrase);
                    }
                }
            }
            List<Hit> values = new ArrayList<>(roles.values());
            List<Hit> focused = !roleTerms.isEmpty() ? values.stream().filter(r -> r.named || r.hint).toList()
                : values.stream().anyMatch(r -> r.related) ? values.stream().filter(r -> r.related).toList()
                : size(plan.getRoleQueries()) > 0 ? List.of() : values;
            List<Hit> candidates = new ArrayList<>(focused);
            candidates.sort((a, b) -> a.score != b.score ? Integer.compare(b.score, a.score)
                : !java.util.Objects.equals(a.rank, b.rank) ? Double.compare(a.rank == null ? 0 : a.rank, b.rank == null ? 0 : b.rank)
                : COLLATOR.compare(String.valueOf(a.title), String.valueOf(b.title)));
            candidates = candidates.stream().limit(12).toList();
            List<Hit> selected;
            if (plan.getRoleIds() != null) {
                List<String> ids = plan.getRoleIds();
                selected = candidates.stream().filter(r -> ids.contains("role:" + r.roleId)).sorted(Comparator.comparingInt(r -> ids.indexOf("role:" + r.roleId))).toList();
            } else selected = plan.isCandidatePool() ? candidates : candidates.stream().limit(4).toList();
            List<WarehouseExploration.Role> selectedRoles = new ArrayList<>();
            for (Hit r : selected) {
                var row = s.get("onet_occupation", "SELECT job_id,job_title,description,tasks FROM onet_occupation WHERE job_id=?", r.roleId);
                var mappings = s.query("onet_anzsco_crosswalk", "SELECT anzsco_code,anzsco_title,method,confidence FROM onet_anzsco_crosswalk WHERE job_id=? ORDER BY confidence DESC LIMIT 2", r.roleId).stream()
                    .map(m -> new WarehouseExploration.Mapping(str(m.get("anzsco_code")), str(m.get("anzsco_title")), str(m.get("method")), number(m.get("confidence")))).toList();
                var requirements = s.index.queryForList("SELECT skill_id AS id,name,description FROM role_skills WHERE role_id=? LIMIT 18", r.roleId).stream()
                    .map(k -> new WarehouseExploration.RoleSkill(str(k.get("id")), str(k.get("name")), str(k.get("description")))).toList();
                WarehouseExploration.Role role = new WarehouseExploration.Role();
                role.setId("role:" + r.roleId);
                role.setEvidenceId("warehouse_role_" + r.roleId);
                role.setTitle(r.title);
                role.setDescription(text(row == null ? null : row.get("description"), 600));
                role.setTasks(oppList(row == null ? null : row.get("tasks"), 4));
                role.setMatchedSkills(r.matchedSkills);
                role.setSkills(requirements);
                role.setMappings(mappings);
                role.setSource("O*NET occupation profile");
                role.setScope("General occupational context; stored Australian crosswalks are shown separately");
                role.setMatchReason(r.named ? "Matches the occupation requested" : "Uses one or more of the skills being explored; personal suitability is not established");
                selectedRoles.add(role);
            }
            List<WarehouseExploration.LearningLink> learning = new ArrayList<>();
            if (facets.contains("LEARNING")) for (String phrase : skills) {
                String match = WarehouseText.clause(phrase);
                if (match.isEmpty()) continue;
                for (var r : s.index.queryForList("SELECT code,name,kind,method FROM learning_search WHERE learning_search MATCH ? ORDER BY rank LIMIT 8", match)) {
                    String code = str(r.get("code")), skill = str(r.get("name"));
                    if (learning.stream().anyMatch(x -> java.util.Objects.equals(x.getCode(), code) && java.util.Objects.equals(x.getSkill(), skill))) continue;
                    var courses = s.index.queryForList("SELECT course_id,course_name,institution_name FROM catalogue WHERE national_code=? LIMIT 2", r.get("code"));
                    WarehouseExploration.LearningLink link = new WarehouseExploration.LearningLink();
                    link.setId("learning:" + code + ":" + learning.size());
                    link.setEvidenceId("warehouse_learning_" + code + "_" + learning.size());
                    link.setCode(code);
                    link.setSkill(skill);
                    link.setKind(str(r.get("kind")));
                    link.setMethod(str(r.get("method")));
                    link.setQuery(phrase);
                    link.setCourses(courses.stream().map(c -> new WarehouseExploration.CourseRef(str(c.get("course_id")), str(c.get("course_name")), str(c.get("institution_name")))).toList());
                    link.setScope("Stored qualification-to-skill or unit link; delivery and assessment are not established by this link");
                    learning.add(link);
                }
            }
            List<WarehouseExploration.JobAd> jobs = new ArrayList<>();
            List<WarehouseExploration.ObservedSkill> observedSkills = new ArrayList<>();
            WarehouseExploration.Geography geography = null;
            int sampleSize = 0, withSkills = 0;
            List<String> source = size(plan.getJobQueries()) > 0 ? plan.getJobQueries() : !roleTerms.isEmpty() ? roleTerms : size(plan.getRoleQueries()) > 0 ? plan.getRoleQueries() : skills;
            List<String> jobTerms = new LinkedHashSet<>(source).stream().filter(x -> !WarehouseText.clause(x).isEmpty()).limit(8).toList();
            if (facets.contains("JOBS") && s.tables.contains("outside_jobs") && !jobTerms.isEmpty()) {
                WarehouseQueryPlan.LocationQuery local = plan.getLocation() == null ? new WarehouseQueryPlan.LocationQuery() : plan.getLocation();
                List<Object[]> stages = new ArrayList<>(); // where, args, scope, name, local
                if (location.region != null) {
                    String regionState = location.regionState(), tier = location.regionTier();
                    if (truthy(local.getName())) stages.add(new Object[]{"g.city=? AND g.state=?", new Object[]{text(local.getName(), 160).toLowerCase(java.util.Locale.ROOT), regionState}, "SUBURB", text(local.getName(), 600), true});
                    if (local.getPostcode() != null && local.getPostcode().matches("\\d{4}")) stages.add(new Object[]{"g.postcode=? AND g.state=?", new Object[]{local.getPostcode(), regionState}, "POSTCODE", local.getPostcode(), true});
                    var sa4 = location.chain.stream().filter(x -> "SA4".equals(str(x.get("tier")))).findFirst().orElse(null);
                    if (sa4 != null) stages.add(new Object[]{"g.sa4=?", new Object[]{jsString(sa4.get("region_key"))}, "SA4", str(sa4.get("name")), "SA4".equals(tier)});
                    if (truthy(regionState)) stages.add(new Object[]{"g.state=?", new Object[]{regionState}, "STATE", regionState, "STATE".equals(tier)});
                } else if (location.requested.isEmpty()) stages.add(new Object[]{"1=1", new Object[0], "NATIONAL", "Australia", false});
                // Unresolved/ambiguous user locations never silently become an unrestricted search.
                for (Object[] stage : stages) {
                    Set<String> candidateIds = new LinkedHashSet<>();
                    for (String phrase : jobTerms) {
                        List<Object> args = new ArrayList<>();
                        args.add(WarehouseText.clause(phrase));
                        args.addAll(Arrays.asList((Object[]) stage[1]));
                        for (var r : s.index.queryForList("SELECT j.job_id FROM job_search j JOIN job_geo g ON g.job_id=j.job_id WHERE job_search MATCH ? AND " + stage[0] + " ORDER BY rank LIMIT 100", args.toArray()))
                            candidateIds.add(str(r.get("job_id")));
                    }
                    if (candidateIds.isEmpty()) continue;
                    List<String> ids = candidateIds.stream().limit(800).toList();
                    var found = s.query("outside_jobs", "SELECT id,title,company_name,city,state,postal_code,skills_json,requirements_json,description,employment_type,work_mode,salary_text,posted_at,updated_at,expired_at,job_url,source FROM outside_jobs WHERE id IN ("
                        + ids.stream().map(x -> "?").collect(Collectors.joining(",")) + ") AND privacy_level='PUBLIC' AND status='active' AND upper(country) IN ('AU','AUSTRALIA','AUS') AND (expired_at IS NULL OR expired_at='' OR julianday(expired_at)>julianday('now')) ORDER BY COALESCE(NULLIF(posted_at,''),updated_at) DESC,id DESC LIMIT 40", ids.toArray());
                    if (found.isEmpty()) continue;
                    String stageName = (String) stage[3];
                    geography = new WarehouseExploration.Geography((String) stage[2], stageName, (Boolean) stage[4]);
                    sampleSize = found.size();
                    Map<String, Object[]> counts = new LinkedHashMap<>(); // norm -> {name, count}
                    for (var r : found) {
                        List<String> names = new ArrayList<>(new LinkedHashSet<>(oppList(r.get("skills_json"), 30)));
                        if (!names.isEmpty()) withSkills++;
                        for (String name : names) {
                            Object[] entry = counts.computeIfAbsent(text(name, 160).toLowerCase(java.util.Locale.ROOT), k -> new Object[]{name, 0});
                            entry[1] = (Integer) entry[1] + 1;
                        }
                    }
                    List<Object[]> top = counts.values().stream()
                        .sorted((a, b) -> !a[1].equals(b[1]) ? Integer.compare((Integer) b[1], (Integer) a[1]) : COLLATOR.compare((String) a[0], (String) b[0])).limit(8).toList();
                    for (int k = 0; k < top.size(); k++) {
                        WarehouseExploration.ObservedSkill os = new WarehouseExploration.ObservedSkill();
                        os.setName((String) top.get(k)[0]);
                        os.setCount((Integer) top.get(k)[1]);
                        os.setId("observed:" + k);
                        os.setEvidenceId("warehouse_observed_skill_" + k);
                        os.setDenominator(sampleSize);
                        os.setScope("Structured skills in the retrieved advertisement sample only");
                        os.setGeography(stageName);
                        observedSkills.add(os);
                    }
                    for (var r : found.stream().limit(6).toList()) {
                        WarehouseExploration.JobAd job = new WarehouseExploration.JobAd();
                        job.setId(jsString(r.get("id")));
                        job.setEvidenceId("warehouse_job_" + jsString(r.get("id")));
                        job.setTitle(text(r.get("title"), 200));
                        job.setCompany(text(r.get("company_name"), 160));
                        job.setArea(joinTruthy(r.get("city"), r.get("state"), r.get("postal_code")));
                        job.setSkills(oppList(r.get("skills_json")));
                        job.setRequirements(oppList(r.get("requirements_json"), 6));
                        job.setDescription(text(r.get("description"), 800));
                        job.setEmploymentType(text(r.get("employment_type"), 100));
                        job.setWorkMode(text(r.get("work_mode"), 100));
                        job.setSalary(text(r.get("salary_text"), 160));
                        job.setPostedAt(truthy(r.get("posted_at")) ? str(r.get("posted_at")) : null);
                        job.setUpdatedAt(truthy(r.get("updated_at")) ? str(r.get("updated_at")) : null);
                        String url = truthy(r.get("job_url")) ? jsString(r.get("job_url")) : "";
                        job.setUrl(Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE).matcher(url).find() ? url : null);
                        job.setSource(text(r.get("source"), 600));
                        job.setGeography(geography);
                        jobs.add(job);
                    }
                    break;
                }
            }
            Map<String, WarehouseExploration.SkillRef> skillsById = new LinkedHashMap<>();
            for (var role : selectedRoles) for (var sk : role.getSkills()) {
                skillsById.computeIfAbsent(sk.getId(), k -> { var ref = new WarehouseExploration.SkillRef(); ref.setId(sk.getId()); ref.setName(sk.getName()); ref.setDescription(sk.getDescription()); return ref; })
                    .getRoleIds().add(role.getId());
            }
            List<WarehouseExploration.SkillRef> displayed = new ArrayList<>(skillsById.values());
            displayed.sort((a, b) -> a.getRoleIds().size() != b.getRoleIds().size() ? Integer.compare(b.getRoleIds().size(), a.getRoleIds().size()) : COLLATOR.compare(String.valueOf(a.getName()), String.valueOf(b.getName())));
            WarehouseExploration e = new WarehouseExploration();
            e.setRoles(selectedRoles);
            e.setSkills(displayed.stream().limit(12).toList());
            e.setLearning(learning.stream().limit(8).toList());
            e.setJobs(jobs);
            e.setObservedSkills(observedSkills);
            e.setGeography(geography);
            e.getCoverage().setSampleSize(sampleSize);
            e.getCoverage().setWithStructuredSkills(withSkills);
            e.getCoverage().setReturnedJobs(jobs.size());
            return e;
        }
    }
}
