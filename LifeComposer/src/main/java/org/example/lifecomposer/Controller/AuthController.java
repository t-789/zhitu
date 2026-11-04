package org.example.lifecomposer.Controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Security.XsrfHeaderAliasFilter;
import org.example.lifecomposer.Service.AuthLoginService;
import org.example.lifecomposer.Service.RegistrationRateLimiter;
import org.example.lifecomposer.Service.UserService;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.example.lifecomposer.dto.ApiEnvelope;
import org.example.lifecomposer.dto.AuthRegisterRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Account-center contract (login-security module, owner: 王钰博).
 * All responses use the {@code {code,message,data}} envelope; HTTP status mirrors the
 * code, except business code 1001 which is sent as HTTP 401.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(AuthController.class);

    static final int CODE_BAD_CREDENTIALS = 1001;
    private static final Pattern STUDENT_ID = Pattern.compile("^[A-Za-z0-9]{4,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserService userService;
    private final AuthLoginService authLoginService;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final AppSecurityProperties securityProperties;

    public AuthController(UserService userService,
                          AuthLoginService authLoginService,
                          RegistrationRateLimiter registrationRateLimiter,
                          AppSecurityProperties securityProperties) {
        this.userService = userService;
        this.authLoginService = authLoginService;
        this.registrationRateLimiter = registrationRateLimiter;
        this.securityProperties = securityProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> register(
            @RequestBody(required = false) AuthRegisterRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            return fail(HttpStatus.BAD_REQUEST, 400, "请求体不能为空");
        }
        String error = validate(request);
        if (error != null) {
            return fail(HttpStatus.BAD_REQUEST, 400, error);
        }
        String clientIp = httpRequest.getRemoteAddr();
        if (!registrationRateLimiter.tryAcquire(clientIp, securityProperties.getRegisterPerMinutePerIp())) {
            long retryAfter = Math.max(1L, registrationRateLimiter.retryAfterSeconds(clientIp));
            LOG.warn("AUDIT event=register_rate_limited ip={} retryAfterSeconds={}", clientIp, retryAfter);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(ApiEnvelope.of(429, "注册请求过于频繁，请稍后再试"));
        }
        String studentId = request.getStudentId().trim();
        boolean created = userService.register(studentId, studentId, request.getPassword(),
                request.getEmail().trim(), request.getRealName().trim());
        if (!created) {
            return fail(HttpStatus.CONFLICT, 409, "学号或邮箱已被注册");
        }
        User user = userService.getByUsername(studentId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user == null ? null : user.getId());
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> login(
            @RequestBody(required = false) Map<String, String> body, HttpServletRequest httpRequest) {
        String account = body == null ? null : body.get("account");
        String password = body == null ? null : body.get("password");
        if (account == null || account.isBlank() || account.length() > 100
                || password == null || password.isEmpty() || password.length() > 50) {
            return fail(HttpStatus.BAD_REQUEST, 400, "账号和密码不能为空");
        }
        AuthLoginService.Result result = authLoginService.login(account.trim(), password, httpRequest);
        switch (result.status()) {
            case LOCKED:
                return ResponseEntity.status(423)
                        .header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                        .body(ApiEnvelope.of(423, result.message()));
            case BAD_CREDENTIALS:
                // Unknown account and wrong password are indistinguishable (anti-enumeration).
                return fail(HttpStatus.UNAUTHORIZED, CODE_BAD_CREDENTIALS, "账号或密码错误");
            case TEMP_PASSWORD_EXPIRED:
            case REJECTED:
                return fail(HttpStatus.FORBIDDEN, 403, result.message());
            default:
                return ResponseEntity.ok(ApiEnvelope.ok(userData(result.user(), true)));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiEnvelope<Void>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || sessionUser(session) == null) {
            return failVoid();
        }
        SecurityContextHolder.clearContext();
        session.invalidate();
        return ResponseEntity.ok(ApiEnvelope.ok(null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        User user = session == null ? null : sessionUser(session);
        if (user == null) {
            return fail(HttpStatus.UNAUTHORIZED, 401, "未认证或登录已过期");
        }
        return ResponseEntity.ok(ApiEnvelope.ok(userData(user, false)));
    }

    @GetMapping("/csrf")
    public ApiEnvelope<Map<String, Object>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            token = (CsrfToken) request.getAttribute("_csrf");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("headerName", XsrfHeaderAliasFilter.ALIAS_HEADER);
        data.put("parameterName", token != null ? token.getParameterName() : "_csrf");
        data.put("token", token != null ? token.getToken() : null);
        return ApiEnvelope.ok(data);
    }

    private User sessionUser(HttpSession session) {
        Object value = session.getAttribute("user");
        if (value instanceof User user && user.getId() != null) {
            return userService.getById(user.getId());
        }
        return null;
    }

    /** Identity data only: college/major/grade belong to the profile module. */
    private Map<String, Object> userData(User user, boolean forLogin) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("studentId", user.getStudentId());
        data.put("email", user.getEmail());
        data.put("type", user.getType());
        data.put("status", isBanned(user) ? 0 : 1);
        data.put("passwordChangeRequired", Boolean.TRUE.equals(user.getPasswordResetRequired()));
        if (forLogin) {
            data.put("tempPasswordExpiresAt", user.getTempPasswordExpiresAt());
        }
        return data;
    }

    private boolean isBanned(User user) {
        if (!Boolean.TRUE.equals(user.getBanned())) {
            return false;
        }
        Timestamp end = user.getBanEndTime();
        return end == null || end.getTime() > System.currentTimeMillis();
    }

    private String validate(AuthRegisterRequest r) {
        if (r.getStudentId() == null || !STUDENT_ID.matcher(r.getStudentId().trim()).matches()) {
            return "学号须为4-20位字母或数字";
        }
        if (r.getRealName() == null || r.getRealName().trim().length() < 2
                || r.getRealName().trim().length() > 50) {
            return "姓名长度须为2-50个字符";
        }
        if (r.getEmail() == null || r.getEmail().trim().length() > 100
                || !EMAIL.matcher(r.getEmail().trim()).matches()) {
            return "邮箱格式不正确";
        }
        String pwd = r.getPassword();
        if (pwd == null || pwd.length() < 8 || pwd.length() > 50
                || pwd.chars().noneMatch(Character::isLetter)
                || pwd.chars().noneMatch(Character::isDigit)) {
            return "密码至少8位，且必须同时包含字母和数字";
        }
        if (!pwd.equals(r.getConfirmPassword())) {
            return "两次输入的密码不一致";
        }
        return null;
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> fail(HttpStatus http, int code, String message) {
        return ResponseEntity.status(http).body(ApiEnvelope.of(code, message));
    }

    private static ResponseEntity<ApiEnvelope<Void>> failVoid() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiEnvelope.of(401, "未认证或登录已过期"));
    }
}
