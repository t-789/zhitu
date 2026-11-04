package org.example.lifecomposer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Milestone 5 security / AI usage control configuration. */
@Configuration
@ConfigurationProperties(prefix = "app.security")
@Getter
@Setter
public class AppSecurityProperties {

    /**
     * Explicit CORS origins; never use "*" with credentials. The last entry is a
     * wildcard subdomain pattern covering Cloudflare Quick Tunnels
     * (https://&lt;random&gt;.trycloudflare.com) used while debugging.
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://*.trycloudflare.com"
    ));

    /** Mark XSRF-TOKEN cookie Secure when the deployment is HTTPS-only. */
    private boolean csrfCookieSecure = false;

    /** Chat minute window limit per authenticated user. */
    private int chatPerMinute = 5;

    /** Chat daily limit per authenticated user (Asia/Shanghai natural day). */
    private int chatPerDay = 100;

    /** Maximum generated tokens for /api/chat/*; larger client values are truncated. */
    private int chatMaxOutputTokens = 2048;

    /**
     * v0.1.2: how many recent user/assistant-text messages are sent to the LLM.
     * Tool-call/tool-result rows inside that window are included but not counted.
     * The full history stays in chat_messages; values below 1 are treated as 1.
     */
    private int chatContextMessages = 15;

    /** v0.1.2: maximum concurrently running SSE chat streams across all users. */
    private int chatStreamMaxConcurrent = 32;

    /** SSE emitter timeout. */
    private long sseTimeoutMillis = 300_000L;

    /** Registration attempts per client IP per minute. */
    private int registerPerMinutePerIp = 10;

    /**
     * 第一层（账号 + IP）连续失败阈值。校园网出口 IP 相同会互相误伤，
     * 生产环境建议放宽到 10（见对接文档 C 节）。
     */
    private int loginMaxFailures = 5;

    /** 第一层锁定持续时间。 */
    private long loginLockMillis = 15 * 60_000L;

    /** 第二层（账号总量，不分 IP）的统计窗口，单位分钟。 */
    private int loginTotalWindowMinutes = 60;

    /** 第二层窗口内失败次数阈值；分布式换 IP 猜密码会撞上这一层。 */
    private int loginTotalFailuresPerHour = 30;

    /** 第二层锁定持续时间。 */
    private long loginTotalLockMillis = 30 * 60_000L;

    /** 管理员（type=2）是否也受第二层约束；默认 false，只受第一层 IP 维度限制。 */
    private boolean loginAdminTotalLockEnabled = false;

    /** login_attempt 计数行的保留天数（长期无更新则清理）。 */
    private int loginAttemptRetentionDays = 30;

    /** login_log 审计行的保留天数。 */
    private int loginLogRetentionDays = 90;

    /** 登录相关清理任务的 cron（默认每小时整点）。 */
    private String loginCleanupCron = "0 0 * * * *";

    /** When true, admin endpoints only accept private/loopback client addresses. */
    private boolean adminIntranetOnly = false;

    /** Chat usage day boundary. */
    private String chatUsageZone = "Asia/Shanghai";

    /** Daily usage rows older than this many days are removed on startup. */
    private int chatUsageRetentionDays = 30;
}
