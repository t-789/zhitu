package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.User;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<User> USER_ROW_MAPPER = new RowMapper<>() {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User user = new User();
            user.setId(rs.getInt("id"));
            user.setUsername(rs.getString("username"));
            user.setStudentId(rs.getString("student_id"));
            user.setEmail(rs.getString("email"));
            user.setRealName(rs.getString("real_name"));
            user.setPasswordHash(rs.getString("password_hash"));
            user.setType(rs.getInt("type"));
            user.setBanned(rs.getBoolean("is_banned"));
            user.setBanEndTime(rs.getTimestamp("ban_end_time"));
            user.setAvatar(rs.getString("avatar"));
            user.setCreatedAt(rs.getTimestamp("created_at"));
            user.setUpdatedAt(rs.getTimestamp("updated_at"));
            user.setPasswordResetRequired(rs.getBoolean("password_reset_required"));
            user.setTempPasswordExpiresAt(rs.getTimestamp("temp_password_expires_at"));
            user.setPasswordChangedAt(rs.getTimestamp("password_changed_at"));
            user.setCredentialVersion(rs.getInt("credential_version"));
            return user;
        }
    };

    public void createUserTableIfNeeded() {
        String sql = """
                CREATE TABLE IF NOT EXISTS users (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  username TEXT UNIQUE NOT NULL,
                  student_id TEXT NULL,
                  email TEXT NULL,
                  real_name TEXT NULL,
                  password_hash TEXT NOT NULL,
                  type INTEGER NOT NULL DEFAULT 1 CHECK(type IN (1,2)),
                  is_banned BOOLEAN NOT NULL DEFAULT 0,
                  ban_end_time TIMESTAMP NULL,
                  avatar TEXT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  password_reset_required BOOLEAN NOT NULL DEFAULT 0,
                  temp_password_expires_at TIMESTAMP NULL,
                  password_changed_at TIMESTAMP NULL,
                  credential_version INTEGER NOT NULL DEFAULT 1
                )
                """;
        jdbcTemplate.execute(sql);
    }

    public void migrateUserSchema() {
        // v0.1.1: legacy/demo/admin accounts have no known email or name.
        if (!hasColumn("users", "email")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN email TEXT NULL");
        }
        if (!hasColumn("users", "real_name")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN real_name TEXT NULL");
        }
        // v0.1.3: student id is account identity and lives on users (not user_profiles).
        if (!hasColumn("users", "student_id")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN student_id TEXT NULL");
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_student_id "
                + "ON users(student_id) WHERE student_id IS NOT NULL");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_ci "
                + "ON users(lower(email)) WHERE email IS NOT NULL");
        // Remove legacy column from old project structure.
        // SQLite (3.35+) supports DROP COLUMN. If runtime SQLite is older, this will be ignored safely.
//        if (hasColumn("users", "credit")) {
//            try {
//                jdbcTemplate.execute("ALTER TABLE users DROP COLUMN credit");
//            } catch (Exception ignored) {
//                // Keep startup resilient on older SQLite engines.
//            }
//        }

        // Remove legacy blind type and old type=0 users. Keep only:
        // 1 = USER, 2 = ADMIN
        jdbcTemplate.update("UPDATE users SET type = 1 WHERE type IS NULL OR type IN (0, 1)");

