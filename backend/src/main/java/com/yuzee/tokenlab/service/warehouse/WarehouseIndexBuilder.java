package com.yuzee.tokenlab.service.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds (or reuses) a derived FTS5 lookup index from the source {@code training_gov.db} SQLite
 * database, in-process. Ported from the index-building half of
 * yuzee-ai-token-lab/src/warehouse/catalogue-worker.cjs's {@code initialize()} (the FTS5 catalogue,
 * the materialized profile/occupation/industry link tables) plus opportunities.cjs's
 * {@code buildOpportunityIndex()} (the O*NET role/job/learning search tables).
 * <p>
 * The old app forked a worker process for this; a plain Java class does the same job in-process
 * since there is no event loop to protect here.
 * <p>
 * // ponytail: no background scheduler / file-watcher for source changes -- the stamp check below
 * // re-validates (and silently rebuilds) on first use per app run, matching the old worker's
 * // once-per-process behaviour. Add a watcher only if the source db is replaced while the app is up.
 */
@Component
public class WarehouseIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(WarehouseIndexBuilder.class);
    private static final int STAMP_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sourcePath;
    private final String indexPath;
    private boolean loggedUnavailable = false;
    private volatile boolean ready = false;

    public WarehouseIndexBuilder(
            @Value("${warehouse.db-path:}") String sourcePath,
            @Value("${warehouse.index-path:data/warehouse/catalogue.sqlite}") String indexPath) {
        this.sourcePath = sourcePath == null ? "" : sourcePath.trim();
        this.indexPath = indexPath;
    }

    public String getIndexPath() { return indexPath; }
    public String getSourcePath() { return sourcePath; }

    /** True once the source database is configured, exists, and the derived index is ready to query. */
    public synchronized boolean ensureReady() {
        if (sourcePath.isBlank()) {
            logUnavailableOnce("Warehouse source database not configured (warehouse.db-path / YUZEE_WAREHOUSE_DB is unset); warehouse features report UNAVAILABLE.");
            return false;
        }
        File sourceFile = new File(sourcePath);
        if (!sourceFile.isFile()) {
            logUnavailableOnce("Warehouse source database not found at " + sourcePath + "; warehouse features report UNAVAILABLE.");
            return false;
        }
        try {
            rebuildIfStale(sourceFile);
            ready = true;
            return true;
        } catch (Exception e) {
            log.warn("Warehouse index build failed; warehouse features report UNAVAILABLE.", e);
            ready = false;
            return false;
        }
    }

    public boolean isReady() { return ready; }

    private void logUnavailableOnce(String message) {
        if (!loggedUnavailable) {
            log.info(message);
            loggedUnavailable = true;
        }
    }

    private void rebuildIfStale(File sourceFile) throws Exception {
        String stamp = computeStamp(sourceFile);
        File indexFile = new File(indexPath);
        if (indexFile.isFile() && stampMatches(indexFile, stamp)) return;

        File dir = indexFile.getParentFile();
        if (dir != null) Files.createDirectories(dir.toPath());
        File temp = new File(indexPath + ".building-" + ProcessHandle.current().pid());
        Files.deleteIfExists(temp.toPath());

        try (Connection source = openSourceReadOnly(sourceFile);
             Connection index = openIndexReadWrite(temp)) {
            index.setAutoCommit(false);
            buildCatalogue(source, index);
            buildLinkTables(source, index);
            buildOpportunityIndex(source, index);
            writeStamp(index, stamp);
            index.commit();
        }
        Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private String computeStamp(File sourceFile) throws java.io.IOException {
        File wal = new File(sourceFile.getPath() + "-wal");
        long walSize = wal.isFile() ? wal.length() : -1;
        long walMtime = wal.isFile() ? wal.lastModified() : -1;
        return STAMP_VERSION + "|" + sourceFile.getCanonicalPath() + "|" + sourceFile.length() + "|"
            + sourceFile.lastModified() + "|" + walSize + "|" + walMtime;
    }

    private boolean stampMatches(File indexFile, String stamp) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + indexFile.getPath());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT value FROM metadata WHERE key='source'")) {
                return rs.next() && stamp.equals(rs.getString("value"));
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection openSourceReadOnly(File sourceFile) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getPath(), config.toProperties());
    }

    private Connection openIndexReadWrite(File file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
    }

    private Set<String> tableNames(Connection c) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) names.add(rs.getString("name"));
        }
        return names;
    }

    private void writeStamp(Connection index, String stamp) throws SQLException {
        try (Statement st = index.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT)");
        }
        try (PreparedStatement ps = index.prepareStatement("INSERT OR REPLACE INTO metadata VALUES (?,?)")) {
            ps.setString(1, "source");
            ps.setString(2, stamp);
            ps.executeUpdate();
        }
    }

    // ---- catalogue FTS5 (course search) --------------------------------------------------

    private void buildCatalogue(Connection source, Connection index) throws SQLException {
        try (Statement st = index.createStatement()) {
            st.execute("DROP TABLE IF EXISTS catalogue");
            st.execute("""
                CREATE VIRTUAL TABLE catalogue USING fts5(
                  course_id UNINDEXED, course_name, institution_name, national_code, course_code,
                  institution_id UNINDEXED, tokenize='unicode61 remove_diacritics 2')""");
        }
        if (!tableNames(source).contains("live_courses")) return;
        try (Statement read = source.createStatement();
             ResultSet rs = read.executeQuery(
                 "SELECT id,course_name,institution_name,national_code,course_code,institution_id FROM live_courses");
             PreparedStatement insert = index.prepareStatement("INSERT INTO catalogue VALUES(?,?,?,?,?,?)")) {
            while (rs.next()) {
                insert.setString(1, String.valueOf(rs.getObject("id")));
                insert.setString(2, ns(rs.getString("course_name")));
                insert.setString(3, ns(rs.getString("institution_name")));
                insert.setString(4, ns(rs.getString("national_code")));
                insert.setString(5, ns(rs.getString("course_code")));
                insert.setString(6, String.valueOf(rs.getObject("institution_id")));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    // ---- materialized profile/occupation/industry link tables ---------------------------

    private void buildLinkTables(Connection source, Connection index) throws SQLException {
        Set<String> sourceTables = tableNames(source);

        try (Statement st = index.createStatement()) {
            st.execute("DROP TABLE IF EXISTS profile_links");
            st.execute("""
                CREATE TABLE profile_links(course_id TEXT, course_name TEXT, qualification_code TEXT,
                  anzsco_codes_json TEXT, occupation_titles_json TEXT, industry_codes_json TEXT,
                  mapping_method TEXT, mapping_confidence TEXT)""");
            st.execute("CREATE INDEX profile_links_lookup ON profile_links(course_id)");
            st.execute("DROP TABLE IF EXISTS occupation_links");
            st.execute("""
                CREATE TABLE occupation_links(qualification_code TEXT, occupation_code TEXT, occupation_name TEXT,
                  match_method TEXT, match_confidence TEXT, is_primary TEXT)""");
            st.execute("CREATE INDEX occupation_links_lookup ON occupation_links(qualification_code)");
        }
        if (sourceTables.contains("course_mapping_profile")) {
            copyTable(source, index,
                "SELECT course_id,course_name,qualification_code,anzsco_codes_json,occupation_titles_json,industry_codes_json,mapping_method,mapping_confidence FROM course_mapping_profile",
                "INSERT INTO profile_links VALUES(?,?,?,?,?,?,?,?)", 8);
        }
        if (sourceTables.contains("course_occupation_map")) {
            copyTable(source, index,
                "SELECT qualification_code,occupation_code,occupation_name,match_method,match_confidence,is_primary FROM course_occupation_map",
                "INSERT INTO occupation_links VALUES(?,?,?,?,?,?)", 6);
        }

        try (Statement st = index.createStatement()) {
            st.execute("DROP TABLE IF EXISTS industry_links");
            st.execute("CREATE TABLE industry_links(course_id TEXT, industry_name TEXT, qualification_code TEXT, mapping_method TEXT)");
            st.execute("CREATE INDEX industry_name_lookup ON industry_links(industry_name)");
        }
        try (Statement read = index.createStatement();
             ResultSet rs = read.executeQuery("SELECT course_id,qualification_code,industry_codes_json,mapping_method FROM profile_links");
             PreparedStatement insert = index.prepareStatement("INSERT INTO industry_links VALUES(?,?,?,?)")) {
            while (rs.next()) {
                JsonNode names = WarehouseText.json(rs.getString("industry_codes_json"));
                if (!names.isArray()) continue;
                int n = 0;
                for (JsonNode nameNode : names) {
                    if (n++ >= 8) break;
                    String name = nameNode.isTextual() ? nameNode.asText().trim() : "";
                    if (name.isEmpty() || name.matches("(?i)^(unknown|null|none)$")) continue;
                    insert.setString(1, rs.getString("course_id"));
                    insert.setString(2, name);
                    insert.setString(3, ns(rs.getString("qualification_code")));
                    insert.setString(4, ns(rs.getString("mapping_method")));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private void copyTable(Connection source, Connection index, String selectSql, String insertSql, int cols) throws SQLException {
        try (Statement read = source.createStatement();
             ResultSet rs = read.executeQuery(selectSql);
             PreparedStatement insert = index.prepareStatement(insertSql)) {
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) insert.setObject(i, rs.getObject(i));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    // ---- opportunity index (O*NET roles/skills, learning links, job ads) ----------------

    private void buildOpportunityIndex(Connection source, Connection index) throws SQLException {
        Set<String> sourceTables = tableNames(source);
        try (Statement st = index.createStatement()) {
            st.execute("DROP TABLE IF EXISTS role_search");
            st.execute("""
                CREATE VIRTUAL TABLE role_search USING fts5(role_id UNINDEXED, title, skills, description, aliases,
                  tokenize='unicode61 remove_diacritics 2')""");
            st.execute("DROP TABLE IF EXISTS role_skills");
            st.execute("CREATE TABLE role_skills(role_id TEXT, skill_id TEXT, name TEXT, description TEXT)");
            st.execute("CREATE INDEX role_skill_lookup ON role_skills(role_id)");
            st.execute("DROP TABLE IF EXISTS learning_search");
            st.execute("""
                CREATE VIRTUAL TABLE learning_search USING fts5(code UNINDEXED, name, kind UNINDEXED, method UNINDEXED,
                  tokenize='unicode61 remove_diacritics 2')""");
            st.execute("DROP TABLE IF EXISTS job_search");
            st.execute("""
                CREATE VIRTUAL TABLE job_search USING fts5(job_id UNINDEXED, title, skills, description,
                  tokenize='unicode61 remove_diacritics 2')""");
            st.execute("DROP TABLE IF EXISTS job_geo");
            st.execute("CREATE TABLE job_geo(job_id TEXT PRIMARY KEY, city TEXT, state TEXT, postcode TEXT, sa4 TEXT)");
            st.execute("CREATE INDEX job_area ON job_geo(state, city)");
            st.execute("CREATE INDEX job_postcode ON job_geo(postcode)");
            st.execute("CREATE INDEX job_sa4 ON job_geo(sa4)");
        }

        java.util.Map<String, java.util.List<String>> skillGroups = new java.util.HashMap<>();
        if (sourceTables.contains("onet_job_skill")) {
            boolean hasDesc = columnExists(source, "onet_job_skill", "skill_desc");
            try (Statement read = source.createStatement();
                 ResultSet rs = read.executeQuery("SELECT job_id,skill_name" + (hasDesc ? ",skill_desc" : "") + " FROM onet_job_skill");
                 PreparedStatement insert = index.prepareStatement("INSERT INTO role_skills VALUES(?,?,?,?)")) {
                while (rs.next()) {
                    String id = String.valueOf(rs.getObject("job_id"));
                    String name = WarehouseText.text(rs.getString("skill_name"), 120);
                    if (name.isEmpty()) continue;
                    String skillId = "skill:" + name.toLowerCase().trim();
                    insert.setString(1, id);
                    insert.setString(2, skillId);
                    insert.setString(3, name);
                    insert.setString(4, hasDesc ? WarehouseText.text(rs.getString("skill_desc")) : null);
                    insert.addBatch();
                    skillGroups.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(name);
                }
                insert.executeBatch();
            }
        }
        if (sourceTables.contains("onet_occupation")) {
            boolean hasKeywords = columnExists(source, "onet_occupation", "keywords");
            try (Statement read = source.createStatement();
                 ResultSet rs = read.executeQuery("SELECT job_id,job_title,description" + (hasKeywords ? ",keywords" : "") + " FROM onet_occupation");
                 PreparedStatement insert = index.prepareStatement("INSERT INTO role_search VALUES(?,?,?,?,?)")) {
                while (rs.next()) {
                    String id = String.valueOf(rs.getObject("job_id"));
                    insert.setString(1, id);
                    insert.setString(2, WarehouseText.text(rs.getString("job_title"), 180));
                    insert.setString(3, String.join(" | ", skillGroups.getOrDefault(id, java.util.List.of())));
                    insert.setString(4, WarehouseText.text(rs.getString("description"), 800));
                    insert.setString(5, hasKeywords ? WarehouseText.text(rs.getString("keywords"), 1000) : null);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        if (sourceTables.contains("course_skill_edge")) {
            try (Statement read = source.createStatement();
                 ResultSet rs = read.executeQuery("SELECT national_code,skill_name,skill_type,method FROM course_skill_edge");
                 PreparedStatement insert = index.prepareStatement("INSERT INTO learning_search VALUES(?,?,?,?)")) {
                while (rs.next()) {
                    String code = rs.getString("national_code");
                    String skill = rs.getString("skill_name");
                    if (code == null || skill == null) continue;
                    insert.setString(1, code);
                    insert.setString(2, WarehouseText.text(skill, 240));
                    insert.setString(3, WarehouseText.text(rs.getString("skill_type"), 80));
                    insert.setString(4, WarehouseText.text(rs.getString("method"), 100));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        if (sourceTables.contains("outside_jobs")) {
            java.util.Map<String, Set<String>> postcodeSa4 = new java.util.HashMap<>();
            if (sourceTables.contains("dim_location_asgs")) {
                try (Statement read = source.createStatement();
                     ResultSet rs = read.executeQuery("SELECT DISTINCT postcode,state_code,sa4_code FROM dim_location_asgs")) {
                    while (rs.next()) {
                        String key = rs.getString("state_code") + ":" + rs.getString("postcode");
                        String sa4 = rs.getString("sa4_code");
                        if (sa4 != null) postcodeSa4.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(sa4);
                    }
                }
            }
            try (Statement read = source.createStatement();
                 ResultSet rs = read.executeQuery(
                     "SELECT id,title,skills_json,description,city,state,postal_code FROM outside_jobs " +
                     "WHERE privacy_level='PUBLIC' AND status='active' AND upper(country) IN ('AU','AUSTRALIA','AUS')");
                 PreparedStatement insertJob = index.prepareStatement("INSERT INTO job_search VALUES(?,?,?,?)");
                 PreparedStatement insertGeo = index.prepareStatement("INSERT OR IGNORE INTO job_geo VALUES(?,?,?,?,?)")) {
                while (rs.next()) {
                    String id = String.valueOf(rs.getObject("id"));
                    insertJob.setString(1, id);
                    insertJob.setString(2, WarehouseText.text(rs.getString("title"), 200));
                    insertJob.setString(3, String.join(" | ", WarehouseText.list(rs.getString("skills_json"), 30)));
                    insertJob.setString(4, WarehouseText.text(rs.getString("description"), 1800));
                    insertJob.addBatch();

                    String state = rs.getString("state");
                    String postal = rs.getString("postal_code");
                    Set<String> sa4s = postcodeSa4.get(state + ":" + postal);
                    insertGeo.setString(1, id);
                    insertGeo.setString(2, ns(rs.getString("city")).toLowerCase());
                    insertGeo.setString(3, WarehouseText.text(state, 3).toUpperCase());
                    insertGeo.setString(4, WarehouseText.text(postal, 4));
                    insertGeo.setString(5, sa4s != null && sa4s.size() == 1 ? sa4s.iterator().next() : "");
                    insertGeo.addBatch();
                }
                insertJob.executeBatch();
                insertGeo.executeBatch();
            }
        }
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private static String ns(String v) { return v == null ? "" : v; }
}
