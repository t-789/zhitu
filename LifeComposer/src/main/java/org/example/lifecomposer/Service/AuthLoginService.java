package org.example.lifecomposer.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.Security.SessionCredential;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Single login flow shared by {@code /api/auth/login} and the legacy
 * {@code /api/users/login}: lockout, ban/temp-password checks, session fixation
 * protection and credential stamping. Login-security owner: 王钰博.
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

    public AuthLoginService(UserService userService,
                            UserRepository userRepository,
                            UserDetailsService userDetailsService,
                            LoginAttemptService loginAttemptService) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
    }

    public Result login(String account, String password, HttpServletRequest httpRequest) {
        User existing = userRepository.findByAccount(account);
        // Lockout is shared by student id / username / email of the same account.
        String attemptKey = existing == null ? account.toLowerCase(Locale.ROOT) : existing.getUsername();
        String auditName = existing == null && account.contains("@") ? "<email>" : attemptKey;
        String clientIp = httpRequest == null ? "unknown" : httpRequest.getRemoteAddr();

        if (loginAttemptService.isLocked(attemptKey, clientIp)) {
            long retryAfter = loginAttemptService.retryAfterSeconds(attemptKey, clientIp);
            LOG.warn("AUDIT event=login_locked username={} ip={} retryAfterSeconds={}",
                    auditName, clientIp, retryAfter);
            return new Result(Status.LOCKED, null, retryAfter, "登录失败次数过多，请稍后再试");
        }
        try {
            User user = userService.login(account, password);
            if (user == null) {
                loginAttemptService.recordFailure(attemptKey, clientIp);
                LOG.warn("AUDIT event=login_failure username={} ip={}", auditName, clientIp);
                return Result.of(Status.BAD_CREDENTIALS, "登录失败，用户名或密码错误");
            }
            if (Boolean.TRUE.equals(user.getPasswordResetRequired())
                    && userService.isTemporaryPasswordExpired(user)) {
                loginAttemptService.recordFailure(attemptKey, clientIp);
                LOG.warn("AUDIT event=login_rejected_temp_password_expired username={} ip={}",
                        auditName, clientIp);
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

            if (Boolean.TRUE.equals(user.getPasswordResetRequired())) {
                LOG.info("AUDIT event=login_ok_password_change_required username={} ip={}",
                        auditName, clientIp);
            }
            return new Result(Status.OK, user, 0, "登录成功");
        } catch (IllegalStateException e) {
            loginAttemptService.recordFailure(attemptKey, clientIp);
            LOG.warn("AUDIT event=login_failure username={} ip={} reason={}",
                    auditName, clientIp, e.getMessage());
            return Result.of(Status.REJECTED, String.valueOf(e.getMessage()));
        }
    }
}
