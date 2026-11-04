package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.stereotype.Component;

/**
 * 登录连续失败锁定，维度是<b>账号</b>。
 *
 * <p>v0.1.4 起状态落在 {@code login_attempt} 表里，重启后锁定仍然生效。</p>
 *
 * <p>v0.1.5 起 key 从"账号 + IP"改成"只按账号"：原来的双维度换一个 IP 就能绕过锁定，
 * 而 IP 维度的防刷交给 {@code RegistrationRateLimiter} 那类限流更合适。调用方传进来的
 * {@code account} 已经是规范化后的账号（存在时用 {@code users.username}，不存在时用小写的输入值），
 * 所以同一个人的学号 / 用户名 / 邮箱会落到同一条记录上。</p>
 */
@Component
public class LoginAttemptService {

    private final AppSecurityProperties properties;
    private final LoginAttemptRepository repository;

    public LoginAttemptService(AppSecurityProperties properties, LoginAttemptRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public boolean isLocked(String account) {
        LoginAttemptRepository.Attempt attempt = repository.find(key(account)).orElse(null);
        if (attempt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil() > now) {
            return true;
        }
        if (attempt.lockedUntil() > 0) {
            // 锁定期已过：整条记录清零，下一次失败重新从 1 开始计数。
            repository.delete(key(account));
        }
        return false;
    }

    public long retryAfterSeconds(String account) {
        LoginAttemptRepository.Attempt attempt = repository.find(key(account)).orElse(null);
        long now = System.currentTimeMillis();
        if (attempt == null || attempt.lockedUntil() <= now) {
            return 0L;
        }
        return Math.max(1L, (attempt.lockedUntil() - now + 999) / 1000);
    }

    /**
     * 记一次失败，返回累计失败次数（含本次），供审计日志写"累计失败 N 次"。
     */
    public int recordFailure(String account) {
        String attemptKey = key(account);
        LoginAttemptRepository.Attempt existing = repository.find(attemptKey).orElse(null);
        int failures = (existing == null ? 0 : existing.failures()) + 1;
        long lockedUntil = failures >= properties.getLoginMaxFailures()
                ? System.currentTimeMillis() + properties.getLoginLockMillis()
                : 0L;
        repository.upsert(attemptKey, failures, lockedUntil);
        return failures;
    }

    /** 当前累计失败次数（未锁定或未记录时为 0）。 */
    public int failureCount(String account) {
        return repository.find(key(account)).map(LoginAttemptRepository.Attempt::failures).orElse(0);
    }

    /** 触发锁定的失败次数阈值，供审计日志判断本次是否"触发锁定"。 */
    public int maxFailures() {
        return properties.getLoginMaxFailures();
    }

    public void recordSuccess(String account) {
        repository.delete(key(account));
    }

    /** 仅供测试与运维清理使用。 */
    public void reset() {
        repository.clearAll();
    }

    private String key(String account) {
        if (account == null) {
            return "";
        }
        String trimmed = account.trim();
        return trimmed.isEmpty() ? "" : trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
