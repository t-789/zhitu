package org.example.lifecomposer.Security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 审计与日志里跟"用户输入"打交道时的两个处理：<b>清洗</b>和<b>脱敏</b>。
 *
 * <p>为什么需要：</p>
 * <ul>
 *   <li><b>清洗</b>——落日志前把 CR / LF / NUL 等控制字符换成空格。原始输入里带
 *       {@code \r\n} 就能在日志里伪造一整行（例如伪造一条 {@code AUDIT event=login_ok}），
 *       这是最典型的日志注入；</li>
 *   <li><b>脱敏</b>——登录框和密码框挨着，用户把密码填进账号框并不罕见。账号写进
 *       {@code login_log} 是为了运维排查（谁在什么时候被锁），但如果存的是密码就成了
 *       明文凭据泄漏。所以只有"像账号"的值才原样落库，其余一律只存哈希前 8 位。</li>
 * </ul>
 *
 * <p>"像账号"的判定故意保守但简单：纯数字（学号）、含 {@code @}（邮箱）、或只由
 * {@code [A-Za-z0-9._@+-]} 组成且不超过 40 字符（用户名 / 学号 / 邮箱的常见形态）。
 * 超出这个形态的（带空格、带符号、超长）当作"不是账号"，只存哈希。这条规则挡不住
 * 形如 {@code abc12345} 的纯字母数字密码，所以账号框脱敏只是纵深防御的一层，
 * 真正的防线是"密码不进日志"这条开发约束。</p>
 */
public final class AuditSanitizer {

    /** 日志里的单字段上限，够看清是谁，又不至于把日志刷爆。 */
    private static final int LOG_MAX = 100;

    /** 落库的账号上限，与 {@code login_log.account} 的列宽一致。 */
    private static final int ACCOUNT_MAX = 128;

    private static final Pattern STUDENT_ID = Pattern.compile("\\d{4,20}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._@+-]{1,40}");

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
     * 清洗后用于落库的账号值：像账号就原样保留，不像就只留哈希。
     *
     * @return 清洗/脱敏后的值，永不为 {@code null}
     */
    public static String accountForStorage(String raw) {
        String cleaned = clean(raw, ACCOUNT_MAX);
        if (cleaned.isEmpty()) {
            return "";
        }
        return looksLikeAccount(cleaned) ? cleaned : mask(raw);
    }

    /** 脱敏形态：{@code <masked:1a2b3c4d>}，只留 SHA-256 前 8 位，不可逆。 */
    public static String mask(String raw) {
        String source = raw == null ? "" : raw;
        return "<masked:" + sha256Hex(source).substring(0, 8) + ">";
    }

    /** 判定一个（已清洗的）值是否"像账号"。 */
    static boolean looksLikeAccount(String value) {
        if (value.length() > 40 && !value.contains("@")) {
            return false;
        }
        return STUDENT_ID.matcher(value).matches()
                || EMAIL.matcher(value).matches()
                || PLAIN.matcher(value).matches();
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
