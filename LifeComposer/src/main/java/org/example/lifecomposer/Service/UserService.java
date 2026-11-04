package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Entity.UserType;
import org.example.lifecomposer.config.AdminSecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

@Service
public class UserService {

    /**
     * Outcome of an administrator-removal operation, so controllers can answer
     * with a precise status instead of a generic 400 (review follow-up: the old
     * "last administrator" rejection was reported as "user does not exist").
     */
    public enum AdminRemovalResult {
        /** The state change was applied. */
        DONE,
        /** No such user. */
        NOT_FOUND,
        /** Refused: the system must keep at least one loginable administrator. */
        LAST_ADMIN,
        /** Refused: the ban duration expression was invalid. */
        INVALID_INPUT
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminSecurityProperties adminSecurityProperties;
    private final AdminPasswordPolicy passwordPolicy;

    /**
     * A throw-away hash with exactly the configured strength, so a login attempt for an
     * unknown account still pays one bcrypt comparison. Without it the response time
     * itself would tell an attacker whether the account exists (anti-enumeration).
     */
    private final String timingDummyHash;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AdminSecurityProperties adminSecurityProperties,
                       AdminPasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminSecurityProperties = adminSecurityProperties;
        this.passwordPolicy = passwordPolicy;
        this.timingDummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public boolean register(String username, String rawPassword) {
        return register(username, rawPassword, null, null);
    }

    public boolean register(String username, String rawPassword, String email, String realName) {
        return register(username, null, rawPassword, email, realName);
    }

    /**
     * Registers a user. {@code studentId} is the campus id used by the
     * {@code /api/auth/register} flow (then also the username); legacy callers pass null.
     * Student ids and usernames share one namespace so one login string maps to one account.
     */
    public boolean register(String username, String studentId, String rawPassword,
                            String email, String realName) {
        String normalizedEmail = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByUsername(username) != null
                || userRepository.findByStudentId(username) != null
                || userRepository.findByEmail(username) != null
                || (studentId != null && (userRepository.findByStudentId(studentId) != null
                || userRepository.findByUsername(studentId) != null))
                || (normalizedEmail != null && (userRepository.findByEmail(normalizedEmail) != null
                || userRepository.usernameExistsIgnoringCase(normalizedEmail)))) {
            return false;
        }

        User user = new User();
        user.setUsername(username);
        user.setStudentId(studentId);
        user.setEmail(normalizedEmail);
        user.setRealName(realName == null || realName.isBlank() ? null : realName.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setType(UserType.USER);
        user.setBanned(false);

        try {
            return userRepository.insertUser(user) > 0;
        } catch (DataIntegrityViolationException duplicate) {
            // The database unique constraints also protect concurrent registrations.
            return false;
        }
    }

    /**
     * Verifies credentials. Order matters for anti-enumeration: the password is checked
     * <em>before</em> the ban state, so a banned account looks exactly like a wrong
     * password unless the caller already knows the correct password. The rejection
     * message never carries the unban timestamp.
     *
     * @return the authenticated user, or {@code null} for any credential failure
     * @throws IllegalStateException only when the password is correct but the account is banned
     */
    public User login(String username, String rawPassword) {
        User user = userRepository.findByAccount(username);
        if (user == null) {
            // Pay the same bcrypt cost as a real comparison (timing side channel).
            passwordEncoder.matches(rawPassword, timingDummyHash);
            return null;
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            return null;
        }

        if (Boolean.TRUE.equals(user.getBanned())) {
            Timestamp banEndTime = user.getBanEndTime();
            if (banEndTime == null || banEndTime.after(Timestamp.from(Instant.now()))) {
                throw new IllegalStateException("账户已被封禁，请联系管理员");
            }
            // Ban window is over, auto-unban for consistency.
            userRepository.updateBanStatus(user.getId(), false, null);
            user.setBanned(false);
            user.setBanEndTime(null);
        }

        return user;
    }

