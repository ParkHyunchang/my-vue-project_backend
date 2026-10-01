package com.hyunchang.webapp.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicitly widen existing timeline columns; Hibernate schema update may leave VARCHAR intact. */
@Component
@Order(1)
public class TimelineDescriptionMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TimelineDescriptionMigration.class);
    private final JdbcTemplate jdbc;

    public TimelineDescriptionMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String table : List.of("history", "dating")) {
            String type = columnType(table);
            if (List.of("text", "mediumtext", "longtext").contains(type)) {
                log.info("[MIGRATION] {}.description already {}", table, type);
                continue;
            }
            if (!List.of("varchar", "char", "tinytext").contains(type)) {
                throw new IllegalStateException(
                        "Unexpected description column type: " + table + ": " + type);
            }
            String nullable =
                    jdbc.queryForObject(
                            "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'description'",
                            String.class,
                            table);
            // Table identifiers come exclusively from the fixed list above. Preserve nullability.
            jdbc.execute(
                    "ALTER TABLE `"
                            + table
                            + "` MODIFY COLUMN `description` TEXT "
                            + ("YES".equals(nullable) ? "NULL" : "NOT NULL"));
            if (!"text".equals(columnType(table))) {
                throw new IllegalStateException("Description migration was not applied: " + table);
            }
            log.info(
                    "[MIGRATION] {}.description widened from {} to TEXT and verified", table, type);
        }
    }

    private String columnType(String table) {
        return jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'description'",
                String.class,
                table);
    }
}
