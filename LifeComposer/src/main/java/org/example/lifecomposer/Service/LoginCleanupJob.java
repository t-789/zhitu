package org.example.lifecomposer.Service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.Repository.LoginLogRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 登录相关表的定时清理（审核反馈 B2）。
 *
 * <p>{@code login_attempt} 的清理原先写了 repository 方法却没有调用点：不存在的账号
 * 每次尝试都会插一行，key 还是攻击者可控的字符串，长期跑会把表撑大；
 * {@code login_log} 每次尝试写一行、没有保留期，同理。</p>
 *
 * <p>三个动作：清掉过期锁（锁过期计数就该从头开始）、清掉长期没更新的计数行、
 * 按保留期删审计行。cron 由 {@code app.security.login-cleanup-cron} 配，默认每小时整点。</p>
 */
@Component
public class LoginCleanupJob {

    private static final Logger LOG = LogManager.getLogger(LoginCleanupJob.class);

    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginLogRepository loginLogRepository;
    private final AppSecurityProperties properties;

    public LoginCleanupJob(LoginAttemptRepository loginAttemptRepository,
                           LoginLogRepository loginLogRepository,
                           AppSecurityProperties properties) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.loginLogRepository = loginLogRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.security.login-cleanup-cron:0 0 * * * *}")
    public void cleanup() {
        runOnce();
    }

    /** 供测试直接调用，避开 cron。 */
    public CleanupResult runOnce() {
        try {
            int expiredLocks = loginAttemptRepository.deleteExpiredLocks();
            int idleAttempts = loginAttemptRepository.purgeIdle(properties.getLoginAttemptRetentionDays());
            int oldLogs = loginLogRepository.deleteOlderThan(properties.getLoginLogRetentionDays());
            if (expiredLocks > 0 || idleAttempts > 0 || oldLogs > 0) {
                LOG.info("AUDIT event=login_cleanup expiredLocks={} idleAttempts={} oldLogs={}",
                        expiredLocks, idleAttempts, oldLogs);
            }
            return new CleanupResult(expiredLocks, idleAttempts, oldLogs);
        } catch (RuntimeException e) {
            // 清理失败不能影响其他定时任务，更不能让进程退出。
            LOG.warn("AUDIT event=login_cleanup_failed error={}", e.toString());
            return new CleanupResult(0, 0, 0);
        }
    }

    public record CleanupResult(int expiredLocks, int idleAttempts, int oldLogs) {
    }
}