    /** True when the stored temporary password has passed its deadline. */
    public boolean isTemporaryPasswordExpired(User user) {
        Timestamp expiresAt = user.getTempPasswordExpiresAt();
        return expiresAt != null && expiresAt.before(Timestamp.from(Instant.now()));
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User getById(int id) {
        return userRepository.findById(id);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public boolean grantAdminPermission(int userId) {
        return userRepository.updateUserType(userId, UserType.ADMIN);
    }

    /**
     * Milestone 6 guard (review follow-up: race-free).
     *
     * <p>The whole operation runs in one immediate write transaction, and the
     * "keep at least one loginable administrator" condition is additionally part
     * of the UPDATE statement itself, so two concurrent demotions can never both
     * succeed (see {@code UserRepository#revokeAdminIfNotLast}).</p>
     */
    @Transactional
    public AdminRemovalResult revokeAdminPermission(int userId) {
        User target = userRepository.findById(userId);
        if (target == null) {
            return AdminRemovalResult.NOT_FOUND;
        }
        if (target.getType() == null || target.getType() != UserType.ADMIN) {
            // Already a normal user: idempotent success, matching the old behaviour.
            return AdminRemovalResult.DONE;
        }
        return userRepository.revokeAdminIfNotLast(userId) > 0
                ? AdminRemovalResult.DONE
                : AdminRemovalResult.LAST_ADMIN;
    }

    /**
     * Milestone 6 guard (review follow-up: race-free). The last loginable
     * administrator cannot be banned; the guard is evaluated inside the UPDATE
     * statement under an immediate write transaction.
     */
    @Transactional
    public AdminRemovalResult banUser(int userId, String banTimeExpr) {
        User target = userRepository.findById(userId);
        if (target == null) {
            return AdminRemovalResult.NOT_FOUND;
        }

        Timestamp banEndTime = null;
        if (!"0".equals(banTimeExpr)) {
            long ms = parseBanTimeToMillis(banTimeExpr);
            if (ms <= 0) {
                return AdminRemovalResult.INVALID_INPUT;
            }
            banEndTime = new Timestamp(System.currentTimeMillis() + ms);
        }

        return userRepository.banIfNotLastLoginableAdmin(userId, banEndTime) > 0
                ? AdminRemovalResult.DONE
                : AdminRemovalResult.LAST_ADMIN;
    }

    public boolean unbanUser(int userId) {
        return userRepository.updateBanStatus(userId, false, null);
    }

    /** Plain password replacement (bootstrap rotation of the legacy default account). */
    public boolean resetPassword(int userId, String newPassword) {
        return userRepository.updateUserPassword(userId, passwordEncoder.encode(newPassword));
    }

    /**
     * Review follow-up (P1/P2): installs a real temporary password — the account
     * must replace it before using anything else, and it stops working once the
     * configured TTL has passed. Every existing session of that user becomes
     * stale because the credential version is bumped.
     *
     * @return the expiry moment, or null when the user does not exist.
     */
    @Transactional
    public Timestamp setTemporaryPassword(int userId, String temporaryPassword) {
        User target = userRepository.findById(userId);
        if (target == null) {
            return null;
        }
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(
                Duration.ofMinutes(Math.max(1, adminSecurityProperties.getTempPasswordTtlMinutes()))));
        boolean updated = userRepository.updateUserPasswordAsTemporary(
                userId, passwordEncoder.encode(temporaryPassword), expiresAt);
        return updated ? expiresAt : null;
    }

    /**
     * Self-service password replacement, used both for the forced first change
     * after a temporary password and for an ordinary password change.
     *
     * @return null on success, otherwise a user-facing error message.
     */
    @Transactional
    public String changeOwnPassword(int userId, String currentPassword, String newPassword,
                                    boolean targetIsAdmin) {
        User user = userRepository.findById(userId);
        if (user == null) {
            return "用户不存在";
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return "当前密码不正确";
        }
        if (currentPassword.equals(newPassword)) {
            return "新密码不能与当前密码相同";
        }
        Optional<String> violation = targetIsAdmin
                ? passwordPolicy.violation(newPassword)
                : passwordPolicy.violationForUser(newPassword);
        if (violation.isPresent()) {
            return violation.get();
        }
        return userRepository.updateUserPasswordAndClearReset(userId, passwordEncoder.encode(newPassword))
                ? null
                : "密码更新失败";
    }

    private long parseBanTimeToMillis(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return -1;
        }

        long totalMillis = 0;
        int i = 0;
        while (i < timeStr.length()) {
            int start = i;
            while (i < timeStr.length() && Character.isDigit(timeStr.charAt(i))) {
                i++;
            }
            if (start == i || i >= timeStr.length()) {
                return -1;
            }

            int number = Integer.parseInt(timeStr.substring(start, i));
            char unit = timeStr.charAt(i++);

            switch (unit) {
                case 'y' -> totalMillis += Duration.ofDays(365L * number).toMillis();
                case 'm' -> totalMillis += Duration.ofDays(30L * number).toMillis();
                case 'd' -> totalMillis += Duration.ofDays(number).toMillis();
                case 'h' -> totalMillis += Duration.ofHours(number).toMillis();
                default -> {
                    return -1;
                }
            }
        }

        return totalMillis;
    }
}
