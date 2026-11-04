package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.stereotype.Component;

/**
 * Consecutive-failure lockout for account/IP pairs.
 * <p>
 * v0.1.4：状态由进程内存改为 {@code login_attempt} 表，重启后锁定仍然生效；
 * 对外语义（key = 账号 + IP、连续失败达阈值锁定、成功后清零）保持不变。
 */
@Component
public class LoginAttemptService {

    private final AppSecurityProperties properties;
    private final LoginAttemptRepository repository;

    public LoginAttemptService(AppSecurityProperties properties, LoginAttemptRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public boolean isLocked(String username, String clientIp) {
        LoginAttemptRepository.Attempt attempt = repository.find(key(username, clientIp)).orElse(null);
        if (attempt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil() > now) {
            return true;
        }
        if (attempt.lockedUntil() > 0) {
            // 锁定期已过：整条记录清零，下一次失败重新从 1 开始计数。
            repository.delete(key(username, clientIp));
        }
        return false;
    }

    public long retryAfterSeconds(String username, String clientIp) {
        LoginAttemptRepository.Attempt attempt = repository.find(key(username, clientIp)).orElse(null);
        long now = System.currentTimeMillis();
        if (attempt == null || attempt.lockedUntil() <= now) {
            return 0L;
        }
        return Math.max(1L, (attempt.lockedUntil() - now + 999) / 1000);
    }

    /**
     * 记一次失败，返回累计失败次数（含本次），供审计日志写"累计失败 N 次"。
     */
    public int recordFailure(String username, String clientIp) {
        String attemptKey = key(username, clientIp);
        LoginAttemptRepository.Attempt existing = repository.find(attemptKey).orElse(null);
        int failures = (existing == null ? 0 : existing.failures()) + 1;
        long lockedUntil = failures >= properties.getLoginMaxFailures()
                ? System.currentTimeMillis() + properties.getLoginLockMillis()
                : 0L;
        repository.upsert(attemptKey, failures, lockedUntil);
        return failures;
    }

    /** 当前累计失败次数（未锁定或未记录时为 0）。 */
    public int failureCount(String username, String clientIp) {
        return repository.find(key(username, clientIp)).map(LoginAttemptRepository.Attempt::failures).orElse(0);
    }

    /** 触发锁定的失败次数阈值，供审计日志判断本次是否"触发锁定"。 */
    public int maxFailures() {
        return properties.getLoginMaxFailures();
    }

    public void recordSuccess(String username, String clientIp) {
        repository.delete(key(username, clientIp));
    }

    /** 仅供测试与运维清理使用。 */
    public void reset() {
        repository.clearAll();
    }

    private String key(String username, String clientIp) {
        return (username == null ? "" : username) + "|" + (clientIp == null ? "" : clientIp);
    }
}
