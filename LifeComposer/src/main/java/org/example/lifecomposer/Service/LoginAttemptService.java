package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.Repository.LoginAttemptRepository.Attempt;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 登录失败锁定，v0.1.6 起分两层（审核反馈 A 节）。
 *
 * <p><b>第一层 {@code pair}</b>：同一 IP 对同一账号连错 {@code login-max-failures}
 * 次（默认 5），锁这一对组合 {@code login-lock-millis}（默认 15 分钟）。
 * 攻击者只能锁自己的 IP，锁不住受害者在别处的登录。</p>
 *
 * <p><b>第二层 {@code account}</b>：同一账号 {@code login-total-window-minutes}（默认 60）
 * 分钟内累计失败 {@code login-total-failures-per-hour}（默认 30）次，不分 IP，
 * 锁 {@code login-total-lock-millis}（默认 30 分钟）。分布式换 IP 猜密码会撞上这一层。</p>
 *
 * <p><b>管理员豁免</b>：{@code users.type=2} 的账号只受第一层约束
 * （{@code login-admin-total-lock-enabled=false}），避免外网有人把管理员账号
 * 打到总量阈值上锁死。</p>
 *
 * <p>调用方传入的 {@code account} 已规范化（存在时用 {@code users.username}，
 * 不存在时用去掉首尾空白的小写输入），所以同一人的学号 / 用户名 / 邮箱落到同一条记录。</p>
 *
 * <p>配置里把 {@code login-lock-millis} 或 {@code login-total-lock-millis} 设成 0
 * 等于<b>关掉那一层的锁定</b>（只计数不锁），开发与测试环境用得上。</p>
 */
@Component
public class LoginAttemptService {

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
     * @param accountLevelLock 是否适用第二层（管理员传 false）
     */
    public LockStatus check(String account, String clientIp, boolean accountLevelLock) {
        Instant now = Instant.now();
        LockStatus pair = checkScope(LoginAttemptRepository.SCOPE_PAIR, pairKey(account, clientIp), now, Layer.PAIR);
        if (pair.locked()) {
            return pair;
        }
        if (!accountLevelLock) {
            return LockStatus.unlocked();
        }
        return checkScope(LoginAttemptRepository.SCOPE_ACCOUNT, accountKey(account), now, Layer.ACCOUNT);
    }

    /**
     * 记一次失败，返回累计次数与是否触发锁定。
     *
     * @param accountLevelLock 是否计入第二层（管理员传 false）
     */
    public FailureOutcome recordFailure(String account, String clientIp, boolean accountLevelLock) {
        String pairKey = pairKey(account, clientIp);
        int pairFailures = repository.incrementConsecutive(LoginAttemptRepository.SCOPE_PAIR, pairKey);
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

        if (accountLevelLock) {
            String accountKey = accountKey(account);
            int total = repository.incrementWithinWindow(
                    LoginAttemptRepository.SCOPE_ACCOUNT, accountKey, properties.getLoginTotalWindowMinutes());
            failures = Math.max(failures, total);
            if (total >= properties.getLoginTotalFailuresPerHour() && properties.getLoginTotalLockMillis() > 0) {
                repository.lock(LoginAttemptRepository.SCOPE_ACCOUNT, accountKey, properties.getLoginTotalLockMillis());
                locked = true;
                retryAfter = Math.max(retryAfter, seconds(properties.getLoginTotalLockMillis()));
                layer = Layer.ACCOUNT;
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
        String key = LoginAttemptRepository.normalizeKey(account);
        String ip = clientIp == null ? "unknown" : clientIp.trim();
        return key + "|" + (ip.isEmpty() ? "unknown" : ip);
    }

    private static String accountKey(String account) {
        return LoginAttemptRepository.normalizeKey(account);
    }
}
