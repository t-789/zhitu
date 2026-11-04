package org.example.lifecomposer.Controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.dto.LoginRequest;
import org.example.lifecomposer.dto.RegisterRequest;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Entity.UserType;
import org.example.lifecomposer.Security.SessionCredential;
import org.example.lifecomposer.Service.AdminPasswordPolicy;
import org.example.lifecomposer.Service.AuthLoginService;
import org.example.lifecomposer.Service.RegistrationRateLimiter;
import org.example.lifecomposer.Service.UserService;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(UserController.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthLoginService authLoginService;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final AppSecurityProperties securityProperties;
    private final AdminPasswordPolicy adminPasswordPolicy;

    public UserController(UserService userService,
                          UserRepository userRepository,
                          AuthLoginService authLoginService,
                          RegistrationRateLimiter registrationRateLimiter,
                          AppSecurityProperties securityProperties,
                          AdminPasswordPolicy adminPasswordPolicy) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.authLoginService = authLoginService;
        this.registrationRateLimiter = registrationRateLimiter;
        this.securityProperties = securityProperties;
        this.adminPasswordPolicy = adminPasswordPolicy;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request,
                                      HttpServletRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        if (!registrationRateLimiter.tryAcquire(clientIp,
                securityProperties.getRegisterPerMinutePerIp())) {
            long retryAfter = registrationRateLimiter.retryAfterSeconds(clientIp);
            LOG.warn("AUDIT event=register_rate_limited ip={} retryAfterSeconds={}", clientIp, retryAfter);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of(
                            "error", "REGISTER_RATE_LIMIT",
                            "message", "注册请求过于频繁，请稍后再试",
                            "retryAfterSeconds", retryAfter));
        }
        boolean success = userService.register(request.getUsername(), request.getPassword(),
                request.getEmail(), request.getRealName());
        if (!success) {
            return ResponseEntity.badRequest().body("注册失败，用户名或邮箱可能已存在");
        }
        return ResponseEntity.ok("注册成功");
    }

    /** Legacy login; {@code /api/auth/login} is the contract endpoint. Same flow via {@link AuthLoginService}. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthLoginService.Result result = authLoginService.login(
                request.getUsername(), request.getPassword(), httpRequest);
        switch (result.status()) {
            case LOCKED:
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                        .body(Map.of(
                                "error", "LOGIN_LOCKED",
                                "message", result.message(),
                                "retryAfterSeconds", result.retryAfterSeconds()));
            case BAD_CREDENTIALS:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "BAD_CREDENTIALS", "message", result.message()));
            case TEMP_PASSWORD_EXPIRED:
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "TEMP_PASSWORD_EXPIRED", "message", result.message()));
            case REJECTED:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "LOGIN_REJECTED", "message", result.message()));
            default:
                User user = result.user();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", "登录成功");
                body.put("username", user.getUsername());
                body.put("type", user.getType());
                body.put("passwordChangeRequired", Boolean.TRUE.equals(user.getPasswordResetRequired()));
                body.put("tempPasswordExpiresAt", user.getTempPasswordExpiresAt());
                return ResponseEntity.ok(body);
        }
    }

    /**
     * Review follow-up (P1/P2): self-service password replacement. It is the only
     * endpoint reachable while a session is flagged
     * {@code password_reset_required}, and on success the session is re-stamped so
     * that the device performing the change stays logged in while every other
     * session of that account is invalidated by the credential-version bump.
     */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody(required = false) Map<String, String> payload,
                                            HttpServletRequest request,
                                            Authentication authentication) {
        HttpSession session = request.getSession(false);
        User current = currentUser(session, authentication);
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "UNAUTHORIZED", "message", "用户未登录"));
        }

        String currentPassword = payload == null ? null : payload.get("currentPassword");
        String newPassword = payload == null ? null : payload.get("newPassword");
        boolean wasForced = Boolean.TRUE.equals(current.getPasswordResetRequired());

        String error = userService.changeOwnPassword(current.getId(), currentPassword, newPassword,
                current.getType() != null && current.getType() == UserType.ADMIN);
        if (error != null) {
            LOG.warn("AUDIT event=password_change_rejected username={} reason={}",
                    current.getUsername(), error);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "PASSWORD_REJECTED", "message", error));
        }

        // Re-read so the session carries the new credential generation.
        User refreshed = userService.getById(current.getId());
        session.setAttribute("user", refreshed);
        SessionCredential.bind(session, refreshed);

        LOG.info("AUDIT event=password_changed username={} forced={}",
                refreshed.getUsername(), wasForced);
        return ResponseEntity.ok(Map.of("message", "密码已更新"));
    }

    /** Resolves the session user, falling back to the authenticated principal. */
    private User currentUser(HttpSession session, Authentication authentication) {
        if (session != null) {
            Object sessionUser = session.getAttribute("user");
            if (sessionUser instanceof User user && user.getId() != null) {
                return userService.getById(user.getId());
            }
        }
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return userService.getByUsername(authentication.getName());
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok("登出成功");
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("用户未登录");
        }

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("用户未登录");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("type", user.getType());
        payload.put("avatar", user.getAvatar());
        payload.put("isBanned", user.getBanned());
        payload.put("banEndTime", user.getBanEndTime());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllUsers(Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "权限不足"));
        }
        // FIX: filter out passwordHash to prevent credential leakage
        return ResponseEntity.ok(userService.getAllUsers().stream()
                .map(this::toSafeUserMap)
                .toList());
    }

    private Map<String, Object> toSafeUserMap(User u) {
        Map<String, Object> safe = new HashMap<>();
        safe.put("id", u.getId());
        safe.put("username", u.getUsername());
        safe.put("type", u.getType() != null ? u.getType() : 1);
        safe.put("avatar", u.getAvatar());
        safe.put("banned", u.getBanned() != null ? u.getBanned() : false);
        safe.put("banEndTime", u.getBanEndTime());
        safe.put("createdAt", u.getCreatedAt());
        safe.put("updatedAt", u.getUpdatedAt());
        return safe;
    }
    @PutMapping("{userId}/grant-admin")
    public ResponseEntity<?> grantAdmin(@PathVariable int userId, Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("权限不足");
        }
        boolean success = userService.grantAdminPermission(userId);
        return success ? ResponseEntity.ok("用户" + userId + "已被赋予管理员权限")
                : ResponseEntity.badRequest().body("用户" + userId + "不存在");
    }

    @PutMapping("/{userId}/revoke-admin")
    public ResponseEntity<?> revokeAdmin(@PathVariable int userId, Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("权限不足");
        }
        return removalResponse(userService.revokeAdminPermission(userId), "撤销管理员权限",
                "用户" + userId + "已被撤销管理员权限");
    }

    @PutMapping("/{userId}/ban")
    public ResponseEntity<?> banUser(@PathVariable int userId,
                                     @RequestBody(required = false) Map<String, String> payload,
                                     Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("权限不足");
        }

        // Review follow-up: a missing body is a client error (400), not a 500 that
        // would also be recorded as a system error in the feedback table.
        String banTime = payload == null ? null : payload.get("banTime");
        if (banTime == null || banTime.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_INPUT", "message", "封禁时间不能为空"));
        }

        return removalResponse(userService.banUser(userId, banTime), "封禁", "用户封禁操作完成");
    }

    /**
     * Maps the race-free removal outcome to an explicit status: 404 for a missing
     * user, 409 when the last loginable administrator is protected.
     */
    private ResponseEntity<?> removalResponse(UserService.AdminRemovalResult result,
                                              String action,
                                              String successMessage) {
        return switch (result) {
            case DONE -> ResponseEntity.ok(successMessage);
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOT_FOUND", "message", "用户不存在"));
            case LAST_ADMIN -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "LAST_ADMIN_PROTECTED",
                            "message", "系统必须保留至少一个可登录管理员，" + action + "操作已被拒绝"));
            case INVALID_INPUT -> ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_INPUT", "message", "封禁时间格式不正确"));
        };
    }

    @PutMapping("/{userId}/unban")
    public ResponseEntity<?> unbanUser(@PathVariable int userId, Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("权限不足");
        }
        boolean success = userService.unbanUser(userId);
        return success ? ResponseEntity.ok("用户解封操作完成") : ResponseEntity.badRequest().body("解封操作失败");
    }

    /**
     * Milestone 6 + review follow-up: the old behaviour (reset to the fixed
     * password {@code 000000} and echo it) is gone. The administrator supplies a
     * <em>real</em> temporary password:
     * <ul>
     *   <li>it must satisfy the strength policy (administrator accounts use the
     *       12-character rule, user accounts the shorter user rule);</li>
     *   <li>it is stored with {@code password_reset_required = 1} and an expiry
     *       ({@code lifecomposer.admin.temp-password-ttl-minutes}), so the account
     *       must replace it before doing anything else and cannot log in after the
     *       deadline;</li>
     *   <li>the credential version is bumped, which immediately invalidates every
     *       session opened with the previous password;</li>
     *   <li>the response and the audit line never contain the password.</li>
     * </ul>
     */
    @PostMapping("/admin/reset-password/{userId}")
    public ResponseEntity<?> adminResetPassword(@PathVariable int userId,
                                                @RequestBody(required = false) Map<String, String> payload,
                                                Authentication authentication) {
        if (isNotAdmin(authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "权限不足"));
        }

        User target = userService.getById(userId);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        String newPassword = payload == null ? null : payload.get("newPassword");
        boolean targetIsAdmin = target.getType() != null && target.getType() == UserType.ADMIN;
        Optional<String> violation = targetIsAdmin
                ? adminPasswordPolicy.violation(newPassword)
                : adminPasswordPolicy.violationForUser(newPassword);
        if (violation.isPresent()) {
            LOG.warn("AUDIT event=admin_reset_password_rejected admin={} targetUserId={} reason=weak_password",
                    authentication.getName(), userId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "WEAK_PASSWORD", "message", violation.get()));
        }

        Timestamp expiresAt = userService.setTemporaryPassword(userId, newPassword);
        if (expiresAt == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "RESET_FAILED", "message", "密码重置失败"));
        }

        LOG.info("AUDIT event=admin_reset_password admin={} targetUserId={} targetUsername={} "
                        + "tempPassword=true expiresAt={} result=success",
                authentication.getName(), userId, target.getUsername(), expiresAt.toInstant());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "临时密码已下发，用户首次登录后必须立即修改");
        body.put("passwordChangeRequired", true);
        body.put("tempPasswordExpiresAt", expiresAt);
        return ResponseEntity.ok(body);
    }

    private boolean isNotAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return true;
        }
        User user = userRepository.findByUsername(authentication.getName());
        return user == null || user.getType() == null || user.getType() != UserType.ADMIN;
    }
}
