package org.example.lifecomposer.Security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审计清洗与脱敏：日志注入、账号框误填密码两种情况的回归。 */
class AuditSanitizerTest {

    @Test
    @DisplayName("forLog strips CR/LF so an attacker cannot forge a log line")
    void forLogStripsControlCharacters() {
        String forged = "20260001\r\nAUDIT event=login_ok username=admin ip=127.0.0.1";
        String cleaned = AuditSanitizer.forLog(forged);
        assertFalse(cleaned.contains("\r"));
        assertFalse(cleaned.contains("\n"));
        assertTrue(cleaned.startsWith("20260001 AUDIT event=login_ok"), cleaned);
    }

    @Test
    @DisplayName("forLog collapses runs of whitespace and truncates")
    void forLogCollapsesAndTruncates() {
        assertEquals("a b", AuditSanitizer.forLog("a   \t  b"));
        assertEquals("", AuditSanitizer.forLog(null));
        assertEquals("", AuditSanitizer.forLog("   "));
        assertEquals(100, AuditSanitizer.forLog("x".repeat(500)).length());
    }

    @Test
    @DisplayName("accountForStorage keeps values that look like an account")
    void keepsPlausibleAccounts() {
        assertEquals("20260001", AuditSanitizer.accountForStorage("20260001"));
        assertEquals("alice", AuditSanitizer.accountForStorage(" alice "));
        assertEquals("alice@bupt.cn", AuditSanitizer.accountForStorage("alice@bupt.cn"));
        assertEquals("2026-0001_x", AuditSanitizer.accountForStorage("2026-0001_x"));
    }

    @Test
    @DisplayName("accountForStorage masks values that cannot be an account")
    void masksImplausibleValues() {
        // 典型"把密码填进账号框"：带符号 / 带空格 / 超长
        assertTrue(AuditSanitizer.accountForStorage("Zhitu@2026!").startsWith("<masked:"));
        assertTrue(AuditSanitizer.accountForStorage("my secret pass phrase").startsWith("<masked:"));
        assertTrue(AuditSanitizer.accountForStorage("p".repeat(64)).startsWith("<masked:"));
    }

    @Test
    @DisplayName("mask is stable, non-reversible-looking and short")
    void maskShape() {
        String a = AuditSanitizer.mask("hunter2!");
        assertEquals(a, AuditSanitizer.mask("hunter2!"));
        assertTrue(a.matches("<masked:[0-9a-f]{8}>"), a);
        assertFalse(a.contains("hunter2"));
    }

    @Test
    @DisplayName("empty input never produces a mask")
    void emptyInput() {
        assertEquals("", AuditSanitizer.accountForStorage(null));
        assertEquals("", AuditSanitizer.accountForStorage(""));
        assertEquals("", AuditSanitizer.accountForStorage("   "));
    }
}
