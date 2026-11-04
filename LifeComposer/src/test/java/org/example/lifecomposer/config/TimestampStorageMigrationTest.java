package org.example.lifecomposer.config;

import org.example.lifecomposer.LifeComposerApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.1.3: Timestamp columns are UTC text; legacy epoch-millis rows are converted idempotently. */
@SpringBootTest
@ActiveProfiles("test")
class TimestampStorageMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseInitializer initializer;

    @Test
    @DisplayName("new Timestamp writes are UTC text and legacy integers are normalised")
    void timestampsAreUtcText() {
        LifeComposerApplication.useUtcStorageZone();
        jdbcTemplate.update("DELETE FROM users WHERE username = 'ts_user'");
        // epoch 1_700_000_000_000 ms = 2023-11-14 22:13:20 UTC
        jdbcTemplate.update("INSERT INTO users(username, password_hash, type, is_banned, created_at, updated_at) "
                + "VALUES ('ts_user', 'x', 1, 0, ?, ?)", 1_700_000_000_000L, 1_700_000_000_000L);
        assertEquals("integer", jdbcTemplate.queryForObject(
                "SELECT typeof(created_at) FROM users WHERE username = 'ts_user'", String.class));

        initializer.normalizeTimestampStorage();
        initializer.normalizeTimestampStorage(); // idempotent

        assertEquals("2023-11-14 22:13:20", jdbcTemplate.queryForObject(
                "SELECT created_at FROM users WHERE username = 'ts_user'", String.class));

        jdbcTemplate.update("UPDATE users SET ban_end_time = ? WHERE username = 'ts_user'", new Timestamp(0L));
        assertEquals("1970-01-01 00:00:00", jdbcTemplate.queryForObject(
                "SELECT ban_end_time FROM users WHERE username = 'ts_user'", String.class));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT ban_end_time FROM users WHERE username = 'ts_user'", Timestamp.class).getTime());
        jdbcTemplate.update("DELETE FROM users WHERE username = 'ts_user'");
    }
}
