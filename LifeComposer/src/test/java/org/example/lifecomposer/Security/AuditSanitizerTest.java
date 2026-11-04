package org.example.lifecomposer.Security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计清洗与脱敏：日志注入、账号框误填密码两种情况下的回归。
 *
 * <p>v0.1.9 起判定规则从"长得像不像账号"改成"账号是否真实存在"：注册规则允许
 * {@code abc12345} 这种密码，形态判断挡不住它。</p>
 */
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
    @DisplayName("账号存在时原样落库（运维要按账号查）")
    void existingAccountIsStoredAsTyped() {
        assertEquals("20260001", AuditSanitizer.accountForStorage("20260001", true));
        assertEquals("alice", AuditSanitizer.accountForStorage(" alice ", true));
        assertEquals("alice@bupt.cn", AuditSanitizer.accountForStorage("alice@bupt.cn", true));
        assertEquals("2026-0001_x", AuditSanitizer.accountForStorage("2026-0001_x", true));
    }

    @Test
    @DisplayName("账号不存在时一律脱敏——包括形如 abc12345 的典型密码")
    void unknownAccountIsAlwaysMasked() {
        String masked = AuditSanitizer.accountForStorage("abc12345", false);
        assertTrue(masked.startsWith("<masked:"), masked);
        assertFalse(masked.contains("abc12345"));

        // 形态上"很像账号"的值，只要账号不存在也必须打码。
        assertTrue(AuditSanitizer.accountForStorage("20260001", false).startsWith("<masked:"));
        assertTrue(AuditSanitizer.accountForStorage("alice@bupt.cn", false).startsWith("<masked:"));
        assertTrue(AuditSanitizer.accountForStorage("Zhitu@2026!", false).startsWith("<masked:"));
    }

    @Test
    @DisplayName("同一个输入在同一判定下得到同一个前缀（运维仍能关联）")
    void maskIsStableAndNonReversible() {
        String a = AuditSanitizer.mask("hunter2!");
        assertEquals(a, AuditSanitizer.mask("hunter2!"));
        assertTrue(a.matches("<masked:[0-9a-f]{8}>"), a);
        assertFalse(a.contains("hunter2"));
        assertNotEquals(a, AuditSanitizer.mask("hunter2?"));
    }

    @Test
    @DisplayName("空输入一律空串，不产生 mask")
    void emptyInput() {
        assertEquals("", AuditSanitizer.accountForStorage(null, false));
        assertEquals("", AuditSanitizer.accountForStorage("", true));
        assertEquals("", AuditSanitizer.accountForStorage("   ", false));
    }
}
