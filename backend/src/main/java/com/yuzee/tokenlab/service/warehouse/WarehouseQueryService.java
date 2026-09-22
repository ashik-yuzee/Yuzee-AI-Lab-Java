package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzee.tokenlab.model.warehouse.ExplorationChoice;
import com.yuzee.tokenlab.model.warehouse.ProviderMatch;
import com.yuzee.tokenlab.model.warehouse.WarehouseComparison;
import com.yuzee.tokenlab.model.warehouse.WarehouseConnections;
import com.yuzee.tokenlab.model.warehouse.WarehouseCourse;
import com.yuzee.tokenlab.model.warehouse.WarehouseExploration;
import com.yuzee.tokenlab.model.warehouse.WarehousePack;
import com.yuzee.tokenlab.model.warehouse.WarehouseQueryPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC-backed course/provider/career/industry/signal lookups against the derived warehouse index
 * and (read-only) the source {@code training_gov.db}. Ported from:
 * <ul>
 *   <li>catalogue-worker.cjs (the {@code search()} FTS query and the {@code lookup} message handler)</li>
 *   <li>linked-data.cjs ({@code createLinkedReader})</li>
 *   <li>opportunities.cjs ({@code createOpportunityReader})</li>
 *   <li>provider-data.cjs ({@code createProviderReader})</li>
 *   <li>normalize.ts ({@code normalizeCourse})</li>
 *   <li>comparison.ts ({@code buildComparison})</li>
 *   <li>choices.ts ({@code explorationChoice})</li>
 * </ul>
 * Every read is wrapped in a "does this table/column exist" guard, mirroring the old readers' use
 * of {@code sqlite_master} — the source schema is externally supplied and not all tables are
 * guaranteed present.
 */
@Service
public class WarehouseQueryService {

    private static final List<String> COURSE_FIELDS = List.of(
        "id", "course_name", "institution_id", "institution_name", "national_code", "course_code",
        "aqf_level", "course_type", "description", "duration_text", "delivery_modes_json", "locations_json",
        "entry_requirements", "domestic_fee", "international_fee", "skills_json", "quality_scores_json",
        "work_readiness_score", "future_readiness_score", "yuzee_readiness_score", "career_outcomes_json",
        "intelligence_json", "canonical_url", "web_collect_url", "website", "intelligence_enriched_at", "updated_at");

    private static final Set<String> STATES = Set.of("ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA");

    private final WarehouseIndexBuilder indexBuilder;
    private final String sourcePath;

    public WarehouseQueryService(WarehouseIndexBuilder indexBuilder,
                                  @Value("${warehouse.db-path:}") String sourcePath) {
        this.indexBuilder = indexBuilder;
        this.sourcePath = sourcePath == null ? "" : sourcePath.trim();
    }

    public boolean isAvailable() { return indexBuilder.ensureReady(); }

    /** One JDBC round of open connections to source + derived index, closed together. Mirrors the
     *  old worker process's single long-lived pair of {@code DatabaseSync} handles, scoped per call
     *  here since a Java service has no separate worker process to keep them alive in. */
    private final class Session implements AutoCloseable {
        final Connection sourceConn;
        final Connection indexConn;
        final JdbcTemplate source;
        final JdbcTemplate index;
        final Set<String> sourceTables;

        Session() throws SQLException {
            SQLiteConfig readOnly = new SQLiteConfig();
            readOnly.setReadOnly(true);
            sourceConn = DriverManager.getConnection("jdbc:sqlite:" + sourcePath, readOnly.toProperties());
            indexConn = DriverManager.getConnection("jdbc:sqlite:" + indexBuilder.getIndexPath());
            source = new JdbcTemplate(new SingleConnectionDataSource(sourceConn, true));
            index = new JdbcTemplate(new SingleConnectionDataSource(indexConn, true));
            sourceTables = new LinkedHashSet<>();
            for (Map<String, Object> row : source.queryForList("SELECT name FROM sqlite_master WHERE type='table'")) {
                sourceTables.add(String.valueOf(row.get("name")));
            }
        }

        boolean has(String table) { return sourceTables.contains(table); }

        @Override
        public void close() {
            try { sourceConn.close(); } catch (SQLException ignored) {}
            try { indexConn.close(); } catch (SQLException ignored) {}
        }
    }

    /** Result of one lookup round. Mirrors the {@code {rows, connected, providerMatches, qualifications}}
     *  object catalogue-worker.cjs sends back over IPC. */
    public record LookupResult(List<WarehouseCourse> courses, WarehouseConnections connected,
                                List<ProviderMatch> providerMatches, List<WarehouseComparison.Qualification> qualifications) {}

    /** Ported from catalogue-worker.cjs's {@code process.on('message', ...)} lookup handler. */
    public LookupResult lookup(List<String> queries, List<String> ids, WarehouseQueryPlan plan) {
        if (!isAvailable()) return new LookupResult(List.of(), null, List.of(), List.of());
        List<String> safeQueries = (queries == null ? List.<String>of() : queries).stream()
            .filter(q -> q != null && !q.isBlank()).map(q -> q.length() > 240 ? q.substring(0, 240) : q)
            .limit(3).toList();
        WarehouseQueryPlan safePlan = plan == null ? new WarehouseQueryPlan() : plan;

        try (Session s = new Session()) {
            List<String> chosen = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String id : ids == null ? List.<String>of() : ids) {
                if (chosen.size() >= 4) break;
                if (id != null && id.matches("\\d+") && seen.add(id)) chosen.add(id);
            }

            List<ProviderMatch> providerMatches = providerMatches(s, safePlan.getProviderQueries());
            // Duplicate legal names can represent distinct registrations. Resolve only when the
            // user's requested course actually belongs to exactly one record (linked-data parity).
            for (ProviderMatch match : providerMatches) {
                if (!"AMBIGUOUS".equals(match.getStatus()) || safeQueries.isEmpty()) continue;
                List<ProviderMatch.ProviderRecord> offering = match.getProviders().stream()
                    .filter(p -> safeQueries.stream().anyMatch(q -> !search(s, q, p.getId()).isEmpty()))
                    .collect(Collectors.toList());
                if (offering.size() == 1) {
                    match.setStatus("MATCHED");
                    match.setProviders(offering);
                    match.setResolution("Unique requested course offering");
                }
            }
            List<ProviderMatch.ProviderRecord> resolved = providerMatches.stream()
                .filter(m -> "MATCHED".equals(m.getStatus())).flatMap(m -> m.getProviders().stream()).toList();

            List<List<Map<String, Object>>> groups = new ArrayList<>();
            if (!providerMatches.isEmpty()) {
                for (ProviderMatch.ProviderRecord p : resolved) {
                    for (String q : safeQueries) groups.add(search(s, q, p.getId()));
                }
            } else {
                for (String q : safeQueries) groups.add(search(s, q, null));
            }
            boolean allCodes = !safeQueries.isEmpty() && safeQueries.stream().allMatch(q -> q.trim().matches("(?i)[A-Z]{3}[0-9]{5}"));
            int scanDepth = (!providerMatches.isEmpty() && allCodes) ? 1 : 16;
            for (int n = 0; n < scanDepth && chosen.size() < 4; n++) {
                for (List<Map<String, Object>> group : groups) {
                    if (n >= group.size()) continue;
                    String courseId = String.valueOf(group.get(n).get("course_id"));
                    if (chosen.size() < 4 && seen.add(courseId)) chosen.add(courseId);
                }
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            String fieldList = String.join(",", COURSE_FIELDS);
            for (String id : chosen) {
                List<Map<String, Object>> hit = s.source.queryForList("SELECT " + fieldList + " FROM live_courses WHERE id=?", id);
                if (!hit.isEmpty()) rows.add(hit.get(0));
            }

            boolean comparing = safePlan.isComparison() || !providerMatches.isEmpty();
            List<WarehouseCourse> courses = rows.stream().map(this::normalizeCourse).toList();
            WarehouseConnections connected = new LinkedDataExpansion(s, safePlan).read(rows);

            List<ProviderMatch> finalProviderMatches = comparing
                ? (providerMatches.isEmpty()
                    ? providerMatches(s, rows.stream().map(r -> String.valueOf(r.get("institution_name")))
                        .filter(n -> n != null && !"null".equals(n)).distinct().toList())
                    : providerMatches)
                : List.of();
            List<WarehouseComparison.Qualification> qualifications = comparing ? qualifications(s, rows) : List.of();

            return new LookupResult(courses, connected, finalProviderMatches, qualifications);
        } catch (SQLException e) {
            throw new WarehouseUnavailableException("Warehouse lookup could not be completed.", e);
        }
    }

    /** Signals a lookup that could not run against the configured database (connection/IO failure,
     *  as opposed to "database simply not configured", which never reaches here). */
    public static class WarehouseUnavailableException extends RuntimeException {
        public WarehouseUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    // ---- catalogue full-text search (catalogue-worker.cjs's search()) -------------------

    private List<Map<String, Object>> search(Session s, String text, String providerId) {
        String clause = WarehouseText.matchClause(text);
        if (clause == null) return List.of();
        String sql = "SELECT course_id,course_name,institution_name,bm25(catalogue,0,5,3,8,8,0) AS rank FROM catalogue " +
            "WHERE catalogue MATCH ?" + (providerId != null ? " AND institution_id=?" : "") + " ORDER BY rank LIMIT 16";
        return providerId != null ? s.index.queryForList(sql, clause, providerId) : s.index.queryForList(sql, clause);
    }

    // ---- provider matching (provider-data.cjs) -------------------------------------------

    private List<ProviderMatch> providerMatches(Session s, List<String> queries) {
        List<ProviderMatch> out = new ArrayList<>();
        if (!s.has("live_institutions") || queries == null) return out;
        for (String q : queries.stream().filter(x -> x != null && !x.isBlank()).limit(3).toList()) {
            ProviderMatch match = new ProviderMatch();
            match.setQuery(q);
            List<String> words = WarehouseText.terms(q, 8);
            if (words.isEmpty()) { match.setStatus("NOT_FOUND"); out.add(match); continue; }
            List<Map<String, Object>> exact = s.source.queryForList(
                "SELECT id,legal_name,rto_code,rto_type,city,state,about_us_description,website_url," +
                "has_student_support,has_disability_support,has_indigenous_support,has_library,has_apprenticeships,updated_at " +
                "FROM live_institutions WHERE lower(legal_name)=lower(?) OR rto_code=? LIMIT 4", q, q);
            List<Map<String, Object>> rows = exact;
            if (rows.isEmpty()) {
                StringBuilder where = new StringBuilder();
                List<Object> args = new ArrayList<>();
                for (String w : words) {
                    if (!where.isEmpty()) where.append(" AND ");
                    where.append("legal_name LIKE ? ESCAPE '\\'");
                    args.add("%" + WarehouseText.safeLike(w) + "%");
                }
                rows = s.source.queryForList(
                    "SELECT id,legal_name,rto_code,rto_type,city,state,about_us_description,website_url," +
                    "has_student_support,has_disability_support,has_indigenous_support,has_library,has_apprenticeships,updated_at " +
                    "FROM live_institutions WHERE " + where + " ORDER BY legal_name LIMIT 4", args.toArray());
            }
            match.setStatus(rows.size() == 1 ? "MATCHED" : rows.isEmpty() ? "NOT_FOUND" : "AMBIGUOUS");
            for (Map<String, Object> r : rows) match.getProviders().add(projectProvider(r));
            out.add(match);
        }
        return out;
    }

