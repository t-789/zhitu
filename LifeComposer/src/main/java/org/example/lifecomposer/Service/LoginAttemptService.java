package org.example.lifecomposer.Service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.Repository.LoginAttemptRepository.Attempt;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 登录失败锁定，v0.1.6 起分两层（审核反馈 A 节），v0.1.9 起补三处（Claude 复审）。
 *
 * <p><b>第一层 {@code pair}</b>：同一 IP 对同一账号在窗口内连错 {@code login-max-failures}
 * 次（默认 5），锁这一对组合 {@code login-lock-millis}（默认 15 分钟）。计数带窗口，
 * 窗口内没有新失败就从头算，避免"几周零星错几次就被锁"。</p>
 *
 * <p><b>第二层 {@code account}</b>：同一账号 {@code login-total-window-minutes}（默认 60）
 * 分钟内累计失败 {@code login-total-failures-per-hour}（默认 30）次，不分 IP，
 * 锁 {@code login-total-lock-millis}（默认 30 分钟）。</p>
 *
 * <p><b>可信 IP 豁免（v0.1.9）</b>：第二层即使锁住，也不拒绝"该账号最近
 * {@code login-trusted-ip-days} 天内成功登录过的 IP"。原因：第一层把攻击者当前 IP
 * 锁住后，后续失败在锁定检查处就被拦下、不再计入第二层，所以攻击者用两个 IP 就能
 * 把受害者的账号推进第二层 —— 锁是账号级的，受害者自己也被挡住。豁免之后，受害者
 * 从常用网络照常登录，陌生 IP 仍被第二层限制。</p>
 *
 * <p><b>管理员</b>：{@code users.type=2} 的账号<b>仍然计入</b>账号级总量，但超阈值时
 * 只打告警日志、不上锁（{@code login-admin-total-lock-enabled=false}）。这样既不会
 * 被外网锁死管理员，也不会因为"管理员的第 30 次失败不返回 423"而被拿去判定账号身份。</p>
 *
 * <p><b>IPv6</b>：一个 /64 前缀通常就是一个用户网络，换地址即换 key 会让计数失去意义，
 * 所以 {@code pair} 的 IP 维度归一到 /64。</p>
 *
 * <p>调用方传入的 {@code account} 已规范化（存在时用 {@code users.username}，
 * 不存在时用去掉首尾空白的小写输入），所以同一人的学号 / 用户名 / 邮箱落到同一条记录。</p>
 *
 * <p>配置里把 {@code login-lock-millis} 或 {@code login-total-lock-millis} 设成 0
 * 等于<b>关掉那一层的锁定</b>（只计数不锁），开发与测试环境用得上。</p>
 */
@Component
public class LoginAttemptService {

    private static final Logger LOG = LogManager.getLogger(LoginAttemptService.class);

    /** 触发的层次，写审计与日志用。 */
    public enum Layer { PAIR, ACCOUNT }

    public record LockStatus(boolean locked, long retryAfterSeconds, Layer layer) {

        static LockStatus unlocked() {
            return new LockStatus(false, 0L, null);
        }

        static LockStatus locked(long retryAfterSeconds, Layer layer) {
            return new LockStatus(true, Math.max(1L, retryAfterSeconds), layer);
        }
    }

    /** 一次失败记录的结果：累计次数 + 是否因此触发锁定。 */
    public record FailureOutcome(int failures, boolean locked, long retryAfterSeconds, Layer layer) {
    }

    private final AppSecurityProperties properties;
    private final LoginAttemptRepository repository;