//        if (!hasColumn("users", "password_hash") && hasColumn("users", "password")) {
//            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN password_hash TEXT");
//            jdbcTemplate.update("UPDATE users SET password_hash = password WHERE password_hash IS NULL");
//        }

        if (!hasColumn("users", "is_banned")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN is_banned BOOLEAN NOT NULL DEFAULT 0");
        }

        if (!hasColumn("users", "ban_end_time")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN ban_end_time TIMESTAMP NULL");
        }

        if (!hasColumn("users", "avatar")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN avatar TEXT NULL");
        }

        if (!hasColumn("users", "created_at")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        }

        if (!hasColumn("users", "updated_at")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        }

        // v0.0.6 review follow-up: real temporary-password state plus a credential
        // generation counter used to invalidate sessions after a password change.
        if (!hasColumn("users", "password_reset_required")) {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN password_reset_required BOOLEAN NOT NULL DEFAULT 0");
        }

        if (!hasColumn("users", "temp_password_expires_at")) {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN temp_password_expires_at TIMESTAMP NULL");
        }

        if (!hasColumn("users", "password_changed_at")) {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP NULL");
        }

        if (!hasColumn("users", "credential_version")) {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 1");
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        String sql = "PRAGMA table_info(" + tableName + ")";
        List<String> columns = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("name"));
        return columns.stream().anyMatch(col -> col.equalsIgnoreCase(columnName));
    }

    public int insertUser(User user) {
        String sql = """
                INSERT INTO users(username, student_id, email, real_name, password_hash, type, is_banned, ban_end_time, avatar, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return jdbcTemplate.update(sql,
                user.getUsername(),
                user.getStudentId(),
                user.getEmail(),
                user.getRealName(),
                user.getPasswordHash(),
                user.getType(),
                Boolean.TRUE.equals(user.getBanned()),
                user.getBanEndTime(),
                user.getAvatar(),
                now,
                now
        );
    }

    public User findByUsername(String username) {
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM users WHERE username = ?", USER_ROW_MAPPER, username);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public User findByEmail(String email) {
        if (email == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM users WHERE lower(email) = lower(?)",
                    USER_ROW_MAPPER, email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public User findByStudentId(String studentId) {
        if (studentId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM users WHERE student_id = ?",
                    USER_ROW_MAPPER, studentId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** Login lookup: student id, then username, then email. */
    public User findByAccount(String account) {
        if (account == null) {
            return null;
        }
        User user = findByStudentId(account);
        if (user == null) {
            user = findByUsername(account);
        }
        if (user == null) {
            user = findByEmail(account);
        }
        return user;
    }

    public boolean usernameExistsIgnoringCase(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(username) = lower(?)", Integer.class, username);
        return count != null && count > 0;
    }

    public User findById(int id) {
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM users WHERE id = ?", USER_ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<User> findAll() {
        return jdbcTemplate.query("SELECT * FROM users ORDER BY id", USER_ROW_MAPPER);
    }

    /**
     * Changes the role. A real change also bumps {@code credential_version}: the
     * authorities of a session are fixed at login, so without the bump the user would
     * keep the old role until they log out. Setting the role they already have is a
     * no-op and leaves their sessions alone.
     */
    public boolean updateUserType(int userId, int type) {
        int rows = jdbcTemplate.update("""
                UPDATE users
                SET credential_version = credential_version + CASE WHEN type = ? THEN 0 ELSE 1 END,
                    type = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, type, type, userId);
        return rows > 0;
    }

    /**
     * Review follow-up (P1): atomic demotion that can never remove the last
     * loginable administrator. The guard lives inside the UPDATE statement and
     * is restricted to a single row, so there is no read-then-write window on the
     * database side. Callers additionally run inside a write transaction
     * (see {@code UserService#revokeAdminPermission}).
     *
     * <p>Demotion bumps {@code credential_version}, so the demoted user's existing
     * sessions (which still carry ROLE_ADMIN) are rejected on their next request.</p>
     *
     * @return 1 when the user was demoted, 0 when it is not an administrator or
     *         demoting it would leave the system without a loginable admin.
     */
    public int revokeAdminIfNotLast(int userId) {
        return jdbcTemplate.update("""
                UPDATE users
                SET type = 1, credential_version = credential_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND type = 2
                  AND (SELECT COUNT(*) FROM users WHERE type = 2 AND is_banned = 0) > 1
                """, userId);
    }

    /**
     * Review follow-up (P1): atomic ban that can never ban the last loginable
     * administrator. Banning an already banned account stays allowed (it only
     * refreshes the end time).
     *
     * @return 1 when the ban was applied, 0 when the user does not exist or the
     *         statement would leave the system without a loginable admin.
     */
    public int banIfNotLastLoginableAdmin(int userId, Timestamp banEndTime) {
        return jdbcTemplate.update("""
                UPDATE users
                SET is_banned = 1, ban_end_time = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND NOT (type = 2 AND is_banned = 0
                           AND (SELECT COUNT(*) FROM users WHERE type = 2 AND is_banned = 0) <= 1)
                """, banEndTime, userId);
    }

    public boolean updateUserAvatar(int userId, String avatarPath) {
        int rows = jdbcTemplate.update("UPDATE users SET avatar = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", avatarPath, userId);
        return rows > 0;
    }

    /**
     * Any password change bumps {@code credential_version}, which is what makes
     * already-issued sessions detectable (and therefore invalidatable) without a
     * server-side session registry.
     */
    public boolean updateUserPassword(int userId, String passwordHash) {
        int rows = jdbcTemplate.update("""
                        UPDATE users
                        SET password_hash = ?,
                            password_changed_at = CURRENT_TIMESTAMP,
                            credential_version = credential_version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                passwordHash, userId);
        return rows > 0;
    }

    /**
     * Installs an administrator-issued temporary password: it must be replaced
     * before anything else can be used, and it stops working at {@code expiresAt}.
     */
    public boolean updateUserPasswordAsTemporary(int userId, String passwordHash, Timestamp expiresAt) {
        int rows = jdbcTemplate.update("""
                        UPDATE users
                        SET password_hash = ?,
                            password_reset_required = 1,
                            temp_password_expires_at = ?,
                            password_changed_at = CURRENT_TIMESTAMP,
                            credential_version = credential_version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                passwordHash, expiresAt, userId);
        return rows > 0;
    }

    /** Replaces the temporary password and clears the forced-change state. */
    public boolean updateUserPasswordAndClearReset(int userId, String passwordHash) {
        int rows = jdbcTemplate.update("""
                        UPDATE users
                        SET password_hash = ?,
                            password_reset_required = 0,
                            temp_password_expires_at = NULL,
                            password_changed_at = CURRENT_TIMESTAMP,
                            credential_version = credential_version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                passwordHash, userId);
        return rows > 0;
    }

    public boolean updateBanStatus(int userId, boolean banned, Timestamp banEndTime) {
        int rows = jdbcTemplate.update(
                "UPDATE users SET is_banned = ?, ban_end_time = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                banned,
                banEndTime,
                userId
        );
        return rows > 0;
    }

    public long countAdminUsers() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE type = 2", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Milestone 6: administrators that can still authenticate. A banned account
     * is excluded; an expired ban is auto-cleared on the next login, so it is
     * deliberately not counted here (conservative: it may block a demotion that
     * would in fact have been safe).
     */
    public long countLoginableAdmins() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE type = 2 AND is_banned = 0", Long.class);
        return count == null ? 0 : count;
    }

    /** Password generation currently stored for a user (null when it is gone). */
    public Integer findCredentialVersion(int userId) {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT credential_version FROM users WHERE id = ?", Integer.class, userId);
        return versions.isEmpty() ? null : versions.get(0);
    }
}
