package org.example.lifecomposer.Security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.1.4: open-redirect guard used by the login page's {@code ?redirect=} parameter. */
class SafeRedirectTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/front/profile",
            "/front/chat_test",
            "/front/chat_test?tab=history",
            "/front/profile#top",
            "/admin/usage",
    })
    @DisplayName("same-site paths are accepted")
    void acceptsSameSitePaths(String value) {
        assertTrue(SafeRedirect.isSafe(value), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example.com",
            "https://evil.example.com/login",
            "//evil.example.com",
            "/\\evil.example.com",
            "\\\\evil.example.com",
            "evil.example.com/front/profile",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "/%2f%2fevil.example.com",
            "/%5Cevil.example.com",
            "/front//chat_test",
    })
    @DisplayName("absolute URLs, protocol-relative URLs and encoded bypasses are rejected")
    void rejectsForeignTargets(String value) {
        assertFalse(SafeRedirect.isSafe(value), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/front/login", "/front/register", "/login", "/adminlogin", "/error/403"})
    @DisplayName("pages that would loop back to the login screen are rejected")
    void rejectsLoginPages(String value) {
        assertFalse(SafeRedirect.isSafe(value), value);
    }

    @Test
    @DisplayName("API paths are not a landing page")
    void rejectsApiPaths() {
        assertFalse(SafeRedirect.isSafe("/api/auth/me"));
        assertFalse(SafeRedirect.isSafe("/api/profiles/me"));
    }

    @Test
    @DisplayName("empty, null, control characters and over-long values are rejected")
    void rejectsMalformedValues() {
        assertFalse(SafeRedirect.isSafe(null));
        assertFalse(SafeRedirect.isSafe(""));
        assertFalse(SafeRedirect.isSafe("   "));
        assertFalse(SafeRedirect.isSafe("/front/profile\r\nSet-Cookie: x=1"));
        assertFalse(SafeRedirect.isSafe("/front/profile\u0000"));
        assertFalse(SafeRedirect.isSafe("/" + "a".repeat(600)));
    }

    @Test
    @DisplayName("resolve falls back for bad input and keeps good input")
    void resolveFallsBack() {
        assertEquals("/front/profile", SafeRedirect.resolve(null, "/front/profile"));
        assertEquals("/front/profile", SafeRedirect.resolve("//evil.example.com", "/front/profile"));
        assertEquals("/front/profile", SafeRedirect.resolve("/front/login", "/front/profile"));
        assertEquals("/front/chat_test", SafeRedirect.resolve("/front/chat_test", "/front/profile"));
        assertEquals("/front/chat_test", SafeRedirect.resolve("  /front/chat_test  ", "/front/profile"));
    }

    @Test
    @DisplayName("the default landing page is itself safe")
    void defaultIsSafe() {
        assertTrue(SafeRedirect.isSafe(SafeRedirect.DEFAULT_AFTER_LOGIN));
    }

    @Test
    @DisplayName("trailing slash and different case cannot smuggle the login page back in")
    void normalizedForbiddenPaths() {
        assertFalse(SafeRedirect.isSafe("/front/login/"));
        assertFalse(SafeRedirect.isSafe("/front/register/"));
        assertFalse(SafeRedirect.isSafe("/Front/Login"));
        assertFalse(SafeRedirect.isSafe("/ADMINLOGIN/"));
        assertFalse(SafeRedirect.isSafe("/login/"));
        assertFalse(SafeRedirect.isSafe("/error/403/"));
        // 合法页面的尾斜杠不受影响
        assertTrue(SafeRedirect.isSafe("/front/chat_test/"));
        assertTrue(SafeRedirect.isSafe("/front/profile/"));
    }

    @Test
    @DisplayName("dot segments that the browser would resolve are rejected")
    void dotSegmentsAreRejected() {
        assertFalse(SafeRedirect.isSafe("/front/login/.."));
        assertFalse(SafeRedirect.isSafe("/front/./login"));
        assertFalse(SafeRedirect.isSafe("/front/chat_test/../login"));
        assertFalse(SafeRedirect.isSafe("/front/%2e%2e/login"));
        assertFalse(SafeRedirect.isSafe("/front/profile/."));
        // 文件名里的点不受影响
        assertTrue(SafeRedirect.isSafe("/front/chat_test.js"));
    }

    @Test
    @DisplayName("static normalize() lower-cases and strips trailing slashes")
    void normalizeHelper() {
        assertEquals("/front/login", SafeRedirect.normalize("/Front/Login/"));
        assertEquals("/", SafeRedirect.normalize("/"));
        assertEquals("/front/chat_test", SafeRedirect.normalize("/front/chat_test///"));
    }
}