    public LoginAttemptService(AppSecurityProperties properties, LoginAttemptRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    /**
     * 查当前是否被锁。过期锁顺手清掉，让计数从头开始。
     *
     * @param accountLevelLock 是否适用第二层（管理员传 false：只计数不锁）
     * @param trustedIps       该账号近期的可信 IP（已归一），可为空集
     */
    public LockStatus check(String account, String clientIp, boolean accountLevelLock, Set<String> trustedIps) {
        Instant now = Instant.now();
        LockStatus pair = checkScope(LoginAttemptRepository.SCOPE_PAIR, pairKey(account, clientIp), now, Layer.PAIR);
        if (pair.locked()) {
            return pair;
        }
        if (!accountLevelLock) {
            return LockStatus.unlocked();
        }
        LockStatus accountLock = checkScope(
                LoginAttemptRepository.SCOPE_ACCOUNT, accountKey(account), now, Layer.ACCOUNT);
        if (accountLock.locked() && trustedIps != null && trustedIps.contains(normalizeIp(clientIp))) {
            LOG.info("AUDIT event=account_lock_bypassed reason=trusted_ip account={} ip={}",
                    accountKey(account), normalizeIp(clientIp));
            return LockStatus.unlocked();
        }
        return accountLock;
    }

    /**
     * 无"可信 IP"线索时的简化入口（等价于传空集合）。
     */
    public LockStatus check(String account, String clientIp, boolean accountLevelLock) {
        return check(account, clientIp, accountLevelLock, Set.of());
    }

    /**
     * 记一次失败，返回累计次数与是否触发锁定。
     *
     * <p>第二层<b>始终计数</b>（管理员也是），只有是否上锁受 {@code accountLevelLock} 控制。</p>
     *
     * @param accountLevelLock 是否允许第二层上锁（管理员传 false）
     */
    public FailureOutcome recordFailure(String account, String clientIp, boolean accountLevelLock) {
        String pairKey = pairKey(account, clientIp);
        int pairFailures = repository.incrementConsecutive(
                LoginAttemptRepository.SCOPE_PAIR, pairKey, properties.getLoginPairWindowMinutes());
        int failures = pairFailures;
        boolean locked = false;
        long retryAfter = 0L;
        Layer layer = null;

        if (pairFailures >= maxFailures() && properties.getLoginLockMillis() > 0) {
            repository.lock(LoginAttemptRepository.SCOPE_PAIR, pairKey, properties.getLoginLockMillis());
            locked = true;
            retryAfter = seconds(properties.getLoginLockMillis());
            layer = Layer.PAIR;
        }

        String accountKey = accountKey(account);
        int total = repository.incrementWithinWindow(
                LoginAttemptRepository.SCOPE_ACCOUNT, accountKey, properties.getLoginTotalWindowMinutes());
        failures = Math.max(failures, total);
        if (total >= properties.getLoginTotalFailuresPerHour()) {
            if (accountLevelLock && properties.getLoginTotalLockMillis() > 0) {
                repository.lock(LoginAttemptRepository.SCOPE_ACCOUNT, accountKey, properties.getLoginTotalLockMillis());
                locked = true;
                retryAfter = Math.max(retryAfter, seconds(properties.getLoginTotalLockMillis()));
                layer = Layer.ACCOUNT;
            } else if (!accountLevelLock) {
                // 管理员：只告警，不上锁；也不返回 423，避免被用来判定账号身份。
                LOG.warn("AUDIT event=account_lock_suppressed reason=admin account={} failures={} threshold={}",
                        accountKey, total, totalFailuresPerHour());
            }
        }

        return new FailureOutcome(failures, locked, retryAfter, layer);
    }

    /** 登录成功：两层计数一起清掉。 */
    public void recordSuccess(String account, String clientIp) {
        repository.delete(LoginAttemptRepository.SCOPE_PAIR, pairKey(account, clientIp));
        repository.delete(LoginAttemptRepository.SCOPE_ACCOUNT, accountKey(account));
    }

    /** 第一层的失败次数阈值。 */
    public int maxFailures() {
        return Math.max(1, properties.getLoginMaxFailures());
    }

    /** 第二层的失败次数阈值。 */
    public int totalFailuresPerHour() {
        return Math.max(1, properties.getLoginTotalFailuresPerHour());
    }

    /** 仅供测试与运维清理使用。 */
    public void reset() {
        repository.clearAll();
    }

    /**
     * IP 归一：IPv6 归一到 /64（同一用户网络换地址不算换 key），IPv4 与主机名原样。
     * 只处理 IP 字面量，不做任何 DNS 解析（{@code getByName} 对字面量是本地解析）。
     */
    public static String normalizeIp(String clientIp) {
        if (clientIp == null) {
            return "unknown";
        }
        String ip = clientIp.trim().toLowerCase(Locale.ROOT);
        if (ip.isEmpty()) {
            return "unknown";
        }
        if (ip.startsWith("[") && ip.endsWith("]") && ip.length() > 2) {
            ip = ip.substring(1, ip.length() - 1);
        }
        int zone = ip.indexOf('%');
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.indexOf(':') < 0 || !ip.matches("[0-9a-f:.]+")) {
            return ip;
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) {
                return ip;
            }
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.append("::/64").toString();
        } catch (Exception e) {
            return ip;
        }
    }

    private LockStatus checkScope(String scope, String attemptKey, Instant now, Layer layer) {
        Optional<Attempt> found = repository.find(scope, attemptKey);
        if (found.isEmpty()) {
            return LockStatus.unlocked();
        }
        Attempt attempt = found.get();
        if (attempt.lockedAt(now)) {
            return LockStatus.locked(attempt.retryAfterSeconds(now), layer);
        }
        if (attempt.lockedUntil() != null) {
            // 锁刚过期：整条清掉，下一次失败重新从 1 开始计数。
            repository.delete(scope, attemptKey);
        }
        return LockStatus.unlocked();
    }

    private static long seconds(long millis) {
        return Math.max(1L, (millis + 999) / 1000);
    }

    private static String pairKey(String account, String clientIp) {
        return LoginAttemptRepository.normalizeKey(account) + "|" + normalizeIp(clientIp);
    }

    private static String accountKey(String account) {
        return LoginAttemptRepository.normalizeKey(account);
    }
}
