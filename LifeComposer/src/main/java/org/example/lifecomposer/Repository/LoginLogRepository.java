package org.example.lifecomposer.Repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 登录审计（{@code login_log}）。
 * <p>
 * v0.1.4：登录成功/失败/被拒的每一次尝试都落一行结构化记录，便于按账号与时间回溯；
 * 只记录账号、IP、UA、结果与可读原因，**任何情况下都不写入密码、Cookie、Session、Token**。
 */
@Repository
public class LoginLogRepository {

    /** 失败。 */
    public static final int STATUS_FAILURE = 0;
    /** 成功。 */
    public static final int STATUS_SUCCESS = 1;

    private static final int MAX_ACCOUNT = 128;
    private static final int MAX_IP = 64;
    private static final int MAX_USER_AGENT = 256;
    private static final int MAX_REASON = 200;

    public record Entry(long id,
                        Integer userId,
                        String account,
                        String ipAddress,
                        String userAgent,
                        int status,
                        String failureReason,
                        String createdAt) {
        public boolean success() {
            return status == STATUS_SUCCESS;
        }
    }

    private static final RowMapper<Entry> ROW_MAPPER = (rs, rowNum) -> {
        Object rawUserId = rs.getObject("user_id");
        return new Entry(
                rs.getLong("id"),
                rawUserId == null ? null : ((Number) rawUserId).intValue(),
                rs.getString("account"),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getInt("status"),
                rs.getString("failure_reason"),
                rs.getString("created_at")
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public LoginLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 幂等建表；由 {@code DatabaseInitializer} 在启动时调用。 */
    public void createTableIfNeeded() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS login_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NULL,
                    account TEXT NOT NULL,
                    ip_address TEXT NULL,
                    user_agent TEXT NULL,
                    status INTEGER NOT NULL,
                    failure_reason TEXT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_login_log_account_time ON login_log(account, created_at)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_login_log_created ON login_log(created_at)");
    }

    /**
     * 写入一条审计记录，返回自增主键。
     * <p>调用方应把异常吞掉并只记 warn：审计失败绝不能让一次登录失败/成功变样。
     */
    public long insert(Integer userId,
                       String account,
                       String ipAddress,
                       String userAgent,
                       int status,
                       String failureReason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO login_log (user_id, account, ip_address, user_agent, status, failure_reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId);
            ps.setString(2, truncate(account, MAX_ACCOUNT));
            ps.setString(3, truncate(ipAddress, MAX_IP));
            ps.setString(4, truncate(userAgent, MAX_USER_AGENT));
            ps.setInt(5, status);
            ps.setString(6, truncate(failureReason, MAX_REASON));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 某账号最近的登录记录（新的在前）。 */
    public List<Entry> findRecentByAccount(String account, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query(
                "SELECT * FROM login_log WHERE account = ? ORDER BY id DESC LIMIT " + safeLimit,
                ROW_MAPPER,
                account);
    }

    /** 审计表总行数，供测试与运维核对。 */
    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM login_log", Long.class);
        return count == null ? 0L : count;
    }

    public Optional<Entry> findLatestByAccount(String account) {
        List<Entry> rows = findRecentByAccount(account, 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
