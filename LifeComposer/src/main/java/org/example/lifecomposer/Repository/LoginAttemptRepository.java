package org.example.lifecomposer.Repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persistence for {@code login_attempt}：登录失败计数与锁定状态。
 *
 * <p>v0.1.6 改成两层维度（审核反馈 A 节）：</p>
 * <ul>
 *   <li>{@code scope=pair}：账号 + IP，严。攻击者只能锁住自己这个 IP 的组合，
 *       锁不死受害者在别处的登录。</li>
 *   <li>{@code scope=account}：账号总量，松。分布式换 IP 猜密码会撞上这一层。</li>
 * </ul>
 * <p>主键是 {@code (scope, attempt_key)}，一层一套计数，互不污染。</p>
 *
 * <p><b>并发安全</b>（审核 B1）：计数只用单条
 * {@code INSERT ... ON CONFLICT DO UPDATE SET failures = failures + 1}，
 * 不再"先读再加"，避免并发请求读到同一个旧值后互相覆盖。</p>
 *
 * <p><b>时间戳</b>（审核 B3）：{@code window_start} / {@code locked_until} 都是
 * UTC 文本 {@code yyyy-MM-dd HH:mm:ss}，与 v0.1.3 的约定一致。</p>
 */
@Repository
public class LoginAttemptRepository {

