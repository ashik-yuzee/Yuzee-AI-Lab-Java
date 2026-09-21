package com.yuzee.tokenlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// DataSource autoconfiguration is excluded because spring.datasource.url is blank by default
// (no DATABASE_URL set). Without this exclusion, Spring Boot tries to build a default
// DataSource from that blank URL and refuses to start. PersistenceConfig builds its own
// DataSource/JdbcTemplate beans, but only when the URL is actually non-blank.
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class
})
@EnableScheduling
public class TokenlabApplication {
    public static void main(String[] args) {
        SpringApplication.run(TokenlabApplication.class, args);
    }
}
