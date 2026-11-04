package org.example.lifecomposer.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Repository.LoginLogRepository;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.Security.AuditSanitizer;
import org.example.lifecomposer.Security.SessionCredential;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Single login flow shared by {@code /api/auth/login} and the legacy
 * {@code /api/users/login}: lockout, ban/temp-password checks, session fixation
 * protection and credential stamping. Login-security owner: 王钰博.
 * <p>
 * v0.1.4：每个出口都写一条 {@code login_log} 审计（成功/失败/被拒），
 * 审计写失败只记 warn，不影响登录结果。
 */
@Service
public class AuthLoginService {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(AuthLoginService.class);

    public enum Status { OK, LOCKED, BAD_CREDENTIALS, REJECTED, TEMP_PASSWORD_EXPIRED }

    public record Result(Status status, User user, long retryAfterSeconds, String message) {
        static Result of(Status status, String message) {
            return new Result(status, null, 0, message);
        }
    }

    private final UserService userService;
    private final UserRepository userRepository;
    private final UserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;
    private final LoginLogRepository loginLogRepository;
    private final AppSecurityProperties securityProperties;

    public AuthLoginService(UserService userService,
                            UserRepository userRepository,
                            UserDetailsService userDetailsService,
                            LoginAttemptService loginAttemptService,
                            LoginLogRepository loginLogRepository,
                            AppSecurityProperties securityProperties) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
        this.loginLogRepository = loginLogRepository;
        this.securityProperties = securityProperties;
    }

    public Result login(String account, String password, HttpServletRequest httpRequest) {
        User existing = userRepository.findByAccount(account);
        // Lockout is shared by student id / username / email of the same account.
        String attemptKey = existing == null ? account.toLowerCase(Locale.ROOT) : existing.getUsername();
        // 日志里只写"能认人"的形态：账号不存在时整串打码（可能正是被填错的密码）。
        String auditName = existing == null
                ? AuditSanitizer.mask(account)
                : AuditSanitizer.forLog(attemptKey);
        String clientIp = httpRequest == null ? "unknown" : httpRequest.getRemoteAddr();
        String userAgent = httpRequest == null ? null : httpRequest.getHeader("User-Agent");
        Integer knownUserId = existing == null ? null : existing.getId();
        // 第二层（账号总量）按配置决定是否适用；管理员只计数不上锁。
        boolean accountLevelLock = !(existing != null
                && Integer.valueOf(2).equals(existing.getType())
                && !securityProperties.isLoginAdminTotalLockEnabled());
        // 该账号近期成功登录过的 IP：第二层即使锁住也不拒绝它们（Claude 复审 1）。
        Set<String> trustedIps = existing == null
                ? java.util.Set.of()
                : loginLogRepository
                        .findRecentSuccessfulIpsByUser(existing.getId(), securityProperties.getLoginTrustedIpDays())
                        .stream()
                        .map(LoginAttemptService::normalizeIp)
                        .collect(java.util.stream.Collectors.toSet());

        LoginAttemptService.LockStatus lockStatus =
                loginAttemptService.check(attemptKey, clientIp, accountLevelLock, trustedIps);
        if (lockStatus.locked()) {
            long retryAfter = lockStatus.retryAfterSeconds();
            LOG.warn("AUDIT event=login_locked username={} ip={} scope={} retryAfterSeconds={}",
                    auditName, clientIp, scopeName(lockStatus.layer()), retryAfter);
            audit(knownUserId, account, clientIp, userAgent, LoginLogRepository.STATUS_FAILURE,
                    "账号锁定中，剩余 " + minutes(retryAfter) + " 分钟");
            return new Result(Status.LOCKED, null, retryAfter, "登录失败次数过多，请稍后再试");
        }
        try {
            User user = userService.login(account, password);
            if (user == null) {
                LoginAttemptService.FailureOutcome outcome =
                        loginAttemptService.recordFailure(attemptKey, clientIp, accountLevelLock);
                int failures = outcome.failures();
                LOG.warn("AUDIT event=login_failure username={} ip={} scope={} locked={}",
                        auditName, clientIp, scopeName(outcome.layer()), outcome.locked());
                String reason;
                if (existing == null) {
                    reason = "账号不存在";
                } else if (outcome.locked()) {
                    reason = "密码错误，累计失败 " + failures + " 次触发锁定";
                } else {
                    reason = "密码错误，累计失败 " + failures + " 次";
                }
                audit(knownUserId, account, clientIp, userAgent, LoginLogRepository.STATUS_FAILURE, reason);
                return Result.of(Status.BAD_CREDENTIALS, "登录失败，用户名或密码错误");
            }
            if (Boolean.TRUE.equals(user.getPasswordResetRequired())
                    && userService.isTemporaryPasswordExpired(user)) {
                loginAttemptService.recordFailure(attemptKey, clientIp, accountLevelLock);
                LOG.warn("AUDIT event=login_rejected_temp_password_expired username={} ip={}",
                        auditName, clientIp);
                audit(user.getId(), account, clientIp, userAgent, LoginLogRepository.STATUS_FAILURE,
                        "临时密码已过期");
                return Result.of(Status.TEMP_PASSWORD_EXPIRED, "临时密码已过期，请联系管理员重新下发");
            }

            loginAttemptService.recordSuccess(attemptKey, clientIp);

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, userDetails.getPassword(), userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Session fixation protection: new session after authentication.
            HttpSession existingSession = httpRequest.getSession(false);
            if (existingSession != null) {
                existingSession.invalidate();
            }
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("user", user);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextHolder.getContext());
            SessionCredential.bind(session, user);

            audit(user.getId(), account, clientIp, userAgent, LoginLogRepository.STATUS_SUCCESS, null);

            if (Boolean.TRUE.equals(user.getPasswordResetRequired())) {
                LOG.info("AUDIT event=login_ok_password_change_required username={} ip={}",
                        auditName, clientIp);
            }
            return new Result(Status.OK, user, 0, "登录成功");
        } catch (IllegalStateException e) {
            loginAttemptService.recordFailure(attemptKey, clientIp, accountLevelLock);
            LOG.warn("AUDIT event=login_failure username={} ip={} reason={}",
                    auditName, clientIp, AuditSanitizer.forLog(e.getMessage()));
            audit(knownUserId, account, clientIp, userAgent, LoginLogRepository.STATUS_FAILURE,
                    banReason(e.getMessage()));
            return Result.of(Status.REJECTED, String.valueOf(e.getMessage()));
        }
    }

    /** 日志里没有命中任何一层锁时 scope 显示 none，避免打出 {@code scope=null}。 */
    private static String scopeName(LoginAttemptService.Layer layer) {
        return layer == null ? "none" : layer.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 审计写入；任何异常只记 warn，绝不影响登录主流程。
     *
     * <p>{@code account} 在这里统一脱敏：账号真实存在才原样落库（运维要按账号查），
     * 不存在则只留哈希前 8 位 —— 登录框里填的可能是密码，形态判断挡不住
     * {@code abc12345} 这种典型密码。</p>
     */
    private void audit(Integer userId,
                       String account,
                       String clientIp,
                       String userAgent,
                       int status,
                       String failureReason) {
        String storedAccount = AuditSanitizer.accountForStorage(account, userId != null);
        try {
            loginLogRepository.insert(userId, storedAccount, clientIp, userAgent, status, failureReason);
        } catch (RuntimeException e) {
            LOG.warn("AUDIT event=login_log_write_failed account={} ip={} status={} error={}",
                    storedAccount, clientIp, status, e.toString());
        }
    }

    private static long minutes(long seconds) {
        if (seconds <= 0) {
            return 1;
        }
        return Math.max(1L, (seconds + 59) / 60);
    }

    /** 封禁原因归一为"账户被封禁"，不把解封时间写进审计原因；同样过一遍清洗。 */
    private static String banReason(String message) {
        if (message == null) {
            return "登录被拒绝";
        }
        return AuditSanitizer.forLog(message.contains("封禁") ? "账户被封禁" : message);
    }
}
