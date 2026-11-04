package org.example.lifecomposer.Security;

import java.util.Locale;

/**
 * 开放重定向（open redirect）防护：登录后只允许跳回站内相对路径。
 *
 * <p>登录页支持 {@code /front/login?redirect=/front/chat_test} 这种"登录后回到原页面"的用法。
 * 如果直接把参数塞给 {@code window.location.assign()}，攻击者可以构造
 * {@code ?redirect=//evil.com} 把用户在登录成功后送到钓鱼站（信任度最高的那一刻）。</p>
 *
 * <p>规则（宁可多拒，错了只是回默认页）：</p>
 * <ul>
 *   <li>必须以单个 {@code /} 开头：挡掉绝对 URL、协议相对 URL、相对路径；</li>
 *   <li>不允许反斜杠（浏览器会把 {@code \} 当 {@code /}）；</li>
 *   <li>路径部分不允许 {@code //} 与 {@code %2f}/{@code %5c}/{@code %2e}（编码后的绕过）；</li>
 *   <li>不允许控制字符（CR/LF 头注入）；</li>
 *   <li>路径按"大小写不敏感 + 去尾斜杠"归一化后判黑名单，{@code /Front/Login/} 这种换个写法绕不过去；</li>
 *   <li>不放行登录页、注册页、管理登录页（跳回去等于死循环）与 {@code /api/} 接口。</li>
 * </ul>
 *
 * <p>本类只做判断，不发跳转；控制器把结果放进模型，页面只读这个已经校验过的值。</p>
 */
public final class SafeRedirect {

    /** 没有合法 redirect 参数时的落地页（与 v0.1.3 现有行为一致）。 */
    public static final String DEFAULT_AFTER_LOGIN = "/front/profile";

    private SafeRedirect() {
    }

    /** 合法就返回 {@code raw} 本身（去掉首尾空白），否则返回 {@code fallback}。 */
    public static String resolve(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        return isSafe(value) ? value : fallback;
    }

    public static boolean isSafe(String raw) {
        if (raw == null) {
            return false;
        }
        // 控制字符在 trim 之前就拒掉：String.trim() 会把 \u0000 这类字符当空白删掉，
        // 先 trim 再检查等于放行了 "/front/profile\0" 这种输入。
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 512) {
            return false;
        }
        // 只接受站内绝对路径："/x" 合法，"/" 之后紧跟 "/" 或 "\" 都不是站内路径。
        if (value.charAt(0) != '/' || value.length() < 2) {
            return false;
        }
        if (value.startsWith("//") || value.charAt(1) == '\\') {
            return false;
        }
        if (value.indexOf('\\') >= 0) {
            return false;
        }

        String path = normalize(pathOf(value));
        if (path.contains("//") || path.contains("%2f") || path.contains("%5c") || path.contains("%2e")
                || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")) {
            return false;
        }
        return !FORBIDDEN_PATHS.contains(path) && !path.startsWith("/api/");
    }

    /**
     * 归一化路径，让"换个写法"绕不过黑名单：
     * <ul>
     *   <li>大小写：{@code /Front/Login} 与 {@code /front/login} 是同一个页面；</li>
     *   <li>尾斜杠：{@code /front/login/}、{@code /front/login///} 同样指回登录页，
     *       Spring 与浏览器都按同一路由处理，只比字符串就会漏；</li>
     *   <li>末尾的点段由调用方单独拒绝（见 {@code isSafe} 里的 {@code /./}、{@code /../}）。</li>
     * </ul>
     */
    static String normalize(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 去掉 query 与 fragment，只留路径部分。 */
    private static String pathOf(String value) {
        int cut = value.length();
        int hash = value.indexOf('#');
        if (hash >= 0) {
            cut = hash;
        }
        int query = value.indexOf('?');
        if (query >= 0 && query < cut) {
            cut = query;
        }
        return value.substring(0, cut);
    }

    private static final java.util.Set<String> FORBIDDEN_PATHS = java.util.Set.of(
            "/", "/login", "/adminlogin", "/front/login", "/front/register", "/error/403");
}