    private ProviderMatch.ProviderRecord projectProvider(Map<String, Object> r) {
        ProviderMatch.ProviderRecord p = new ProviderMatch.ProviderRecord();
        p.setId(String.valueOf(r.get("id")));
        p.setEvidenceId("warehouse_rto_" + r.get("id"));
        String name = WarehouseText.cleanText(r.get("legal_name"), 700);
        p.setName(name != null ? name : "Provider");
        p.setRtoCode(WarehouseText.cleanText(r.get("rto_code"), 700));
        p.setType(WarehouseText.cleanText(r.get("rto_type"), 700));
        p.setArea(java.util.stream.Stream.of(r.get("city"), r.get("state")).filter(x -> x != null && !String.valueOf(x).isBlank())
            .map(String::valueOf).collect(Collectors.joining(", ")));
        p.setDescription(WarehouseText.cleanText(r.get("about_us_description"), 700));
        List<String> support = new ArrayList<>();
        addFlagLabel(support, r, "has_student_support", "Student support");
        addFlagLabel(support, r, "has_disability_support", "Disability support");
        addFlagLabel(support, r, "has_indigenous_support", "Indigenous learner support");
        addFlagLabel(support, r, "has_library", "Library");
        addFlagLabel(support, r, "has_apprenticeships", "Apprenticeship support");
        p.setSupport(support);
        p.setUpdatedAt(WarehouseText.cleanText(r.get("updated_at"), 700));
        p.setScope("Provider record; general locations and support do not establish course-specific delivery.");
        return p;
    }

    private void addFlagLabel(List<String> out, Map<String, Object> r, String key, String label) {
        Object v = r.get(key);
        if (v != null && ("1".equals(String.valueOf(v)) || Boolean.TRUE.equals(v))) out.add(label);
    }

    private List<WarehouseComparison.Qualification> qualifications(Session s, List<Map<String, Object>> rows) {
        boolean hasUnits = s.has("qualification_units");
        List<String> codes = rows.stream().map(r -> r.get("national_code")).filter(c -> c != null)
            .map(String::valueOf).distinct().limit(4).toList();
        List<WarehouseComparison.Qualification> out = new ArrayList<>();
        for (String code : codes) {
            WarehouseComparison.Qualification q = new WarehouseComparison.Qualification();
            q.setCode(code);
            q.setEvidenceId("warehouse_qualification_" + code);
            q.setScope("National qualification content (up to 30 units), not a provider-specific elective or delivery plan.");
            if (hasUnits) {
                for (Map<String, Object> u : s.source.queryForList(
                        "SELECT unit_code,unit_title,unit_type FROM qualification_units WHERE qualification_code=? ORDER BY unit_type,unit_code LIMIT 30", code)) {
                    q.getUnits().add(new WarehouseComparison.Unit(
                        WarehouseText.cleanText(u.get("unit_code"), 700), WarehouseText.cleanText(u.get("unit_title"), 700), WarehouseText.cleanText(u.get("unit_type"), 700)));
                }
            }
            out.add(q);
        }
        return out;
    }

    // ---- normalizeCourse (normalize.ts) --------------------------------------------------

