package com.yuzee.tokenlab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzee.tokenlab.repository.ConversationRepository;
import com.yuzee.tokenlab.repository.FileConversationRepository;
import com.yuzee.tokenlab.repository.JdbcConversationRepository;
import com.yuzee.tokenlab.service.ConversationLogService;
import com.yuzee.tokenlab.service.FileConversationLogService;
import com.yuzee.tokenlab.service.JdbcConversationLogService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Postgres persistence is entirely optional. spring.datasource.url (bound from DATABASE_URL) is
 * blank by default, and TokenlabApplication excludes Spring Boot's own DataSourceAutoConfiguration
 * / JdbcTemplateAutoConfiguration, so nothing ever tries to build a default DataSource from a
 * blank URL — this class is the only place a DataSource gets created, and only when the URL is
 * actually non-blank. When it's blank, conversation persistence falls back to local JSON files
 * (FileConversationRepository / FileConversationLogService).
 */
@Configuration
public class PersistenceConfig {

    // SpEL: the placeholder is resolved to a string literal first, then .isBlank() is evaluated.
    private static final String URL_PRESENT = "!'${spring.datasource.url:}'.isBlank()";
    private static final String URL_BLANK = "'${spring.datasource.url:}'.isBlank()";

    @Bean
    @ConditionalOnExpression(URL_PRESENT)
    public DataSource dataSource(@Value("${spring.datasource.url}") String rawUrl) {
        ConnectionInfo info = parse(rawUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(info.jdbcUrl());
        if (info.username() != null) config.setUsername(info.username());
        if (info.password() != null) config.setPassword(info.password());
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5_000);
        config.setIdleTimeout(30_000);
        return new HikariDataSource(config);
    }

    @Bean
    @ConditionalOnExpression(URL_PRESENT)
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnExpression(URL_PRESENT)
    public ConversationRepository jdbcConversationRepository(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        return new JdbcConversationRepository(jdbcTemplate, mapper);
    }

    @Bean
    @ConditionalOnExpression(URL_BLANK)
    public ConversationRepository fileConversationRepository() {
        return new FileConversationRepository();
    }

    @Bean
    @ConditionalOnExpression(URL_PRESENT)
    public ConversationLogService jdbcConversationLogService(JdbcTemplate jdbcTemplate) {
        return new JdbcConversationLogService(jdbcTemplate);
    }

    @Bean
    @ConditionalOnExpression(URL_BLANK)
    public ConversationLogService fileConversationLogService(ObjectMapper mapper) {
        return new FileConversationLogService(mapper);
    }

    /**
     * DATABASE_URL arrives in "postgres://user:pass@host:port/db?query" form (same as the Node
     * app's `pg` client accepts directly) — the JDBC driver needs "jdbc:postgresql://..." plus
     * separate username/password. Also appends sslmode=require, the JDBC equivalent of the old
     * app's `ssl: { rejectUnauthorized: false }` (encrypt, don't verify the cert), and
     * prepareThreshold=0: our DATABASE_URL points at Supabase's port-6543 PgBouncer pooler, which
     * runs in transaction mode and hands a client statements from whichever backend connection is
     * free. pgjdbc's default server-side prepared-statement optimization names statements
     * sequentially ("S_1", "S_2", ...) per JDBC connection, so a name can collide with one another
     * client already prepared on the physical backend PgBouncer just handed us, throwing
     * "prepared statement \"S_1\" already exists". prepareThreshold=0 disables that optimization
     * (always sends plain/unnamed statements), which is the standard fix for PgBouncer compatibility.
     */
    private ConnectionInfo parse(String raw) {
        if (raw.startsWith("jdbc:")) return new ConnectionInfo(raw, null, null);
        URI uri = URI.create(raw);
        String user = null;
        String password = null;
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            password = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
        }
        String query = uri.getQuery();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost()
            + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
            + uri.getPath()
            + (query != null && !query.isBlank() ? "?" + query + "&sslmode=require" : "?sslmode=require")
            + "&prepareThreshold=0";
        return new ConnectionInfo(jdbcUrl, user, password);
    }

    private record ConnectionInfo(String jdbcUrl, String username, String password) {}
}
