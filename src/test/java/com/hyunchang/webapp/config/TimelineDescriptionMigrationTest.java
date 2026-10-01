package com.hyunchang.webapp.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TimelineDescriptionMigrationTest {
    @Test
    void widensOldHistoryAndLeavesDatingTextUnchanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(startsWith("SELECT DATA_TYPE"), eq(String.class), eq("history")))
                .thenReturn("varchar", "text");
        when(jdbc.queryForObject(startsWith("SELECT DATA_TYPE"), eq(String.class), eq("dating")))
                .thenReturn("text");
        when(jdbc.queryForObject(startsWith("SELECT IS_NULLABLE"), eq(String.class), eq("history")))
                .thenReturn("YES");
        new TimelineDescriptionMigration(jdbc).run(null);
        verify(jdbc).execute("ALTER TABLE `history` MODIFY COLUMN `description` TEXT NULL");
        verify(jdbc, times(1)).execute(anyString());
    }

    @Test
    void repeatedStartupDoesNotAlterOrShrinkTextColumns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("history"))).thenReturn("text");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("dating")))
                .thenReturn("longtext");
        new TimelineDescriptionMigration(jdbc).run(null);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void unsuccessfulMigrationFailsStartupInsteadOfSilentlyContinuing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(startsWith("SELECT DATA_TYPE"), eq(String.class), eq("history")))
                .thenReturn("varchar");
        when(jdbc.queryForObject(startsWith("SELECT IS_NULLABLE"), eq(String.class), eq("history")))
                .thenReturn("NO");
        assertThrows(
                IllegalStateException.class,
                () -> new TimelineDescriptionMigration(jdbc).run(null));
        verify(jdbc).execute("ALTER TABLE `history` MODIFY COLUMN `description` TEXT NOT NULL");
    }
}
