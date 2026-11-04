package org.example.lifecomposer.Repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Persistence for {@code login_attempt}：连续失败次数与锁定截止时间。
 * <p>
 * 记录按"账号 + IP"成对存储（key = username + '|' + clientIp），与 v0.1.3 的
 * 内存实现语义一致；区别只是落库，进程重启后锁定状态仍然有效。
 */
@Repository
public class LoginAttemptRepository {

    /** 单条尝试记录：失败次数与锁定截止时间（epoch 毫秒，0 表示未锁定）。 */
    public record Attempt(int failures, long lockedUntil) {
    }

    private final JdbcTemplate jdbcTemplate;

    public LoginAttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createTableIfNeeded() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS login_attempt (
                    attempt_key TEXT PRIMARY KEY,
                    failures INTEGER NOT NULL DEFAULT 0,
                    locked_until INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_login_attempt_locked "
                + "ON login_attempt(locked_until)");
    }

    public Optional<Attempt> find(String attemptKey) {
        return jdbcTemplate.query(
                        "SELECT failures, locked_until FROM login_attempt WHERE attempt_key = ?",
                        (rs, rowNum) -> new Attempt(rs.getInt("failures"), rs.getLong("locked_until")),
                        attemptKey)
                .stream()
                .findFirst();
    }

    public void upsert(String attemptKey, int failures, long lockedUntil) {
        jdbcTemplate.update("""
                INSERT INTO login_attempt (attempt_key, failures, locked_until, updated_at)
                VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT(attempt_key) DO UPDATE SET
                    failures = excluded.failures,
                    locked_until = excluded.locked_until,
                    updated_at = excluded.updated_at
                """, attemptKey, failures, lockedUntil);
    }

    public void delete(String attemptKey) {
        jdbcTemplate.update("DELETE FROM login_attempt WHERE attempt_key = ?", attemptKey);
    }

    /** 仅供测试与运维清理使用。 */
    public void clearAll() {
        jdbcTemplate.update("DELETE FROM login_attempt");
    }

    /**
     * 清理"锁定期已过"的记录，避免表随历史账号无限增长。
     * 未锁定（locked_until = 0）的失败计数记录保留，否则计数会被重置。
     */
    public int deleteExpiredLocks(long now) {
        return jdbcTemplate.update("DELETE FROM login_attempt WHERE locked_until > 0 AND locked_until <= ?", now);
    }

    public int countAll() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM login_attempt", Integer.class);
        return count == null ? 0 : count;
    }
}
