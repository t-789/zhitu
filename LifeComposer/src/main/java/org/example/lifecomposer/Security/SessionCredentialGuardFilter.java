package org.example.lifecomposer.Security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Review follow-up (P1/P2): makes administrator-issued temporary passwords real.
 *
 * <p>Two checks run for every request that carries an authenticated session:</p>
 * <ol>
 *   <li><b>Credential generation</b> — the session remembers the
 *       {@code credential_version} it logged in with. When the stored password
 *       was changed elsewhere (administrator reset, forced change from another
 *       device, legacy rotation) the version no longer matches, so the session is
 *       invalidated immediately: this is the "revoke existing sessions after a
 *       password reset" requirement, without needing a session registry.</li>
 *   <li><b>Forced change</b> — while a temporary password is in force the session
 *       may only reach the change-password endpoint, the current-user lookup,
 *       logout, the CSRF helper and the change-password page. Everything else is
 *       refused, and an expired temporary password kills the session.</li>
 * </ol>
 *
 * <p>Static assets are skipped, and the check only runs when the session actually
 * carries a credential stamp, so anonymous traffic is unaffected.</p>
 *
 * <p>Registered inside the Spring Security chain (see
 * {@code WebSecurityConfig#securityFilterChain}) rather than as a standalone
 * servlet filter: that keeps a single execution per request and makes the guard
 * active in MockMvc tests as well as in the real HTTP stack.</p>
 */
public class SessionCredentialGuardFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WHILE_RESET_REQUIRED = Set.of(
            "/api/users/password",
            "/api/users/current",
            "/api/users/logout",
            "/api/users/login",
            "/api/csrf",
            "/api/auth/me",
            "/api/auth/logout",
            "/api/auth/login",
            "/api/auth/csrf",
            "/front/change-password");

    private static final List<String> STATIC_SUFFIXES = List.of(
            ".js", ".css", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".map");

    private final UserRepository userRepository;

    public SessionCredentialGuardFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || isStaticAsset(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        Integer sessionVersion = SessionCredential.version(session);
        Object sessionUser = session.getAttribute("user");
        if (sessionVersion == null || !(sessionUser instanceof User user) || user.getId() == null) {
            // Not a session created by this application's login flow.
            filterChain.doFilter(request, response);
            return;
        }

        Integer currentVersion = userRepository.findCredentialVersion(user.getId());
        if (currentVersion == null || !currentVersion.equals(sessionVersion)) {
            rejectSession(request, response, session,
                    "SESSION_EXPIRED", "密码已变更，请重新登录", "/front/login?expired");
            return;
        }

        if (SessionCredential.resetRequired(session)) {
            Long expiresAt = SessionCredential.tempExpiresAtMillis(session);
            if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                rejectSession(request, response, session,
                        "TEMP_PASSWORD_EXPIRED", "临时密码已过期，请联系管理员重新下发", "/front/login?expired");
                return;
            }
            if (!allowedWhileResetRequired(request)) {
                rejectPasswordChangeRequired(request, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean allowedWhileResetRequired(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (ALLOWED_WHILE_RESET_REQUIRED.contains(uri)) {
            return true;
        }
        return uri.startsWith("/error");
    }

    private void rejectSession(HttpServletRequest request, HttpServletResponse response, HttpSession session,
                               String code, String message, String redirect) throws IOException {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Already invalidated by the container.
        }
        SecurityContextHolder.clearContext();
        deny(request, response, code, message, redirect);
    }

    private void rejectPasswordChangeRequired(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        deny(request, response, "PASSWORD_CHANGE_REQUIRED",
                "必须先修改临时密码才能继续使用", "/front/change-password");
    }

    private void deny(HttpServletRequest request, HttpServletResponse response,
                      String code, String message, String redirect) throws IOException {
        if (isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
            return;
        }
        response.sendRedirect(redirect);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/");
    }

    private boolean isStaticAsset(String uri) {
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase();
        return STATIC_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
}
