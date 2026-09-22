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
}
