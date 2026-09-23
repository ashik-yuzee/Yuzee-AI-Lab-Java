package com.yuzee.tokenlab.service.warehouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context) for the warehouse index builder:
 * <ol>
 *   <li>confirms the bundled {@code org.xerial:sqlite-jdbc} driver actually supports FTS5 (not all
 *       prebuilt sqlite-jdbc binaries enable it) by creating a virtual table in a throwaway db and
 *       running a MATCH query against it;</li>
 *   <li>confirms {@link WarehouseIndexBuilder} builds a working derived FTS5 catalogue from a small
 *       fake source db with a {@code live_courses} table;</li>
 *   <li>confirms it no-ops cleanly (no exception, {@code ensureReady()} false) when unconfigured or
 *       pointed at a missing file -- the "boots fine with no warehouse data" requirement.</li>
 * </ol>
 */
class WarehouseIndexBuilderSanityTest {

    @Test
    void bundledSqliteDriverSupportsFts5(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("fts5-check.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE VIRTUAL TABLE demo USING fts5(title)");
                st.execute("INSERT INTO demo(title) VALUES ('plumbing certificate iv')");
                st.execute("INSERT INTO demo(title) VALUES ('bachelor of nursing')");
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT title FROM demo WHERE demo MATCH 'plumbing' ORDER BY rank")) {
                assertTrue(rs.next(), "FTS5 MATCH should return the plumbing row");
                assertEquals("plumbing certificate iv", rs.getString("title"));
                assertFalse(rs.next(), "only one row should match 'plumbing'");
            }
        }
    }

    @Test
    void buildsAWorkingCatalogueIndexFromAFakeSourceDb(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("training_gov.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sourceFile)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE live_courses (id INTEGER, course_name TEXT, institution_name TEXT, national_code TEXT, course_code TEXT, institution_id INTEGER)");
                st.execute("INSERT INTO live_courses VALUES (1, 'Certificate IV in Plumbing and Services', 'Example TAFE', 'CPC40120', 'CPC40120', 10)");
                st.execute("INSERT INTO live_courses VALUES (2, 'Bachelor of Nursing', 'Example University', 'BN001', 'BN001', 20)");
            }
        }
        Path indexFile = tempDir.resolve("derived-catalogue.sqlite");

        WarehouseIndexBuilder builder = new WarehouseIndexBuilder(sourceFile.toString(), indexFile.toString());
        assertTrue(builder.ensureReady(), "ensureReady() should succeed once a real source file is configured");
        assertTrue(builder.isReady());
        assertTrue(indexFile.toFile().isFile(), "the derived index file should have been written");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + indexFile);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT course_id, course_name FROM catalogue WHERE catalogue MATCH 'plumbing' ORDER BY rank")) {
            assertTrue(rs.next(), "the derived FTS5 catalogue should find the plumbing course");
            assertEquals("1", rs.getString("course_id"));
        }

        // Rebuild is a no-op (same stamp) -- exercised here to confirm it doesn't throw or hang.
        assertTrue(builder.ensureReady());
    }

    @Test
    void noOpsCleanlyWhenSourceIsNotConfigured() {
        WarehouseIndexBuilder builder = new WarehouseIndexBuilder("", "target/does-not-matter.sqlite");
        assertFalse(builder.ensureReady());
        assertFalse(builder.isReady());
    }

    @Test
    void noOpsCleanlyWhenSourceFileIsMissing(@TempDir Path tempDir) {
        WarehouseIndexBuilder builder = new WarehouseIndexBuilder(
            tempDir.resolve("nonexistent.db").toString(), tempDir.resolve("derived.sqlite").toString());
        assertFalse(builder.ensureReady());
        assertFalse(builder.isReady());
    }

    @Test
    void textHelpersKeepTheOriginalSemantics() {
        assertEquals("\"certificate\"* AND \"iv\" AND \"plumbing\"* AND \"cpc40120\"", WarehouseText.catalogueClause("Certificate IV in Plumbing CPC40120"));
        assertEquals("\"time\"* AND \"management\"* AND \"and\"*", WarehouseText.clause("time management and"));
        assertEquals("3", WarehouseText.jsString(3.0));
        assertEquals(java.util.List.of("the", "gordon"), WarehouseText.providerWords("The Gordon"));
        assertEquals(null, WarehouseText.cleanText("not_verified"));
        assertEquals("https://example.com/", WarehouseText.url("HTTPS://Example.com"));
    }

    @Test
    void lookupReturnsTheOriginalPackShape(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("training_gov.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sourceFile); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE live_courses (id INTEGER, course_name TEXT, institution_id INTEGER, institution_name TEXT, national_code TEXT, course_code TEXT, aqf_level TEXT, course_type TEXT, description TEXT, duration_text TEXT, delivery_modes_json TEXT, locations_json TEXT, entry_requirements TEXT, domestic_fee TEXT, international_fee TEXT, skills_json TEXT, quality_scores_json TEXT, work_readiness_score REAL, future_readiness_score REAL, yuzee_readiness_score REAL, career_outcomes_json TEXT, intelligence_json TEXT, canonical_url TEXT, web_collect_url TEXT, website TEXT, intelligence_enriched_at TEXT, updated_at TEXT)");
            st.execute("INSERT INTO live_courses (id,course_name,institution_id,institution_name,national_code,aqf_level,delivery_modes_json,work_readiness_score,intelligence_json) VALUES (1,'Certificate IV in Plumbing',10,'Example TAFE','CPC40120','4','[\"Online\"]',80.0,'{\"trust_and_quality\":{\"scoring_reason\":\"Sound\"}}')");
            st.execute("CREATE TABLE live_institutions (id INTEGER, legal_name TEXT, rto_code TEXT, rto_type TEXT, higher_education_code TEXT, city TEXT, state TEXT, website_url TEXT, has_student_support INTEGER, has_disability_support INTEGER, has_library INTEGER, has_apprenticeships INTEGER, about_us_description TEXT, updated_at TEXT)");
            st.execute("INSERT INTO live_institutions (id,legal_name,rto_code,city,state,has_student_support) VALUES (10,'Example TAFE','123','Ringwood','VIC',1)");
        }
        WarehouseIndexBuilder builder = new WarehouseIndexBuilder(sourceFile.toString(), tempDir.resolve("index.sqlite").toString());
        assertTrue(builder.ensureReady());
        var plan = new com.yuzee.tokenlab.model.warehouse.WarehouseQueryPlan();
        plan.setComparison(true);
        plan.setFacets(java.util.List.of("PROVIDER"));
        var result = new WarehouseQueryService(builder).lookup(java.util.List.of("plumbing"), java.util.List.of(), plan);
        assertEquals(1, result.courses().size());
        var course = result.courses().get(0);
        assertEquals("4", course.getLevel());
        assertEquals(java.util.List.of("Online"), course.getDelivery());
        assertEquals("Sound", course.getQualityExplanation());
        assertEquals("MATCHED", result.providerMatches().get(0).getStatus());
        assertEquals(java.util.List.of("Student support"), result.providerMatches().get(0).getProviders().get(0).getSupport());
        assertEquals(java.util.List.of("Student support"), result.connected().getProviders().get(0).getSupport());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(course);
        assertTrue(json.contains("\"code\":\"CPC40120\"") && json.contains("\"duration\":null"), json);
    }
}
