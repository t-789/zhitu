package org.example.lifecomposer.Security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 审计与日志里跟"用户输入"打交道时的两个处理：<b>清洗</b>和<b>脱敏</b>。
 *
 * <p>为什么需要：</p>
 * <ul>
 *   <li><b>清洗</b>——落日志前把 CR / LF / NUL 等控制字符换成空格。原始输入里带
 *       {@code \\r\\n} 就能在日志里伪造一整行（例如伪造一条 {@code AUDIT event=login_ok}），
 *       这是最典型的日志注入；</li>
 *   <li><b>脱敏</b>——登录框和密码框挨着，用户把密码填进账号框并不罕见。账号写进
 *       {@code login_log} 是为了运维排查（谁在什么时候被锁），但如果存的是密码就成了
 *       明文凭据泄漏。</li>
 * </ul>
 *
 * <p><b>判定规则（v0.1.9 改）</b>：不再靠"长得像不像账号"来猜，而是看
 * <b>这次输入是否真的对应一个存在的账号</b>：</p>
 * <ul>
 *   <li>账号存在 → 原样落库（运维需要按账号检索）；</li>
 *   <li>账号不存在 → 一律只存 {@code <masked:哈希前8位>}。</li>
 * </ul>
 *
 * <p>改的原因：注册规则要求密码 8–50 位且含字母和数字，于是典型密码
 * {@code abc12345} 与"纯字母数字用户名"在形态上完全无法区分，任何基于形态的
 * 白名单都会把密码原样存下来。"账号存在与否"是可以确定的知识，用它判定既简单又
 * 没有漏网的 case。代价是运维看不到陌生人填错的那串原文，但哈希前缀仍能把这几次
 * 尝试关联起来。</p>
 */
public final class AuditSanitizer {

    /** 日志里的单字段上限，够看清是谁，又不至于把日志刷爆。 */
    private static final int LOG_MAX = 100;

    /** 落库的账号上限，与 {@code login_log.account} 的列宽一致。 */
    private static final int ACCOUNT_MAX = 128;

    private AuditSanitizer() {
    }

    /**
     * 清洗后用于日志输出：控制字符换成空格，连续空白折叠，首尾去白，超长截断。
     * {@code null} 返回空串（日志里写 {@code null} 与"空"没区别，空串更短）。
     */
    public static String forLog(String value) {
        return clean(value, LOG_MAX);
    }

    /**
     * 清洗后用于落库的账号值：账号存在才原样保留，不存在一律只留哈希。
     *
     * @param raw           用户当次输入的账号框内容（可能是密码）
     * @param accountExists 这次输入是否解析到了一个真实存在的账号
     * @return 清洗/脱敏后的值，永不为 {@code null}
     */
    public static String accountForStorage(String raw, boolean accountExists) {
        String cleaned = clean(raw, ACCOUNT_MAX);
        if (cleaned.isEmpty()) {
            return "";
        }
        if (!accountExists) {
            return mask(raw);
        }
        return cleaned;
    }

    /** 脱敏形态：{@code <masked:1a2b3c4d>}，只留 SHA-256 前 8 位，不可逆。 */
    public static String mask(String raw) {
        String source = raw == null ? "" : raw;
        return "<masked:" + sha256Hex(source).substring(0, 8) + ">";
    }

    private static String clean(String value, int max) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), max));
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length() && sb.length() < max; i++) {
            char c = value.charAt(i);
            boolean control = c < 0x20 || c == 0x7f;
            if (control || c == ' ') {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            sb.append(c);
            lastWasSpace = false;
        }
        return sb.toString().trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；退化成固定串好过抛异常打断登录。
            return "00000000";
        }
    }
}