    public static final String SCOPE_PAIR = "pair";
    public static final String SCOPE_ACCOUNT = "account";

    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单条尝试记录；{@code lockedUntil}/{@code windowStart} 为 null 表示未设置。 */
    public record Attempt(int failures, Instant windowStart, Instant lockedUntil) {

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        public long retryAfterSeconds(Instant now) {
            if (!lockedAt(now)) {
                return 0L;
            }
            long millis = lockedUntil.toEpochMilli() - now.toEpochMilli();
            return Math.max(1L, (millis + 999) / 1000);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public LoginAttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createTableIfNeeded() {
        migrateLegacyTableIfNeeded();
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS login_attempt (
                    scope TEXT NOT NULL,
                    attempt_key TEXT NOT NULL,
                    failures INTEGER NOT NULL DEFAULT 0,
                    window_start TEXT NOT NULL DEFAULT (datetime('now')),
                    locked_until TEXT,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (scope, attempt_key)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_login_attempt_locked "
                + "ON login_attempt(locked_until)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_login_attempt_updated "
                + "ON login_attempt(updated_at)");
    }

    /**
     * v0.1.5 的表是 {@code attempt_key} 单列主键、{@code locked_until} 存 epoch 毫秒。
     * 升级时把它搬到新结构里（记为 {@code scope=account}，epoch 转 UTC 文本）。
     *
     * <p>代价：旧记录是按"账号 5 次"锁的，搬到松的那一层后阈值变成 30 次，
     * 所以升级瞬间最多会释放一个还在 15 分钟锁定内的账号。这段窗口短且只发生一次，
     * 比直接丢表更可接受 —— 不丢数据，也不影响新的计数。</p>
     */
    private void migrateLegacyTableIfNeeded() {
        if (!tableExists("login_attempt") || columnExists("login_attempt", "scope")) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE login_attempt RENAME TO login_attempt_legacy");
        jdbcTemplate.execute("""
                CREATE TABLE login_attempt (
                    scope TEXT NOT NULL,
                    attempt_key TEXT NOT NULL,
                    failures INTEGER NOT NULL DEFAULT 0,
                    window_start TEXT NOT NULL DEFAULT (datetime('now')),
                    locked_until TEXT,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (scope, attempt_key)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO login_attempt (scope, attempt_key, failures, window_start, locked_until, updated_at)
                SELECT 'account', attempt_key, failures, datetime('now'),
                       CASE WHEN locked_until > 0
                            THEN strftime('%Y-%m-%d %H:%M:%S', locked_until / 1000.0, 'unixepoch')
                            ELSE NULL END,
                       COALESCE(updated_at, datetime('now'))
                FROM login_attempt_legacy
                """);
        jdbcTemplate.execute("DROP TABLE login_attempt_legacy");
    }

    public Optional<Attempt> find(String scope, String attemptKey) {
        List<Attempt> rows = jdbcTemplate.query(
                "SELECT failures, window_start, locked_until FROM login_attempt "
                        + "WHERE scope = ? AND attempt_key = ?",
                (rs, rowNum) -> new Attempt(
                        rs.getInt("failures"),
                        parse(rs.getString("window_start")),
                        parse(rs.getString("locked_until"))),
                scope, attemptKey);
        return rows.stream().findFirst();
    }

    /**
     * 连续失败计数（第一层用）：不设窗口，锁定到期或登录成功才清零。
     *
     * @return 自增后的累计次数
     */
    public int incrementConsecutive(String scope, String attemptKey) {
        Integer failures = jdbcTemplate.queryForObject("""
                INSERT INTO login_attempt (scope, attempt_key, failures, window_start, locked_until, updated_at)
                VALUES (?, ?, 1, datetime('now'), NULL, datetime('now'))
                ON CONFLICT(scope, attempt_key) DO UPDATE SET
                    failures = failures + 1,
                    updated_at = datetime('now')
                RETURNING failures
                """, Integer.class, scope, attemptKey);
        return failures == null ? 1 : failures;
    }

    /**
     * 窗口内失败计数（第二层用）：窗口过期后从 1 重新开始。
     *
     * @return 自增后的窗口内次数
     */
    public int incrementWithinWindow(String scope, String attemptKey, int windowMinutes) {
        String modifier = "-" + Math.max(1, windowMinutes) + " minutes";
        Integer failures = jdbcTemplate.queryForObject("""
                INSERT INTO login_attempt (scope, attempt_key, failures, window_start, locked_until, updated_at)
                VALUES (?, ?, 1, datetime('now'), NULL, datetime('now'))
                ON CONFLICT(scope, attempt_key) DO UPDATE SET
                    failures = CASE
                        WHEN window_start <= datetime('now', ?) THEN 1
                        ELSE failures + 1 END,
                    window_start = CASE
                        WHEN window_start <= datetime('now', ?) THEN datetime('now')
                        ELSE window_start END,
                    updated_at = datetime('now')
                RETURNING failures
                """, Integer.class, scope, attemptKey, modifier, modifier);
        return failures == null ? 1 : failures;
    }

    /**
     * 上锁。已经锁着的不延长（避免攻击者每失败一次就把锁往后推）。
     */
    public void lock(String scope, String attemptKey, long lockMillis) {
        long seconds = Math.max(1L, (lockMillis + 999) / 1000);
        jdbcTemplate.update("""
                UPDATE login_attempt
                SET locked_until = datetime('now', ?), updated_at = datetime('now')
                WHERE scope = ? AND attempt_key = ?
                  AND (locked_until IS NULL OR locked_until <= datetime('now'))
                """, "+" + seconds + " seconds", scope, attemptKey);
    }

    public void delete(String scope, String attemptKey) {
        jdbcTemplate.update("DELETE FROM login_attempt WHERE scope = ? AND attempt_key = ?",
                scope, attemptKey);
    }

    /** 仅供测试与运维清理使用。 */
    public void clearAll() {
        jdbcTemplate.update("DELETE FROM login_attempt");
    }

    /** 清理"锁定期已过"的记录：锁一旦过期，计数从头开始。 */
    public int deleteExpiredLocks() {
        return jdbcTemplate.update(
                "DELETE FROM login_attempt WHERE locked_until IS NOT NULL AND locked_until <= datetime('now')");
    }

    /** 清理长期没有更新的计数行（含匿名账号名打出来的垃圾行）。 */
    public int purgeIdle(int retentionDays) {
        int days = Math.max(1, retentionDays);
        return jdbcTemplate.update(
                "DELETE FROM login_attempt WHERE (locked_until IS NULL OR locked_until <= datetime('now')) "
                        + "AND updated_at <= datetime('now', ?)",
                "-" + days + " days");
    }

    public int countAll() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM login_attempt", Integer.class);
        return count == null ? 0 : count;
    }

    public int countByScope(String scope) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_attempt WHERE scope = ?", Integer.class, scope);
        return count == null ? 0 : count;
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
    }

    private static Instant parse(String dbTime) {
        if (dbTime == null || dbTime.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(dbTime.trim().replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC);
    }

    /** 把瞬时转成库里的 UTC 文本格式（测试断言与迁移复用）。 */
    public static String formatUtc(Instant instant) {
        return DB_TIME.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /** 账号 key 归一：去空白、转小写；空值归一为空串（是否跳过记录由调用方决定）。 */
    public static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed.toLowerCase(Locale.ROOT);
    }
}
