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

    /** Consecutive login failures before a temporary lock. */
    private int loginMaxFailures = 5;

    /** Temporary login lock duration. */
    private long loginLockMillis = 15 * 60_000L;

    /** When true, admin endpoints only accept private/loopback client addresses. */
    private boolean adminIntranetOnly = false;

    /** Chat usage day boundary. */
    private String chatUsageZone = "Asia/Shanghai";

    /** Daily usage rows older than this many days are removed on startup. */
    private int chatUsageRetentionDays = 30;
}