    public WarehouseCourse normalizeCourse(Map<String, Object> r) {
        JsonNode intel = WarehouseText.json(r.get("intelligence_json"));
        JsonNode facts = intel.path("verified_facts");
        JsonNode outcome = intel.path("yuzee_outcome_layer");
        JsonNode skillsNode = WarehouseText.json(r.get("skills_json"));
        JsonNode qualityScores = WarehouseText.json(r.get("quality_scores_json"));
        JsonNode trustQuality = intel.path("trust_and_quality");

        Integer frameworkLevel = firstValidLevel(
            intPath(intel, "classification", "framework_level"),
            intPath(intel, "course_identity", "framework_level"),
            WarehouseText.intNumber(r.get("aqf_level")));

        WarehouseCourse course = new WarehouseCourse();
        course.setId(String.valueOf(r.get("id")));
        Object institutionId = r.get("institution_id");
        course.setProviderId(institutionId == null ? "" : String.valueOf(institutionId));
        course.setEvidenceId("warehouse_course_" + r.get("id"));
        String name = WarehouseText.cleanText(r.get("course_name"));
        course.setName(name != null ? name : "Course");
        String provider = WarehouseText.cleanText(r.get("institution_name"));
        course.setProvider(provider != null ? provider : "Provider not supplied");
        course.setCode(firstNonNull(WarehouseText.cleanText(r.get("national_code")), WarehouseText.cleanText(r.get("course_code"))));
        course.setLevel(frameworkLevel == null ? null : String.valueOf(frameworkLevel));
        course.setType(firstNonNull(WarehouseText.cleanText(r.get("course_type")), WarehouseText.cleanText(textAt(intel, "classification", "recognition_class"))));
        course.setDescription(firstNonNull(
            WarehouseText.cleanText(textAt(outcome, "student_outcome_headline")),
            WarehouseText.cleanText(textAt(intel, "rendered_content", "short_summary")),
            WarehouseText.cleanText(r.get("description"))));
        course.setDuration(firstNonNull(WarehouseText.cleanText(r.get("duration_text")), WarehouseText.cleanText(textAt(facts, "duration"))));
        List<String> delivery = WarehouseText.list(r.get("delivery_modes_json"), 8);
        course.setDelivery(!delivery.isEmpty() ? delivery : WarehouseText.list(facts.path("delivery_modes"), 8));
        List<String> locations = WarehouseText.list(r.get("locations_json"), 8);
        course.setLocations(!locations.isEmpty() ? locations : WarehouseText.list(facts.path("campuses"), 8));
        course.setEntry(!isBlankOrNull(r.get("entry_requirements")) ? WarehouseText.list(r.get("entry_requirements"), 8) : WarehouseText.list(facts.path("entry_requirements"), 8));

        WarehouseCourse.Fees fees = new WarehouseCourse.Fees();
        fees.setDomestic(firstNonNull(WarehouseText.cleanText(r.get("domestic_fee")), WarehouseText.cleanText(textAt(facts, "domestic_fee"))));
        fees.setInternational(firstNonNull(WarehouseText.cleanText(r.get("international_fee")), WarehouseText.cleanText(textAt(facts, "international_fee"))));
        fees.setDetails(WarehouseText.list(intel.path("cost_full_picture").path("funding_options"), 8));
        course.setFees(fees);

        List<String> skills = WarehouseText.list(skillsNode.path("technical_skills"), 8);
        if (skills.isEmpty()) skills = WarehouseText.list(intel.path("skills").path("technical_skills"), 8);
        if (skills.isEmpty()) skills = WarehouseText.list(intel.path("learning_content").path("core_skills"), 8);
        course.setSkills(skills);
        List<String> outcomes = WarehouseText.list(intel.path("credit_and_pathways").path("pathway_to_next_level"), 8);
        if (outcomes.isEmpty()) outcomes = WarehouseText.list(r.get("career_outcomes_json"), 8);
        course.setOutcomes(outcomes);
        course.setAssessments(WarehouseText.list(intel.path("assessment_model").path("assessment_types"), 8));
        course.setBestFor(WarehouseText.list(textAtNode(outcome, "best_suited_for"), 8));
        List<String> considerations = new ArrayList<>();
        String weakness = WarehouseText.cleanText(textAt(outcome, "honest_weakness"));
        if (weakness != null) considerations.add(weakness);
        considerations.addAll(WarehouseText.list(textAtNode(outcome, "not_ideal_for"), 3));
        course.setConsiderations(considerations);

        List<CourseQualityRow> quality = new ArrayList<>();
        addQuality(quality, "work", "Work readiness",
            r.get("work_readiness_score") != null ? r.get("work_readiness_score") : qualityScores.path("work_readiness_score").isMissingNode() ? null : qualityScores.get("work_readiness_score").asText(),
            "The catalogue's assessment of preparation for the course's intended work or progression outcome.");
        addQuality(quality, "future", "Future relevance",
            r.get("future_readiness_score") != null ? r.get("future_readiness_score") : qualityScores.path("future_relevance_score").isMissingNode() ? null : qualityScores.get("future_relevance_score").asText(),
            "The catalogue's assessment of relevance to changing work and skills.");
        addQuality(quality, "overall", "Overall course readiness",
            r.get("yuzee_readiness_score") != null ? r.get("yuzee_readiness_score") : qualityScores.path("overall_yuzee_readiness_score").isMissingNode() ? null : qualityScores.get("overall_yuzee_readiness_score").asText(),
            "The stored Yuzee course assessment. This is not your personal readiness or a job-success probability.");
        for (CourseQualityRow q : quality) course.getQuality().add(new com.yuzee.tokenlab.model.warehouse.CourseQuality(q.key, q.label, q.value, q.explanation));

        course.setQualityExplanation(WarehouseText.cleanText(firstNonNull(
            trustQuality.path("scoring_reason").isMissingNode() ? null : trustQuality.get("scoring_reason").asText(),
            qualityScores.path("scoring_reason").isMissingNode() ? null : qualityScores.get("scoring_reason").asText())));

        course.setIntelligence(intelligenceSections(intel));

        WarehouseCourse.ComparisonDetails cd = new WarehouseCourse.ComparisonDetails();
        cd.setLearning(WarehouseText.list(intel.path("learning_content").path("core_skills"), 8));
        List<String> practice = new ArrayList<>(WarehouseText.list(facts.path("work_placement").path("model"), 8));
        JsonNode hours = facts.path("work_placement").path("hours");
        if (!hours.isMissingNode() && !hours.isNull()) practice.add("Recorded placement hours: " + hours.asText());
        for (String p : WarehouseText.list(intel.path("work_readiness").path("portfolio_artifacts"), 3)) practice.add("Yuzee portfolio guidance: " + p);
        cd.setPractice(practice);
        List<String> attendance = new ArrayList<>(WarehouseText.list(intel.path("delivery_detail").path("attendance_requirement"), 8));
        attendance.addAll(WarehouseText.list(intel.path("delivery_detail").path("mode"), 8));
        cd.setAttendance(attendance);
        List<String> credit = new ArrayList<>();
        JsonNode rpl = intel.path("credit_and_pathways").path("rpl_available");
        if (rpl.isBoolean()) credit.add("Recognition of prior learning: " + (rpl.asBoolean() ? "recorded as available" : "recorded as unavailable"));
        credit.addAll(WarehouseText.list(intel.path("credit_and_pathways").path("articulation_pathways"), 3));
        cd.setCredit(credit);
        cd.setStrengths(WarehouseText.list(textAtNode(outcome, "best_suited_for"), 8));
        cd.setLimitations(new ArrayList<>(considerations));
        cd.setOutcome(WarehouseText.cleanText(textAt(outcome, "student_outcome_headline")));
        course.setComparisonDetails(cd);

        WarehouseCourse.Source source = new WarehouseCourse.Source();
        source.setUrl(firstNonNull(WarehouseText.url(r.get("canonical_url")), WarehouseText.url(r.get("web_collect_url")), WarehouseText.url(r.get("website"))));
        source.setUpdatedAt(firstNonNull(WarehouseText.cleanText(r.get("intelligence_enriched_at")), WarehouseText.cleanText(r.get("updated_at"))));
        course.setSource(source);

        // Respect a conflict already identified by the warehouse itself: redact rather than guess.
        String issueText = course.getQualityExplanation() != null ? course.getQualityExplanation() : "";
        boolean flagged = issueText.matches("(?is).*(critical conflict|unresolved conflict|conflicting (?:source|course|information|descriptions)|contradict(?:ory|ion)).*")
            && !issueText.matches("(?is).*\\b(?:no|without|not an?) (?:critical )?(?:conflict|contradiction).*");
        if (flagged) {
            course.setEvidenceIssues(List.of(
                course.getProvider() + ": the catalogue flags conflicting course information. Course-specific duration, delivery, entry requirements and analysis are not established by this record.",
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

    private record CourseQualityRow(String key, String label, Double value, String explanation) {}

    private void addQuality(List<CourseQualityRow> out, String key, String label, Object raw, String explanation) {
        Double v = WarehouseText.score(raw);
        if (v != null) out.add(new CourseQualityRow(key, label, v, explanation));
    }

    private Integer firstValidLevel(Integer... candidates) {
        for (Integer c : candidates) if (c != null && c >= 1 && c <= 10) return c;
        return null;
    }

    private Integer intPath(JsonNode node, String a, String b) {
        JsonNode v = node.path(a).path(b);
        return v.isMissingNode() || v.isNull() ? null : WarehouseText.intNumber(v.isTextual() ? v.asText() : v.numberValue());
    }

    private String textAt(JsonNode node, String... path) {
        JsonNode v = node;
        for (String p : path) v = v.path(p);
        return v.isMissingNode() || v.isNull() ? null : (v.isTextual() ? v.asText() : v.toString());
    }

    private JsonNode textAtNode(JsonNode node, String field) { return node.path(field); }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private boolean isBlankOrNull(Object v) { return v == null || String.valueOf(v).isBlank(); }

    private List<WarehouseCourse.IntelligenceSection> intelligenceSections(JsonNode intel) {
        List<WarehouseCourse.IntelligenceSection> sections = new ArrayList<>();
        addSection(sections, "learning", "Learning and practical work", intel.path("learning_content"));
        JsonNode gaps = intel.path("skill_gap_map");
        if (gaps.isMissingNode() || gaps.isNull()) gaps = intel.path("skills").path("skills_not_fully_closed");
        addSection(sections, "gaps", "Skills to build further", gaps);
        Map<String, JsonNode> practice = new LinkedHashMap<>();
        practice.put("internships", intel.path("work_readiness").path("internship_plan"));
        practice.put("portfolio", intel.path("work_readiness").path("portfolio_artifacts"));
        practice.put("placement", intel.path("work_readiness").path("placement_status"));
        addSectionMap(sections, "practice", "Practical experience and portfolio", practice);
        Map<String, JsonNode> roles = new LinkedHashMap<>();
        roles.put("entry_roles", intel.path("career_pathways").path("entry_roles"));
        roles.put("roles_with_further_experience", intel.path("career_pathways").path("stretch_roles"));
        roles.put("not_immediate_roles", intel.path("career_pathways").path("not_immediate_roles"));
        addSectionMap(sections, "roles", "Roles and progression", roles);
        Map<String, JsonNode> roleBasis = new LinkedHashMap<>();
        roleBasis.put("provider_claimed_roles", intel.path("outcome_evidence").path("provider_claimed_roles"));
        roleBasis.put("yuzee_inferred_roles", intel.path("outcome_evidence").path("yuzee_inferred_roles"));
        addSectionMap(sections, "role_basis", "How career possibilities are described", roleBasis);
        addSection(sections, "progression", "Credit and further study", intel.path("credit_and_pathways"));
        JsonNode costs = intel.path("cost_full_picture");
        Map<String, JsonNode> costMap = new LinkedHashMap<>();
        if (costs.isObject()) costs.fields().forEachRemaining(e -> {
            if (!Set.of("tuition_fee", "confidence", "funding_options").contains(e.getKey())) costMap.put(e.getKey(), e.getValue());
        });
        addSectionMap(sections, "costs", "Costs beyond tuition", costMap);
        Map<String, JsonNode> professional = new LinkedHashMap<>();
        professional.put("registration_body", intel.path("regulated_profession").path("registration_body"));
        professional.put("placement_clearances", intel.path("regulated_profession").path("placement_clearances"));
        professional.put("licensing_steps", intel.path("regulated_profession").path("licensing_steps_after_graduation"));
        addSectionMap(sections, "professional", "Professional and placement requirements", professional);
        addSection(sections, "delivery", "Attendance and study setup", intel.path("delivery_detail"));
        Map<String, JsonNode> ai = new LinkedHashMap<>();
        ai.put("context", intel.path("ai_readiness").path("context"));
        ai.put("portfolio_suggestions", intel.path("ai_readiness").path("ai_portfolio_suggestions"));
        addSectionMap(sections, "ai", "AI and changing work", ai);
        return sections;
    }

    private void addSection(List<WarehouseCourse.IntelligenceSection> out, String key, String label, JsonNode value) {
        List<String> items = readable(value, 0);
        if (!items.isEmpty()) out.add(new WarehouseCourse.IntelligenceSection(key, label, items.stream().limit(4).map(t -> t.length() > 300 ? t.substring(0, 300) : t).toList()));
    }

    private void addSectionMap(List<WarehouseCourse.IntelligenceSection> out, String key, String label, Map<String, JsonNode> value) {
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : value.entrySet()) {
            for (String t : readable(e.getValue(), 0)) items.add(WarehouseText.label(e.getKey()) + ": " + t);
            if (items.size() >= 4) break;
        }
        if (!items.isEmpty()) out.add(new WarehouseCourse.IntelligenceSection(key, label, items.stream().limit(4).map(t -> t.length() > 300 ? t.substring(0, 300) : t).toList()));
    }

    private static final Set<String> ADMIN_SUFFIX = Set.of("source", "url", "confidence", "verified", "audit", "status", "score", "id");

    /** Ported from normalize.ts's `readable()`: flattens a nested intelligence blob into short strings,
     *  dropping administrative/provenance-looking fields. */
    private List<String> readable(JsonNode v, int depth) {
        if (v == null || v.isMissingNode() || v.isNull() || depth > 2) return List.of();
        if (v.isArray()) {
            List<String> out = new ArrayList<>();
            int n = 0;
            for (JsonNode item : v) {
                if (n++ >= 4) break;
                out.addAll(readable(item, depth + 1));
            }
            return out;
        }
        if (v.isObject()) {
            List<String> out = new ArrayList<>();
            int n = 0;
            var it = v.fields();
            while (it.hasNext() && n < 4) {
                var e = it.next();
                if (ADMIN_SUFFIX.stream().anyMatch(suffix -> e.getKey().endsWith(suffix))) continue;
                n++;
                for (String t : readable(e.getValue(), depth + 1)) out.add(WarehouseText.label(e.getKey()) + ": " + t);
            }
            return out;
        }
        if (v.isBoolean()) return List.of(v.asBoolean() ? "Yes" : "No");
        String t = WarehouseText.cleanText(v.isTextual() ? v.asText() : v.toString(), 240);
        return t == null ? List.of() : List.of(t);
    }

    // ---- buildComparison (comparison.ts) -------------------------------------------------

    public WarehouseComparison buildComparison(List<WarehouseCourse> courses, List<ProviderMatch> providerMatches,
                                                List<WarehouseComparison.Qualification> qualifications, boolean courseRequested) {
        WarehouseComparison out = new WarehouseComparison();
        Map<String, ProviderMatch.ProviderRecord> byId = new LinkedHashMap<>();
        for (ProviderMatch m : providerMatches) if ("MATCHED".equals(m.getStatus())) for (var p : m.getProviders()) byId.putIfAbsent(p.getId(), p);
        List<ProviderMatch.ProviderRecord> providers = new ArrayList<>(byId.values());

        java.util.function.Function<WarehouseCourse, ProviderMatch.ProviderRecord> provider = c -> providers.stream()
            .filter(p -> p.getId().equals(c.getProviderId()) || p.getName().equals(c.getProvider())).findFirst().orElse(null);

        List<WarehouseComparison.Option> options = new ArrayList<>();
        if (!courses.isEmpty()) {
            for (WarehouseCourse c : courses) {
                String subtitle = java.util.stream.Stream.of(c.getName(), c.getCode()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" · "));
                options.add(new WarehouseComparison.Option(c.getId(), c.getProvider(), subtitle));
            }
        } else {
            for (var p : providers) options.add(new WarehouseComparison.Option(p.getId(), p.getName(), p.getRtoCode() != null ? "RTO " + p.getRtoCode() : "Provider"));
        }
        out.setOptions(options);

        Set<String> codes = courses.stream().map(WarehouseCourse::getCode).filter(c -> c != null).collect(Collectors.toCollection(LinkedHashSet::new));
        boolean same = courses.size() > 1 && codes.size() == 1 && courses.stream().allMatch(c -> codes.iterator().next().equals(c.getCode()));

        List<WarehouseComparison.Row> rows = new ArrayList<>();
        if (!courses.isEmpty()) {
            addRow(rows, "duration", "Duration", "COURSE_RECORD", courses.stream().map(c -> single(c.getDuration())).toList(),
                "Compare the time commitment alongside study load and attendance; a shorter course is not automatically better.");
            addRow(rows, "delivery", "How and where you study", "COURSE_RECORD", courses.stream().map(c -> {
                List<String> v = new ArrayList<>(c.getDelivery());
                v.addAll(c.getLocations().stream().limit(4).toList());
                return v;
            }).toList(), "Use the course locations shown. Other campuses do not establish delivery at that campus.");
            addRow(rows, "attendance", "Attendance and practical setup", "YUZEE_ANALYSIS",
                courses.stream().map(c -> c.getComparisonDetails().getAttendance()).toList(),
                "Consider whether the recorded attendance pattern fits your work and other commitments.");
            addRow(rows, "assessment", "Assessment approach", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getAssessments).toList(),
                "Look at how learning can be demonstrated. Broad assessment descriptions may be shared across the qualification.");
            addRow(rows, "practice", "Practical experience", "YUZEE_ANALYSIS", courses.stream().map(c -> c.getComparisonDetails().getPractice()).toList(),
                "Separate a recorded placement arrangement from Yuzee suggestions for building practical evidence.");
            addRow(rows, "support", "Learner support", "PROVIDER_RECORD", courses.stream().map(c -> {
                var p = provider.apply(c);
                return p == null ? List.<String>of() : p.getSupport();
            }).toList(), "Recorded provider services may help you study; the level of support for this course is not established by a tick alone.");
            addRow(rows, "credit", "Credit and recognition", "YUZEE_ANALYSIS", courses.stream().map(c -> c.getComparisonDetails().getCredit()).toList(),
                "Recognition and credit depend on your evidence and the provider decision.");
            addRow(rows, "cost", "Recorded tuition", "COURSE_RECORD", courses.stream().map(c -> {
                List<String> v = new ArrayList<>();
                if (c.getFees().getDomestic() != null) v.add("Domestic: " + c.getFees().getDomestic());
                if (c.getFees().getInternational() != null) v.add("International: " + c.getFees().getInternational());
                return v;
            }).toList(), "Compare like student categories and funding conditions. Not supplied never means free.");
            addRow(rows, "strengths", "Who it may suit", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getBestFor).toList(),
                "Use Yuzee analysis against your priorities; this is not an established provider advantage.");
            addRow(rows, "limits", "Trade-offs and further learning", "YUZEE_ANALYSIS", courses.stream().map(WarehouseCourse::getConsiderations).toList(),
                "Common qualification limits apply to all comparable options; do not treat them as a weakness unique to one RTO.");
        } else {
            addRow(rows, "area", "Provider base", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getArea())).toList(),
                "An institution address does not establish where a particular course runs.");
            addRow(rows, "type", "Provider type", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getType())).toList(),
                "Provider type describes the institution, not a ranking of teaching quality.");
            addRow(rows, "support", "Learner support", "PROVIDER_RECORD", providers.stream().map(ProviderMatch.ProviderRecord::getSupport).toList(),
                "Use these recorded services as discussion points for your study needs.");
            addRow(rows, "about", "Provider overview", "PROVIDER_RECORD", providers.stream().map(p -> single(p.getDescription())).toList(),
                "Compare the stated focus without treating description length as quality.");
        }
        out.setRows(rows);

        List<String> notes = new ArrayList<>();
        if (courseRequested) {
            for (var p : providers) {
                boolean matched = courses.stream().anyMatch(c -> p.getId().equals(c.getProviderId()) || p.getName().equals(c.getProvider()));
                if (!matched) notes.add("No matching course was returned for " + p.getName() + "; their provider details alone do not establish that they offer the requested course.");
            }
        }
        for (WarehouseCourse c : courses) if (c.getEvidenceIssues() != null && !c.getEvidenceIssues().isEmpty()) notes.add(c.getEvidenceIssues().get(0));
        out.setNotes(notes);

        out.setProviderMatches(providerMatches);
        out.setQualifications(qualifications);
        out.setTitle(!courses.isEmpty() ? "Compare the learning experience" : "Compare these providers");
        out.setBaseline(same
            ? "These options share " + codes.iterator().next() + ". The national qualification is the common starting point; compare the recorded delivery and learner experience below."
            : courses.size() > 1
                ? "These records are not all the same qualification. Compare level and intended outcome before interpreting differences as provider quality."
                : "Use the available provider details now. A named qualification enables a more specific comparison of learning and delivery.");
        return out;
    }

    private List<String> single(String v) { return v == null ? List.of() : List.of(v); }

    private void addRow(List<WarehouseComparison.Row> rows, String key, String label, String basis, List<List<String>> values, String meaning) {
        List<List<String>> cells = values.stream().map(v -> v == null ? List.<String>of() : v.stream().filter(x -> x != null && !x.isBlank()).toList()).toList();
        List<String> canonical = cells.stream().map(v -> v.stream().map(x -> x.trim().toLowerCase().replaceAll("\\s+", " ")).sorted().collect(Collectors.joining("|"))).toList();
        boolean allEmpty = cells.stream().allMatch(List::isEmpty);
        boolean someEmpty = cells.stream().anyMatch(List::isEmpty);
        String status = allEmpty ? "UNKNOWN" : someEmpty ? "INCOMPLETE" : (new LinkedHashSet<>(canonical).size() == 1 && cells.size() > 1) ? "SHARED" : "DIFFERENT_RECORDS";
        WarehouseComparison.Row row = new WarehouseComparison.Row();
        row.setKey(key); row.setLabel(label); row.setBasis(basis); row.setStatus(status); row.setValues(cells); row.setMeaning(meaning);
        rows.add(row);
    }

    // ---- explorationChoice (choices.ts) --------------------------------------------------

    public ExplorationChoice.SkillState toSkillState(String id, String name, String state) { return new ExplorationChoice.SkillState(id, name, state); }

    public record ChoiceResult(ExplorationChoice choice, String text) {}

    public ChoiceResult explorationChoice(WarehousePack pack, List<String> roleIds, List<ExplorationChoice.SkillState> requested) {
        WarehouseExploration e = pack == null || pack.getConnected() == null ? null : pack.getConnected().getExploration();
        if (e == null || roleIds == null || requested == null || roleIds.size() > 3 || requested.size() > 12) {
            throw new IllegalArgumentException("Choose roles and skills shown in this workspace.");
        }
        if (new LinkedHashSet<>(roleIds).size() != roleIds.size()
            || requested.stream().map(ExplorationChoice.SkillState::getId).collect(Collectors.toSet()).size() != requested.size()) {
            throw new IllegalArgumentException("Choose each role or skill once.");
        }
        List<WarehouseExploration.Role> roles = new ArrayList<>();
        for (String id : roleIds) {
            WarehouseExploration.Role r = e.getRoles().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose a role shown in this workspace."));
            roles.add(r);
        }
        List<ExplorationChoice.SkillState> skills = new ArrayList<>();
        for (var s : requested) {
            var skill = e.getSkills().stream().filter(k -> k.getId().equals(s.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose a skill shown in this workspace."));
            if (!Set.of("HAVE", "LEARN", "UNSURE").contains(s.getState())) throw new IllegalArgumentException("Choose a skill shown in this workspace.");
            skills.add(new ExplorationChoice.SkillState(skill.getId(), skill.getName(), s.getState()));
        }
        ExplorationChoice choice = new ExplorationChoice();
        choice.setRoleIds(roles.stream().map(WarehouseExploration.Role::getId).toList());
        choice.setSkills(skills);

        List<String> phrases = new ArrayList<>();
        phrases.add(!roles.isEmpty() ? "I want to explore these roles: " + roles.stream().map(WarehouseExploration.Role::getTitle).collect(Collectors.joining("; ")) + "."
            : "I have not selected a target role yet.");
        addSkillPhrase(phrases, skills, "HAVE", "I say I have experience using");
        addSkillPhrase(phrases, skills, "LEARN", "I want to learn");
        addSkillPhrase(phrases, skills, "UNSURE", "I am unsure about my experience with");
        String text = String.join(" ", phrases) + " These replace my previous workspace skill selections. Unmarked skills remain unknown. "
            + "My reported skills are not verified competence. Help me connect the work, relevant learning options and the recorded demand in my area.";
        return new ChoiceResult(choice, text);
    }

    private void addSkillPhrase(List<String> phrases, List<ExplorationChoice.SkillState> skills, String state, String label) {
        List<String> names = skills.stream().filter(s -> state.equals(s.getState())).map(ExplorationChoice.SkillState::getName).toList();
        if (!names.isEmpty()) phrases.add(label + ": " + String.join("; ", names) + ".");
    }

    public List<WarehouseCourse> workspaceCourses(WarehousePack pack) {
        List<WarehouseCourse> out = new ArrayList<>();
        if (pack == null) return out;
        out.addAll(pack.getCourses());
        if (pack.getConnected() != null && pack.getConnected().getExploration() != null) {
            // learning links only carry lightweight course refs (id/name/provider), not full records;
            // callers that need full WarehouseCourse objects should re-fetch by id via lookup().
        }
        return out;
    }

    // ---- linked-data expansion (linked-data.cjs's createLinkedReader) -------------------

    /** Inner (not static) so it can share the per-lookup {@link Session} without re-plumbing a
     *  DataSource pair through yet another constructor. One instance per {@code lookup()} call. */
    private final class LinkedDataExpansion {
        private final Session s;
        private final WarehouseQueryPlan plan;
        private final Set<String> facets;
        private final boolean allFacets;

        LinkedDataExpansion(Session s, WarehouseQueryPlan plan) {
            this.s = s;
            this.plan = plan;
            this.facets = new LinkedHashSet<>(plan.getFacets() == null ? List.of() : plan.getFacets());
            this.allFacets = !plan.hasExplicitFacets();
        }

        private boolean has(String facet) { return allFacets || facets.contains(facet); }

        WarehouseConnections read(List<Map<String, Object>> courses) {
            ResolvedLocation location = resolveLocation(plan.getLocation());
            WarehouseConnections out = new WarehouseConnections();
            out.getLocation().setRequested(location.requested);
            out.getLocation().setRegion(location.region == null ? null : regionRef(location.region));
            out.getLocation().setCandidates(location.candidates.stream().map(this::regionRef).toList());

            Map<String, WarehouseConnections.Career> careerMap = new LinkedHashMap<>();
            List<WarehouseConnections.Career> careers = out.getCareers();
            List<WarehouseConnections.ProviderProfile> providers = out.getProviders();
            List<WarehouseConnections.Industry> industries = out.getIndustries();
            List<WarehouseConnections.Signal> signals = out.getSignals();
            List<WarehouseConnections.Relationship> relationships = out.getRelationships();
            Set<String> signalIds = new LinkedHashSet<>();

            java.util.function.BiConsumer<String, WarehouseConnections.Signal> addSignal = (id, sig) -> {
                if (signalIds.size() < 18 && signalIds.add(id)) { sig.setId(id); sig.setEvidenceId("warehouse_" + id); signals.add(sig); }
            };

            // addCareer(code, title, link): builds (or reuses) one career entry, tracking the course->career relationship.
            java.util.function.BiFunction<String[], WarehouseConnections.CourseLink, WarehouseConnections.Career> addCareer = (codeTitle, link) -> {
                String code = codeTitle[0];
                String title = codeTitle.length > 1 ? codeTitle[1] : null;
                if (code == null || code.isBlank()) return null;
                if (link == null && careers.stream().anyMatch(c -> code.equals(c.getGroupCode()))) return careerMap.get(code);
                if (careerMap.size() >= 6 && !careerMap.containsKey(code)) return null;
                WarehouseConnections.Career c = careerMap.get(code);
                if (c == null) {
                    c = buildCareer(code, title);
                    careerMap.put(code, c);
                    careers.add(c);
                }
                if (link != null && c.getCourseLinks().stream().noneMatch(x -> x.getCourseId().equals(link.getCourseId()))) {
                    c.getCourseLinks().add(link);
                    relationships.add(new WarehouseConnections.Relationship("course:" + link.getCourseId(), "career:" + code, "related_career", link.getMethod(), link.getConfidence()));
                }
                return c;
            };

            for (Map<String, Object> course : courses) {
                String id = String.valueOf(course.get("id"));
                String code = firstNonNull(str(course.get("national_code")), str(course.get("course_code")), "");
                if (has("PROVIDER") || has("LOCAL") || has("FUNDING")) {
                    expandProvider(course, id, code, location, providers, relationships);
                }
                if (has("CAREERS") || has("LOCAL") || has("INDUSTRY")) {
                    expandCareerLinks(course, id, code, addCareer, industries);
                }
            }
            for (String phrase : firstN(plan.getOccupationQueries(), 2)) expandOccupationQuery(phrase, addCareer);
            for (String phrase : firstN(plan.getIndustryQueries(), 2)) expandIndustryQuery(phrase, courses, addCareer, industries);

            WarehouseExploration exploration = new OpportunityReader(s).read(plan, location);
            out.setExploration(exploration);
            if (exploration != null) for (var role : exploration.getRoles()) {
                if (!role.getMappings().isEmpty()) {
                    var mapping = role.getMappings().get(0);
                    addCareer.apply(new String[]{mapping.getAnzscoCode(), mapping.getAnzscoTitle()}, null);
                }
            }

            List<ResolvedLocation.RegionRow> chain = location.chain.stream().filter(r -> !"NATIONAL".equals(r.tier)).toList();
            boolean noNamedTargets = plan.getOccupationQueries().isEmpty() && plan.getSkillQueries().isEmpty()
                && plan.getRoleQueries().isEmpty() && plan.getJobQueries().isEmpty();
            if (has("LOCAL") && careers.isEmpty() && noNamedTargets) {
                for (ResolvedLocation.RegionRow region : chain) {
                    List<Map<String, Object>> rows = s.source.queryForList(
                        "SELECT * FROM region_demand_edge WHERE region_key=? AND period=(SELECT MAX(period) FROM region_demand_edge WHERE region_key=?) AND active_jobs>0 ORDER BY active_jobs DESC LIMIT 5",
                        region.key, region.key);
                    for (var r : rows) addCareer.apply(new String[]{str(r.get("anzsco_code")), archetype(r.get("evidence_json"))}, null);
                    if (!rows.isEmpty()) break;
                }
            }

            for (WarehouseConnections.Career career : new ArrayList<>(careers)) {
                if (has("LOCAL") || has("CAREERS")) addDemandSignals(career, chain, location, addSignal);
                if (has("INDUSTRY") && s.has("dim_industry")) {
                    for (var r : s.source.queryForList(
                            "SELECT x.anzsic_code,d.industry_name,x.source FROM dim_crosswalk_anzsic_anzsco x JOIN dim_industry d ON d.anzsic_code=x.anzsic_code WHERE x.anzsco_code=? LIMIT 3",
                            career.getGroupCode())) {
                        WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                        ind.setId(career.getId() + "_" + r.get("anzsic_code"));
                        ind.setEvidenceId("warehouse_industry_" + career.getId() + "_" + r.get("anzsic_code"));
                        ind.setName(WarehouseText.text(r.get("industry_name")));
                        ind.setCareerId(career.getId());
                        ind.setMethod(WarehouseText.text(r.get("source")));
                        ind.setScope("Occupation-to-industry mapping");
                        industries.add(ind);
                    }
                }
            }

            if (has("LOCAL") && location.region != null && "SA2".equals(location.region.tier)) {
                for (var r : s.source.queryForList(
                        "SELECT row_id,data_item,quarter,value,source FROM salm_unemployment_sa2 WHERE sa2_code=? AND is_unavailable=0 " +
                        "ORDER BY quarter_date DESC, CASE WHEN lower(data_item) LIKE '%rate%' THEN 0 ELSE 1 END LIMIT 2",
                        location.region.key)) {
                    WarehouseConnections.Signal sig = new WarehouseConnections.Signal();
                    sig.setKind("LOCAL_CONTEXT");
                    sig.setTitle(WarehouseText.text(r.get("data_item")));
                    String value = String.valueOf(r.get("value"));
                    sig.setText(value + (String.valueOf(r.get("data_item")).toLowerCase().contains("rate") ? "%" : ""));
                    sig.setScope("SA2");
                    sig.setRegion(location.region.name);
                    sig.setPeriod(str(r.get("quarter")));
                    sig.setSource(WarehouseText.text(r.get("source")));
                    sig.setMethod("Exact region key");
                    sig.setLocalMatch(true);
                    addSignal.accept("local_" + r.get("row_id"), sig);
                }
            }

            out.setLocalOverview(has("LOCAL") ? localOverview(location) : null);
            out.setIndustries(industries.stream().limit(12).toList());
            return out;
        }

        private String archetype(Object evidenceJson) {
            JsonNode ev = WarehouseText.json(evidenceJson);
            return WarehouseText.text(ev.path("archetype"));
        }

        private void addDemandSignals(WarehouseConnections.Career career, List<ResolvedLocation.RegionRow> chain,
                                       ResolvedLocation location, java.util.function.BiConsumer<String, WarehouseConnections.Signal> addSignal) {
            for (ResolvedLocation.RegionRow region : chain) {
                var demand = s.source.queryForList("SELECT * FROM region_demand_edge WHERE region_key=? AND anzsco_code=? ORDER BY period DESC LIMIT 1", region.key, career.getGroupCode());
                if (!demand.isEmpty()) {
                    var r = demand.get(0);
                    WarehouseConnections.Signal sig = new WarehouseConnections.Signal();
                    sig.setKind("RECORDED_DEMAND");
                    sig.setCareerId(career.getId());
                    sig.setTitle("Recorded demand for " + career.getTitle());
                    sig.setText(r.get("active_jobs") + " recorded job advertisements; " + r.get("employer_count") + " employers in the stored signal.");
                    WarehouseConnections.Metrics m = new WarehouseConnections.Metrics();
                    m.setAdvertisements(WarehouseText.intNumber(r.get("active_jobs")));
                    m.setEmployers(WarehouseText.intNumber(r.get("employer_count")));
                    sig.setMetrics(m);
                    sig.setScope(firstNonNull(str(r.get("scope")), region.tier));
                    sig.setRegion(region.name);
                    sig.setPeriod(str(r.get("period")));
                    sig.setSource(str(r.get("source_name")));
                    sig.setMethod(str(r.get("method")));
                    sig.setLocalMatch(location.region != null && region.key.equals(location.region.key));
                    sig.setUpdatedAt(str(r.get("updated_at")));
                    addSignal.accept("demand_" + r.get("edge_id"), sig);
                    break;
                }
            }
            var projections = location.region == null ? List.<Map<String, Object>>of() : s.source.queryForList(
                "SELECT * FROM jsa_employment_projection WHERE anzsco_code=? AND (state_code=? OR state_code IN ('AUS','ALL')) ORDER BY release_date DESC,projection_horizon ASC LIMIT 2",
                career.getGroupCode(), firstNonNull(location.region.state, "AUS"));
            for (var r : projections) {
                WarehouseConnections.Signal sig = new WarehouseConnections.Signal();
                sig.setKind("PROJECTION");
                sig.setCareerId(career.getId());
                sig.setTitle("Employment outlook for " + career.getTitle());
                sig.setText("Stored projection: " + r.get("growth_pct") + "% employment change over " + r.get("projection_horizon") + " years from " + r.get("projection_year") + ".");
                WarehouseConnections.Metrics m = new WarehouseConnections.Metrics();
                m.setGrowthPercent(WarehouseText.number(r.get("growth_pct")));
                m.setHorizonYears(WarehouseText.intNumber(r.get("projection_horizon")));
                m.setBaseYear(WarehouseText.intNumber(r.get("projection_year")));
                sig.setMetrics(m);
                sig.setScope(Set.of("AUS", "ALL").contains(str(r.get("state_code"))) ? "NATIONAL" : "STATE");
                sig.setRegion(str(r.get("state_code")));
                sig.setPeriod(str(r.get("release_date")));
                sig.setSource("Jobs and Skills Australia");
                sig.setMethod("Stored employment projection");
                sig.setLocalMatch(false);
                sig.setUpdatedAt(str(r.get("updated_at")));
                addSignal.accept("projection_" + r.get("projection_id"), sig);
            }
            if (location.region != null && location.region.state != null) {
                for (var r : s.source.queryForList(
                        "SELECT edge_id,employer_name,scope,period,state_code,region_key,updated_at,method FROM employer_job_edge WHERE anzsco_code=? AND state_code=? AND is_active=1 ORDER BY period DESC LIMIT 3",
                        career.getGroupCode(), location.region.state)) {
                    WarehouseConnections.Signal sig = new WarehouseConnections.Signal();
                    sig.setKind("EMPLOYER_SIGNAL");
                    sig.setCareerId(career.getId());
                    sig.setTitle(WarehouseText.text(r.get("employer_name")));
                    sig.setText("Employer recorded against " + career.getTitle() + ".");
                    sig.setScope(firstNonNull(str(r.get("scope")), "STATE"));
                    sig.setRegion(str(r.get("state_code")));
                    sig.setPeriod(str(r.get("period")));
                    sig.setSource("Stored employer–occupation relationship");
                    sig.setMethod(str(r.get("method")));
                    sig.setLocalMatch(r.get("region_key") != null && r.get("region_key").equals(location.region.key));
                    sig.setUpdatedAt(str(r.get("updated_at")));
                    addSignal.accept("employer_" + r.get("edge_id"), sig);
                }
            }
        }

        private void expandProvider(Map<String, Object> course, String id, String code, ResolvedLocation location,
                                     List<WarehouseConnections.ProviderProfile> providers, List<WarehouseConnections.Relationship> relationships) {
            if (!s.has("live_institutions")) return;
            Object institutionId = course.get("institution_id");
            var rows = s.source.queryForList(
                "SELECT id,legal_name,rto_code,rto_type,higher_education_code,city,state,website_url,has_student_support,has_disability_support,has_library,has_apprenticeships " +
                "FROM live_institutions WHERE id=?", institutionId == null ? "" : institutionId);
            if (rows.isEmpty()) return;
            var p = rows.get(0);
            String providerId = String.valueOf(p.get("id"));
            WarehouseConnections.ProviderProfile existing = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
            if (existing == null) {
                WarehouseConnections.ProviderProfile profile = new WarehouseConnections.ProviderProfile();
                profile.setId(providerId);
                profile.setEvidenceId("warehouse_provider_" + providerId);
                profile.setName(WarehouseText.text(p.get("legal_name")));
                profile.setType(WarehouseText.text(p.get("rto_type")));
                profile.setHigherEducationCode(WarehouseText.text(p.get("higher_education_code")));
                profile.setCity(WarehouseText.text(p.get("city")));
                profile.setState(WarehouseText.text(p.get("state")));
                String rtoCode = str(p.get("rto_code"));
                if (s.has("institution_campuses")) {
                    for (var c : s.source.queryForList("SELECT location_name,town,state,postcode FROM institution_campuses WHERE rto_code=? LIMIT 8", firstNonNull(rtoCode, ""))) {
                        WarehouseConnections.Campus campus = new WarehouseConnections.Campus();
                        campus.setName(WarehouseText.text(c.get("location_name")));
                        campus.setTown(WarehouseText.text(c.get("town")));
                        campus.setState(WarehouseText.text(c.get("state")));
                        campus.setPostcode(WarehouseText.text(c.get("postcode")));
                        profile.getCampuses().add(campus);
                    }
                }
                List<String> support = new ArrayList<>();
                addFlagLabel(support, p, "has_student_support", "Student support");
                addFlagLabel(support, p, "has_disability_support", "Disability support");
                addFlagLabel(support, p, "has_library", "Library");
                addFlagLabel(support, p, "has_apprenticeships", "Apprenticeship support");
                profile.setSupport(support);
                if (s.has("he_provider_entitlement_v2")) {
                    var he = s.source.queryForList(
                        "SELECT csp_undergraduate,csp_postgraduate,hecs_help,fee_help,effective_from,effective_to,current_status " +
                        "FROM he_provider_entitlement_v2 WHERE institution_id=? ORDER BY effective_from DESC LIMIT 1", p.get("id"));
                    if (!he.isEmpty()) {
                        var h = he.get(0);
                        Map<String, Object> funding = new LinkedHashMap<>();
                        funding.put("cspUndergraduate", WarehouseText.flag(h.get("csp_undergraduate")));
                        funding.put("cspPostgraduate", WarehouseText.flag(h.get("csp_postgraduate")));
                        funding.put("hecsHelp", WarehouseText.flag(h.get("hecs_help")));
                        funding.put("feeHelp", WarehouseText.flag(h.get("fee_help")));
                        funding.put("from", h.get("effective_from"));
                        funding.put("to", h.get("effective_to"));
                        funding.put("status", str(h.get("current_status")));
                        profile.setHigherEducationFunding(funding);
                    }
                }
                profile.getCourseIds().add(id);
                providers.add(profile);
                existing = profile;
            } else if (!existing.getCourseIds().contains(id)) {
                existing.getCourseIds().add(id);
            }
            if (has("FUNDING") && !code.isBlank() && s.has("course_funding_verdict")) {
                String rtoCode = str(p.get("rto_code"));
                String state = location.region == null ? null : location.region.state;
                var funding = state != null
                    ? s.source.queryForList("SELECT scheme,delivery_state,funding_status,student_tuition_out_of_pocket,currency,effective_period FROM course_funding_verdict WHERE rto_code=? AND national_code=? AND delivery_state=? ORDER BY effective_period DESC LIMIT 3", firstNonNull(rtoCode, ""), code, state)
                    : s.source.queryForList("SELECT scheme,delivery_state,funding_status,student_tuition_out_of_pocket,currency,effective_period FROM course_funding_verdict WHERE rto_code=? AND national_code=? ORDER BY effective_period DESC LIMIT 3", firstNonNull(rtoCode, ""), code);
                for (var f : funding) {
                    Map<String, Object> entry = new LinkedHashMap<>(f);
                    entry.put("courseId", id);
                    entry.put("courseName", WarehouseText.text(course.get("course_name")));
                    existing.getFunding().add(entry);
                }
            }
            relationships.add(new WarehouseConnections.Relationship("course:" + id, "provider:" + providerId, "offered_by", "institution_id", null));
        }

        private void expandCareerLinks(Map<String, Object> course, String id, String code,
                                        java.util.function.BiFunction<String[], WarehouseConnections.CourseLink, WarehouseConnections.Career> addCareer,
                                        List<WarehouseConnections.Industry> industries) {
            if (s.has("course_occupation_edge")) {
                for (var edge : s.source.queryForList(
                        "SELECT anzsco_code,anzsco_title,method,confidence_score FROM course_occupation_edge WHERE national_code=? ORDER BY is_primary DESC,confidence_score DESC LIMIT 3", code)) {
                    addCareer.apply(new String[]{str(edge.get("anzsco_code")), str(edge.get("anzsco_title"))},
                        new WarehouseConnections.CourseLink(id, WarehouseText.text(course.get("course_name")), str(edge.get("method")), WarehouseText.number(edge.get("confidence_score"))));
                }
            }
            var mappings = s.index.queryForList(
                "SELECT occupation_code AS anzsco_code,occupation_name AS anzsco_title,match_method AS method,match_confidence AS confidence_score " +
                "FROM occupation_links WHERE qualification_code=? ORDER BY is_primary DESC LIMIT 3", firstNonNull(code, ""));
            for (var edge : mappings) {
                addCareer.apply(new String[]{str(edge.get("anzsco_code")), str(edge.get("anzsco_title"))},
                    new WarehouseConnections.CourseLink(id, WarehouseText.text(course.get("course_name")), str(edge.get("method")), WarehouseText.number(edge.get("confidence_score"))));
            }
            var profileRows = s.index.queryForList("SELECT * FROM profile_links WHERE course_id=? LIMIT 1", id);
            Map<String, Object> profile = profileRows.isEmpty() ? null : profileRows.get(0);
            if (profile != null) {
                for (String occ : WarehouseText.list(profile.get("anzsco_codes_json"), 3)) {
                    addCareer.apply(new String[]{occ, ""},
                        new WarehouseConnections.CourseLink(id, WarehouseText.text(course.get("course_name")), str(profile.get("mapping_method")), WarehouseText.number(profile.get("mapping_confidence"))));
                }
                if (has("INDUSTRY")) {
                    for (String name : WarehouseText.list(profile.get("industry_codes_json"), 4)) {
                        String industryId = id + "_" + industries.size();
                        if (industries.stream().noneMatch(i -> name.equals(i.getName()) && id.equals(i.getCourseId()))) {
                            WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                            ind.setId(industryId);
                            ind.setEvidenceId("warehouse_industry_" + industryId);
                            ind.setName(name);
                            ind.setCourseId(id);
                            ind.setMethod(str(profile.get("mapping_method")));
                            ind.setScope("Course-to-industry mapping");
                            industries.add(ind);
                        }
                    }
                }
            }
        }

        private void expandOccupationQuery(String phrase, java.util.function.BiFunction<String[], WarehouseConnections.CourseLink, WarehouseConnections.Career> addCareer) {
            if (!s.has("canonical_occupation")) return;
            List<String> words = firstN(java.util.Arrays.asList(WarehouseText.text(phrase, 100).split("\\s+")), 4);
            if (words.isEmpty() || words.get(0).isBlank()) return;
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (String w : words) {
                if (!where.isEmpty()) where.append(" AND ");
                where.append("anzsco_title LIKE ? ESCAPE '\\'");
                args.add("%" + WarehouseText.safeLike(w) + "%");
            }
            for (var row : s.source.queryForList("SELECT anzsco_code,anzsco_title FROM canonical_occupation WHERE " + where + " LIMIT 3", args.toArray())) {
                addCareer.apply(new String[]{str(row.get("anzsco_code")), str(row.get("anzsco_title"))}, null);
            }
        }

        private void expandIndustryQuery(String phrase, List<Map<String, Object>> courses,
                                          java.util.function.BiFunction<String[], WarehouseConnections.CourseLink, WarehouseConnections.Career> addCareer,
                                          List<WarehouseConnections.Industry> industries) {
            List<String> words = firstN(java.util.Arrays.asList(WarehouseText.text(phrase, 100).split("\\s+")), 4);
            if (words.isEmpty() || words.get(0).isBlank()) return;
            StringBuilder likeWhere = new StringBuilder();
            List<Object> likeArgs = new ArrayList<>();
            for (String w : words) {
                if (!likeWhere.isEmpty()) likeWhere.append(" AND ");
                likeWhere.append("industry_name LIKE ? ESCAPE '\\'");
                likeArgs.add("%" + WarehouseText.safeLike(w) + "%");
            }
            for (var r : s.index.queryForList("SELECT * FROM industry_links WHERE " + likeWhere + " LIMIT 8", likeArgs.toArray())) {
                if (industries.stream().noneMatch(x -> r.get("industry_name").equals(x.getName()))) {
                    WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                    ind.setId("mapped_" + r.get("course_id"));
                    ind.setEvidenceId("warehouse_industry_mapped_" + r.get("course_id"));
                    ind.setName(str(r.get("industry_name")));
                    ind.setMethod(str(r.get("mapping_method")));
                    ind.setScope("Stored course-to-industry mapping");
                    industries.add(ind);
                }
                if (courses.isEmpty()) {
                    var profileRows = s.index.queryForList("SELECT * FROM profile_links WHERE course_id=? LIMIT 1", r.get("course_id"));
                    if (!profileRows.isEmpty()) {
                        for (String code : WarehouseText.list(profileRows.get(0).get("anzsco_codes_json"), 3)) addCareer.apply(new String[]{code, ""}, null);
                    }
                    for (var occ : s.index.queryForList("SELECT occupation_code,occupation_name FROM occupation_links WHERE qualification_code=? ORDER BY is_primary DESC LIMIT 2", r.get("qualification_code"))) {
                        addCareer.apply(new String[]{str(occ.get("occupation_code")), str(occ.get("occupation_name"))}, null);
                    }
                }
            }
            if (s.has("dim_industry")) {
                for (var r : s.source.queryForList("SELECT anzsic_code,industry_name FROM dim_industry WHERE " + likeWhere + " LIMIT 3", likeArgs.toArray())) {
                    WarehouseConnections.Industry ind = new WarehouseConnections.Industry();
                    ind.setId("sector_" + r.get("anzsic_code"));
                    ind.setEvidenceId("warehouse_industry_sector_" + r.get("anzsic_code"));
                    ind.setName(WarehouseText.text(r.get("industry_name")));
                    ind.setMethod("Named industry match");
                    ind.setScope("Industry catalogue");
                    industries.add(ind);
                    if (courses.isEmpty() && s.has("dim_crosswalk_anzsic_anzsco")) {
                        for (var link : s.source.queryForList("SELECT anzsco_code FROM dim_crosswalk_anzsic_anzsco WHERE anzsic_code=? LIMIT 3", r.get("anzsic_code"))) {
                            addCareer.apply(new String[]{str(link.get("anzsco_code")), ""}, null);
                        }
                    }
                }
            }
        }

        private WarehouseConnections.Career buildCareer(String code, String title) {
            var exactRows = s.has("canonical_occupation") ? s.source.queryForList("SELECT * FROM canonical_occupation WHERE anzsco_code=?", code) : List.<Map<String, Object>>of();
            Map<String, Object> exact = exactRows.isEmpty() ? null : exactRows.get(0);
            Map<String, Object> group = null;
            if (exact == null && code.length() > 4 && s.has("canonical_occupation")) {
                var groupRows = s.source.queryForList("SELECT * FROM canonical_occupation WHERE anzsco_code=?", code.substring(0, 4));
                group = groupRows.isEmpty() ? null : groupRows.get(0);
            }
            Map<String, Object> canonical = exact != null ? exact : group;
            String key = canonical != null ? str(canonical.get("anzsco_code")) : code;
            Map<String, Object> onet = null;
            if (s.has("onet_occupation") && s.has("onet_anzsco_crosswalk")) {
                var rows = s.source.queryForList(
                    "SELECT x.job_id,x.method,x.confidence,o.job_title,o.description,o.tasks,o.work_styles FROM onet_anzsco_crosswalk x " +
                    "JOIN onet_occupation o ON o.job_id=x.job_id WHERE x.anzsco_code=? ORDER BY x.confidence DESC,o.job_title ASC LIMIT 2", key);
                onet = rows.isEmpty() ? null : rows.get(0);
            }
            WarehouseConnections.Career c = new WarehouseConnections.Career();
            c.setId(code);
            c.setEvidenceId("warehouse_career_" + code);
            c.setTitle(WarehouseText.text(firstNonNull(exact != null ? str(exact.get("anzsco_title")) : null, title, canonical != null ? str(canonical.get("anzsco_title")) : null, code)));
            c.setDescription(WarehouseText.text(firstNonNull(exact != null ? str(exact.get("description")) : null, onet != null ? str(onet.get("description")) : null)));
            c.setTasks(onet != null ? WarehouseText.list(onet.get("tasks"), 5) : List.of());
            c.setWorkStyles(onet != null ? WarehouseText.list(onet.get("work_styles"), 3) : List.of());
            if (onet != null) {
                c.setSkills(s.source.queryForList("SELECT skill_name FROM onet_job_skill WHERE job_id=? LIMIT 6", onet.get("job_id")).stream()
                    .map(r -> WarehouseText.text(r.get("skill_name"))).toList());
            }
            c.setProfileScope(group != null ? "Occupation-group context: " + group.get("anzsco_title") + " (" + key + ")" : "Occupation profile");
            c.setProfileMethod(str(firstNonNull(onet != null ? str(onet.get("method")) : null, canonical != null ? str(canonical.get("source")) : null)));
            c.setProfileSource(onet != null ? "O*NET occupation profile and stored ANZSCO crosswalk" : str(canonical != null ? canonical.get("source") : null));
            c.setGroupCode(key);
            return c;
        }

        // ---- location resolution (linked-data.cjs's resolveLocation/localOverview) --------

        private ResolvedLocation resolveLocation(WarehouseQueryPlan.LocationQuery input) {
            String name = input == null ? "" : WarehouseText.text(input.getName(), 100);
            String postcode = input != null && input.getPostcode() != null && input.getPostcode().matches("\\d{4}") ? input.getPostcode() : "";
            String state = input != null && STATES.contains(input.getState()) ? input.getState() : "";
            ResolvedLocation out = new ResolvedLocation();
            if (name.isBlank() && postcode.isBlank() && state.isBlank()) return out;
            if (!s.has("region_spine")) { out.requested = String.join(", ", nonBlank(name, postcode, state)); return out; }

            List<Map<String, Object>> matches = new ArrayList<>();
            if (!name.isBlank()) {
                matches = state.isBlank()
                    ? s.source.queryForList("SELECT * FROM region_spine WHERE lower(name)=lower(?) LIMIT 6", name)
                    : s.source.queryForList("SELECT * FROM region_spine WHERE lower(name)=lower(?) AND state=? LIMIT 6", name, state);
            }
            if (matches.isEmpty() && !postcode.isBlank() && s.has("dim_location_asgs")) {
                var asgs = state.isBlank()
                    ? s.source.queryForList("SELECT DISTINCT sa2_code,sa4_code,state_code FROM dim_location_asgs WHERE postcode=? LIMIT 6", postcode)
                    : s.source.queryForList("SELECT DISTINCT sa2_code,sa4_code,state_code FROM dim_location_asgs WHERE postcode=? AND state_code=? LIMIT 6", postcode, state);
                for (var r : asgs) {
                    String rk = firstNonNull(str(r.get("sa2_code")), str(r.get("sa4_code")));
                    if (rk == null || rk.isBlank()) continue;
                    var m = s.source.queryForList("SELECT * FROM region_spine WHERE region_key=?", rk);
                    if (!m.isEmpty() && matches.stream().noneMatch(x -> rk.equals(str(x.get("region_key"))))) matches.add(m.get(0));
                }
            }
            if (matches.isEmpty() && !name.isBlank()) {
                matches = state.isBlank()
                    ? s.source.queryForList("SELECT * FROM region_spine WHERE name LIKE ? ESCAPE '\\' LIMIT 6", WarehouseText.safeLike(name) + " -%")
                    : s.source.queryForList("SELECT * FROM region_spine WHERE name LIKE ? ESCAPE '\\' AND state=? LIMIT 6", WarehouseText.safeLike(name) + " -%", state);
            }
            if (name.isBlank() && postcode.isBlank() && !state.isBlank()) {
                matches = s.source.queryForList("SELECT * FROM region_spine WHERE tier='STATE' AND (state=? OR region_key=?) LIMIT 2", state, state);
            }
            if (matches.size() > 1) {
                List<Map<String, Object>> locals = matches.stream().filter(r -> "SA2".equals(r.get("tier"))).toList();
                if (locals.size() == 1) {
                    Set<String> ancestorKeys = new LinkedHashSet<>();
                    Map<String, Object> parent = locals.get(0);
                    while (parent != null && ancestorKeys.size() < 6 && ancestorKeys.add(str(parent.get("region_key")))) {
                        Object parentKey = parent.get("parent_key");
                        parent = parentKey == null ? null : s.source.queryForList("SELECT * FROM region_spine WHERE region_key=?", parentKey).stream().findFirst().orElse(null);
                    }
                    if (matches.stream().allMatch(r -> ancestorKeys.contains(str(r.get("region_key"))))) matches = locals;
                }
            }
            Map<String, Object> regionRow = matches.size() == 1 ? matches.get(0) : null;
            out.candidates = matches.size() > 1 ? matches.stream().map(this::regionRow).toList() : List.of();
            List<ResolvedLocation.RegionRow> chain = new ArrayList<>();
            Map<String, Object> node = regionRow;
            Set<String> chainKeys = new LinkedHashSet<>();
            while (node != null && chain.size() < 5 && chainKeys.add(str(node.get("region_key")))) {
                chain.add(regionRow(node));
                Object parentKey = node.get("parent_key");
                node = parentKey == null ? null : s.source.queryForList("SELECT * FROM region_spine WHERE region_key=?", parentKey).stream().findFirst().orElse(null);
            }
            out.chain = chain;
            out.region = regionRow == null ? null : regionRow(regionRow);
            out.requested = String.join(", ", nonBlank(name, postcode, state));
            return out;
        }

        private List<String> nonBlank(String... values) { return java.util.Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList(); }

        private ResolvedLocation.RegionRow regionRow(Map<String, Object> r) {
            return new ResolvedLocation.RegionRow(str(r.get("region_key")), WarehouseText.text(r.get("name")), str(r.get("tier")), str(r.get("state")));
        }

        private WarehouseConnections.RegionRef regionRef(ResolvedLocation.RegionRow r) {
            return new WarehouseConnections.RegionRef(r.key, r.name, r.tier, r.state);
        }

        private WarehouseConnections.LocalOverview localOverview(ResolvedLocation location) {
            if (location.region == null) return null;
            ResolvedLocation.RegionRow region = location.region;
            WarehouseConnections.LocalOverview overview = new WarehouseConnections.LocalOverview();
            overview.setEvidenceId("warehouse_local_overview_" + region.key);
            overview.setArea(region.name);
            overview.setState(region.state);
            overview.setScope(region.tier);
            overview.setAncestors(location.chain.stream()
                .filter(r -> !r.key.equals(region.key) && Set.of("SA3", "SA4").contains(r.tier))
                .map(r -> new WarehouseConnections.Ancestor(r.name, r.tier)).toList());

            if (s.has("abs_region_profile")) {
                for (ResolvedLocation.RegionRow r : location.chain.stream().filter(x -> Set.of("SA2", "SA3", "SA4").contains(x.tier)).toList()) {
                    var rows = s.source.queryForList(
                        "SELECT region_name,region_type,population,remoteness_area,source_year,source_name FROM abs_region_profile WHERE region_key=? ORDER BY source_year DESC LIMIT 1", r.key);
                    if (!rows.isEmpty()) {
                        var p = rows.get(0);
                        WarehouseConnections.Profile profile = new WarehouseConnections.Profile();
                        profile.setArea(WarehouseText.text(p.get("region_name")));
                        profile.setScope(WarehouseText.text(p.get("region_type")));
                        profile.setPopulation(WarehouseText.intNumber(p.get("population")));
                        profile.setSetting(WarehouseText.text(p.get("remoteness_area")));
                        profile.setPeriod(str(firstNonNull(p.get("source_year"), "")));
                        profile.setSource(WarehouseText.text(p.get("source_name")));
                        overview.setProfile(profile);
                        break;
                    }
                }
            }

            List<Map<String, Object>> organisations = List.of();
            String column = switch (region.tier) { case "SA2" -> "sa2_code"; case "SA3" -> "sa3_code"; case "SA4" -> "sa4_code"; default -> null; };
            if (column != null && s.has("local_market_organisations")) {
                String sql = "SELECT id,name,organisation_type,suburb,state,source,last_verified_at,updated_at,gemini_proposed FROM local_market_organisations " +
                    "WHERE (" + column + "=?" + ("SA2".equals(region.tier) ? " OR (lower(suburb)=lower(?) AND state=?)" : "") + ") AND coalesce(gemini_proposed,0)=0 ORDER BY lower(name),updated_at DESC LIMIT 251";
                organisations = "SA2".equals(region.tier)
                    ? s.source.queryForList(sql, region.key, region.name, region.state)
                    : s.source.queryForList(sql, region.key);
            }
            List<String> typeOrder = List.of("community_centre", "rto", "university", "tafe", "employment_service", "library", "sport_club", "school", "support_service", "employer", "company", "business");
            Map<String, String[]> categories = Map.ofEntries(
                Map.entry("school", new String[]{"learning", "Learning and training"}), Map.entry("rto", new String[]{"learning", "Learning and training"}),
                Map.entry("university", new String[]{"learning", "Learning and training"}), Map.entry("tafe", new String[]{"learning", "Learning and training"}),
                Map.entry("employment_service", new String[]{"employment", "Help getting into work"}), Map.entry("community_centre", new String[]{"community", "Community activities"}),
                Map.entry("sport_club", new String[]{"community", "Community activities"}), Map.entry("library", new String[]{"community", "Community activities"}),
                Map.entry("support_service", new String[]{"support", "Community support"}), Map.entry("employer", new String[]{"business", "Recorded businesses"}),
                Map.entry("company", new String[]{"business", "Recorded businesses"}), Map.entry("business", new String[]{"business", "Recorded businesses"}));

            List<Map<String, Object>> sorted = organisations.stream().limit(250)
                .sorted((a, b) -> {
                    int ta = typeOrder.indexOf(str(a.get("organisation_type")));
                    int tb = typeOrder.indexOf(str(b.get("organisation_type")));
                    if (ta != tb) return Integer.compare(ta, tb);
                    return String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
                }).toList();

            Set<String> seen = new LinkedHashSet<>();
            Map<String, WarehouseConnections.CommunityGroup> groups = new LinkedHashMap<>();
            for (var o : sorted) {
                String[] category = categories.get(str(o.get("organisation_type")));
                String name = WarehouseText.text(o.get("name"));
                if (category == null || name.isEmpty()) continue;
                String key = String.join("|", name, str(o.get("organisation_type")), str(o.get("suburb")), str(o.get("state"))).toLowerCase();
                if (!seen.add(key)) continue;
                WarehouseConnections.CommunityGroup group = groups.computeIfAbsent(category[0], k -> {
                    WarehouseConnections.CommunityGroup g = new WarehouseConnections.CommunityGroup();
                    g.setKey(category[0]); g.setLabel(category[1]);
                    return g;
                });
                group.setRecordedCount(group.getRecordedCount() + 1);
                if (group.getExamples().size() < 4) {
                    WarehouseConnections.Example ex = new WarehouseConnections.Example();
                    ex.setName(name);
                    ex.setType(WarehouseText.text(o.get("organisation_type")));
                    ex.setSource(WarehouseText.text(o.get("source")));
                    ex.setUpdatedAt(WarehouseText.text(firstNonNull(str(o.get("last_verified_at")), str(o.get("updated_at")))));
                    ex.setArea(!WarehouseText.text(o.get("suburb")).isEmpty() ? WarehouseText.text(o.get("suburb")) : region.name);
                    group.getExamples().add(ex);
                }
            }
            for (String k : List.of("community", "learning", "employment", "support", "business")) if (groups.containsKey(k)) overview.getCommunity().add(groups.get(k));
            overview.setLimited(organisations.size() > 250);
            return overview;
        }
    }

    private static final class ResolvedLocation {
        String requested = "";
        RegionRow region;
        List<RegionRow> candidates = new ArrayList<>();
        List<RegionRow> chain = new ArrayList<>();

        static final class RegionRow {
            final String key, name, tier, state;
            RegionRow(String key, String name, String tier, String state) { this.key = key; this.name = name; this.tier = tier; this.state = state; }
        }
    }

    private String str(Object v) { return v == null ? null : String.valueOf(v); }

    private List<String> firstN(List<String> list, int n) { return list == null ? List.of() : list.stream().filter(x -> x != null && !x.isBlank()).limit(n).toList(); }

    // ---- opportunities (opportunities.cjs's createOpportunityReader) --------------------

    private final class OpportunityReader {
        private final Session s;
        OpportunityReader(Session s) { this.s = s; }

        WarehouseExploration read(WarehouseQueryPlan plan, ResolvedLocation location) {
            Set<String> facets = new LinkedHashSet<>(plan.getFacets() == null ? List.of() : plan.getFacets());
            boolean requested = facets.contains("SKILLS") || facets.contains("JOBS") || facets.contains("LEARNING") || !plan.getSkillQueries().isEmpty();
            if (!requested) return null;

            List<String> skills = firstN(plan.getSkillQueries(), 6);
            List<String> roleTerms = firstN(plan.getOccupationQueries(), 3);
            Map<String, RoleHit> roles = new LinkedHashMap<>();
            record Phrases(String kind, List<String> phrases) {}
            List<Phrases> passes = List.of(
                new Phrases("role", roleTerms),
                new Phrases("related", roleTerms.isEmpty() ? concat(plan.getRoleQueries(), plan.getJobQueries()) : List.of()),
                new Phrases("hint", skills),
                new Phrases("skill", skills));
            for (Phrases pass : passes) {
                for (String phrase : pass.phrases) {
                    String match = WarehouseText.matchClause(phrase);
                    if (match == null) continue;
                    List<Map<String, Object>> found = searchRoles(pass.kind, match, false);
                    if (found.isEmpty() && !pass.kind.equals("skill") && !pass.kind.equals("hint")) {
                        List<String> reduced = WarehouseText.terms(phrase, 8).stream()
                            .filter(w -> !Set.of("officer", "assistant", "technician", "representative", "specialist", "analyst", "worker", "workers", "career").contains(w)).toList();
                        if (reduced.size() >= 2) found = searchRoles(pass.kind, WarehouseText.matchClause(String.join(" ", reduced)), false);
                        if (found.isEmpty()) found = searchRoles(pass.kind, match, true);
                    }
                    for (var r : found) {
                        String roleId = str(r.get("role_id"));
                        RoleHit hit = roles.computeIfAbsent(roleId, k -> new RoleHit(roleId, str(r.get("title"))));
                        hit.score += "skill".equals(pass.kind) ? 1 : 100;
                        hit.rank = WarehouseText.number(r.get("rank"));
                        if ("role".equals(pass.kind)) hit.named = true;
                        if ("hint".equals(pass.kind)) hit.hint = true;
                        if ("related".equals(pass.kind) || "hint".equals(pass.kind)) hit.related = true;
                        if ("skill".equals(pass.kind) && !hit.matchedSkills.contains(phrase)) hit.matchedSkills.add(phrase);
                    }
                }
            }
            List<RoleHit> values = new ArrayList<>(roles.values());
            List<RoleHit> focused = !roleTerms.isEmpty() ? values.stream().filter(r -> r.named || r.hint).toList()
                : values.stream().anyMatch(r -> r.related) ? values.stream().filter(r -> r.related).toList()
                : !plan.getRoleQueries().isEmpty() ? List.of() : values;
            List<RoleHit> candidates = focused.stream()
                .sorted((a, b) -> {
                    if (a.score != b.score) return Double.compare(b.score, a.score);
                    double ar = a.rank == null ? 0 : a.rank, br = b.rank == null ? 0 : b.rank;
                    if (ar != br) return Double.compare(ar, br);
                    return a.title.compareTo(b.title);
                }).limit(12).toList();

            List<RoleHit> selected;
            if (plan.getRoleIds() != null && !plan.getRoleIds().isEmpty()) {
                selected = candidates.stream().filter(r -> plan.getRoleIds().contains("role:" + r.roleId))
                    .sorted((a, b) -> Integer.compare(plan.getRoleIds().indexOf("role:" + a.roleId), plan.getRoleIds().indexOf("role:" + b.roleId))).toList();
            } else {
                selected = plan.isCandidatePool() ? candidates : candidates.stream().limit(4).toList();
            }

            List<WarehouseExploration.Role> selectedRoles = new ArrayList<>();
            for (RoleHit hit : selected) {
                var rows = s.has("onet_occupation") ? s.source.queryForList("SELECT job_id,job_title,description,tasks FROM onet_occupation WHERE job_id=?", hit.roleId) : List.<Map<String, Object>>of();
                Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
                var mappingRows = s.has("onet_anzsco_crosswalk")
                    ? s.source.queryForList("SELECT anzsco_code,anzsco_title,method,confidence FROM onet_anzsco_crosswalk WHERE job_id=? ORDER BY confidence DESC LIMIT 2", hit.roleId)
                    : List.<Map<String, Object>>of();
                List<WarehouseExploration.Mapping> mappings = mappingRows.stream()
                    .map(m -> new WarehouseExploration.Mapping(str(m.get("anzsco_code")), str(m.get("anzsco_title")), str(m.get("method")), WarehouseText.number(m.get("confidence"))))
                    .toList();
                List<WarehouseExploration.RoleSkill> requirements = s.index.queryForList(
                        "SELECT skill_id AS id,name,description FROM role_skills WHERE role_id=? LIMIT 18", hit.roleId).stream()
                    .map(r -> new WarehouseExploration.RoleSkill(str(r.get("id")), str(r.get("name")), WarehouseText.text(r.get("description")))).toList();
                WarehouseExploration.Role role = new WarehouseExploration.Role();
                role.setId("role:" + hit.roleId);
                role.setEvidenceId("warehouse_role_" + hit.roleId);
                role.setTitle(hit.title);
                role.setDescription(row == null ? null : WarehouseText.text(row.get("description")));
                role.setTasks(row == null ? List.of() : WarehouseText.list(row.get("tasks"), 4));
                role.setMatchedSkills(hit.matchedSkills);
                role.setSkills(requirements);
                role.setMappings(mappings);
                role.setSource("O*NET occupation profile");
                role.setScope("General occupational context; stored Australian crosswalks are shown separately");
                role.setMatchReason(hit.named ? "Matches the occupation requested" : "Uses one or more of the skills being explored; personal suitability is not established");
                selectedRoles.add(role);
            }

            List<WarehouseExploration.LearningLink> learning = new ArrayList<>();
            if (facets.contains("LEARNING")) {
                for (String phrase : skills) {
                    String match = WarehouseText.matchClause(phrase);
                    if (match == null) continue;
                    for (var r : s.index.queryForList("SELECT code,name,kind,method FROM learning_search WHERE learning_search MATCH ? ORDER BY rank LIMIT 8", match)) {
                        String code = str(r.get("code"));
                        String skillName = str(r.get("name"));
                        if (learning.stream().anyMatch(x -> code.equals(x.getCode()) && skillName.equals(x.getSkill()))) continue;
                        var courseRows = s.index.queryForList("SELECT course_id,course_name,institution_name FROM catalogue WHERE national_code=? LIMIT 2", code);
                        WarehouseExploration.LearningLink link = new WarehouseExploration.LearningLink();
                        link.setId("learning:" + code + ":" + learning.size());
                        link.setEvidenceId("warehouse_learning_" + code + "_" + learning.size());
                        link.setCode(code);
                        link.setSkill(skillName);
                        link.setKind(str(r.get("kind")));
                        link.setMethod(str(r.get("method")));
                        link.setQuery(phrase);
                        link.setCourses(courseRows.stream().map(c -> new WarehouseExploration.CourseRef(str(c.get("course_id")), str(c.get("course_name")), str(c.get("institution_name")))).toList());
                        link.setScope("Stored qualification-to-skill or unit link; delivery and assessment are not established by this link");
                        learning.add(link);
                    }
                }
            }

            List<WarehouseExploration.JobAd> jobs = new ArrayList<>();
            List<WarehouseExploration.ObservedSkill> observedSkills = new ArrayList<>();
            WarehouseExploration.Geography geography = null;
            int sampleSize = 0, withSkills = 0;
            List<String> jobTerms = new LinkedHashSet<>(!plan.getJobQueries().isEmpty() ? plan.getJobQueries()
                : !roleTerms.isEmpty() ? roleTerms : !plan.getRoleQueries().isEmpty() ? plan.getRoleQueries() : skills)
                .stream().filter(x -> WarehouseText.matchClause(x) != null).limit(8).toList();

            if (facets.contains("JOBS") && s.has("outside_jobs") && !jobTerms.isEmpty()) {
                List<JobStage> stages = new ArrayList<>();
                ResolvedLocation.RegionRow region = location.region;
                WarehouseQueryPlan.LocationQuery local = plan.getLocation();
                if (region != null) {
                    if (local != null && !isBlank(local.getName())) stages.add(new JobStage("g.city=? AND g.state=?", List.of(local.getName().toLowerCase(), region.state), "SUBURB", local.getName(), true));
                    if (local != null && local.getPostcode() != null && local.getPostcode().matches("\\d{4}")) stages.add(new JobStage("g.postcode=? AND g.state=?", List.of(local.getPostcode(), region.state), "POSTCODE", local.getPostcode(), true));
                    var sa4 = location.chain.stream().filter(r -> "SA4".equals(r.tier)).findFirst();
                    if (sa4.isPresent()) stages.add(new JobStage("g.sa4=?", List.of(sa4.get().key), "SA4", sa4.get().name, "SA4".equals(region.tier)));
                    if (region.state != null && !region.state.isBlank()) stages.add(new JobStage("g.state=?", List.of(region.state), "STATE", region.state, "STATE".equals(region.tier)));
                } else if (isBlank(location.requested)) {
                    stages.add(new JobStage("1=1", List.of(), "NATIONAL", "Australia", false));
                }
                for (JobStage stage : stages) {
                    Set<String> candidateIds = new LinkedHashSet<>();
                    for (String phrase : jobTerms) {
                        List<Object> args = new ArrayList<>();
                        args.add(WarehouseText.matchClause(phrase));
                        args.addAll(stage.args());
                        for (var r : s.index.queryForList(
                                "SELECT j.job_id FROM job_search j JOIN job_geo g ON g.job_id=j.job_id WHERE job_search MATCH ? AND " + stage.where() + " ORDER BY rank LIMIT 100",
                                args.toArray())) {
                            candidateIds.add(str(r.get("job_id")));
                        }
                    }
                    if (candidateIds.isEmpty()) continue;
                    List<String> ids = candidateIds.stream().limit(800).toList();
                    String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
                    List<Object> args = new ArrayList<>(ids);
                    var found = s.source.queryForList(
                        "SELECT id,title,company_name,city,state,postal_code,skills_json,requirements_json,description,employment_type,work_mode,salary_text,posted_at,updated_at,expired_at,job_url,source " +
                        "FROM outside_jobs WHERE id IN (" + placeholders + ") AND privacy_level='PUBLIC' AND status='active' AND upper(country) IN ('AU','AUSTRALIA','AUS') " +
                        "AND (expired_at IS NULL OR expired_at='' OR julianday(expired_at)>julianday('now')) ORDER BY COALESCE(NULLIF(posted_at,''),updated_at) DESC,id DESC LIMIT 40",
                        args.toArray());
                    if (found.isEmpty()) continue;
                    geography = new WarehouseExploration.Geography(stage.scope(), stage.name(), stage.local());
                    sampleSize = found.size();
                    Map<String, int[]> counts = new LinkedHashMap<>();
                    Map<String, String> displayNames = new LinkedHashMap<>();
                    for (var r : found) {
                        List<String> names = new ArrayList<>(new LinkedHashSet<>(WarehouseText.list(r.get("skills_json"), 30)));
                        if (!names.isEmpty()) withSkills++;
                        for (String name : names) {
                            String k = name.toLowerCase().trim();
                            counts.computeIfAbsent(k, x -> new int[1])[0]++;
                            displayNames.putIfAbsent(k, name);
                        }
                    }
                    int finalSampleSize = sampleSize;
                    List<Map.Entry<String, int[]>> sortedCounts = counts.entrySet().stream()
                        .sorted((a, b) -> b.getValue()[0] != a.getValue()[0] ? Integer.compare(b.getValue()[0], a.getValue()[0]) : displayNames.get(a.getKey()).compareTo(displayNames.get(b.getKey())))
                        .limit(8).toList();
                    for (int i = 0; i < sortedCounts.size(); i++) {
                        var e = sortedCounts.get(i);
                        WarehouseExploration.ObservedSkill os = new WarehouseExploration.ObservedSkill();
                        os.setId("observed:" + i);
                        os.setEvidenceId("warehouse_observed_skill_" + i);
                        os.setName(displayNames.get(e.getKey()));
                        os.setCount(e.getValue()[0]);
                        os.setDenominator(finalSampleSize);
                        os.setScope("Structured skills in the retrieved advertisement sample only");
                        os.setGeography(stage.name());
                        observedSkills.add(os);
                    }
                    for (var r : found.stream().limit(6).toList()) {
                        WarehouseExploration.JobAd job = new WarehouseExploration.JobAd();
                        job.setId(str(r.get("id")));
                        job.setEvidenceId("warehouse_job_" + r.get("id"));
                        job.setTitle(WarehouseText.text(r.get("title"), 200));
                        job.setCompany(WarehouseText.text(r.get("company_name"), 160));
                        job.setArea(java.util.stream.Stream.of(r.get("city"), r.get("state"), r.get("postal_code")).filter(x -> x != null && !String.valueOf(x).isBlank()).map(String::valueOf).collect(Collectors.joining(", ")));
                        job.setSkills(WarehouseText.list(r.get("skills_json")));
                        job.setRequirements(WarehouseText.list(r.get("requirements_json"), 6));
                        job.setDescription(WarehouseText.text(r.get("description"), 800));
                        job.setEmploymentType(WarehouseText.text(r.get("employment_type"), 100));
                        job.setWorkMode(WarehouseText.text(r.get("work_mode"), 100));
                        job.setSalary(WarehouseText.text(r.get("salary_text"), 160));
                        job.setPostedAt(str(r.get("posted_at")));
                        job.setUpdatedAt(str(r.get("updated_at")));
                        String jobUrl = str(r.get("job_url"));
                        job.setUrl(jobUrl != null && jobUrl.matches("(?i)^https?://.*") ? jobUrl : null);
                        job.setSource(WarehouseText.text(r.get("source")));
                        job.setGeography(geography);
                        jobs.add(job);
                    }
                    break;
                }
            }

            Map<String, WarehouseExploration.SkillRef> skillsById = new LinkedHashMap<>();
            for (var role : selectedRoles) {
                for (var skill : role.getSkills()) {
                    var ref = skillsById.computeIfAbsent(skill.getId(), k -> {
                        WarehouseExploration.SkillRef sr = new WarehouseExploration.SkillRef();
                        sr.setId(skill.getId()); sr.setName(skill.getName()); sr.setDescription(skill.getDescription());
                        return sr;
                    });
                    ref.getRoleIds().add(role.getId());
                }
            }
            List<WarehouseExploration.SkillRef> displayedSkills = skillsById.values().stream()
                .sorted((a, b) -> b.getRoleIds().size() != a.getRoleIds().size() ? Integer.compare(b.getRoleIds().size(), a.getRoleIds().size()) : a.getName().compareTo(b.getName()))
                .limit(12).toList();

            WarehouseExploration exploration = new WarehouseExploration();
            exploration.setRoles(selectedRoles);
            exploration.setSkills(displayedSkills);
            exploration.setLearning(learning.stream().limit(8).toList());
            exploration.setJobs(jobs);
            exploration.setObservedSkills(observedSkills);
            exploration.setGeography(geography);
            WarehouseExploration.Coverage coverage = new WarehouseExploration.Coverage();
            coverage.setSampleSize(sampleSize);
            coverage.setWithStructuredSkills(withSkills);
            coverage.setReturnedJobs(jobs.size());
            exploration.setCoverage(coverage);
            return exploration;
        }

        private List<Map<String, Object>> searchRoles(String kind, String match, boolean alias) {
            String column = "skill".equals(kind) ? "skills" : alias ? "aliases" : "title";
            return s.index.queryForList("SELECT role_id,title,bm25(role_search,0,8,4,1,6) rank FROM role_search WHERE role_search MATCH ? ORDER BY rank LIMIT 12",
                column + " : (" + match + ")");
        }

        private boolean isBlank(String v) { return v == null || v.isBlank(); }

        private List<String> concat(List<String> a, List<String> b) {
            List<String> out = new ArrayList<>(a == null ? List.of() : a);
            out.addAll(b == null ? List.of() : b);
            return out;
        }

        private final class RoleHit {
            final String roleId;
            final String title;
            double score;
            Double rank;
            boolean named, hint, related;
            List<String> matchedSkills = new ArrayList<>();
            RoleHit(String roleId, String title) { this.roleId = roleId; this.title = title; }
        }

        private record JobStage(String where, List<Object> args, String scope, String name, boolean local) {}
    }
}
