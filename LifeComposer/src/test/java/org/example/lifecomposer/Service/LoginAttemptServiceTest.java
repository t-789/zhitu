package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.Repository.LoginLogRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.1.6 起锁定分两层：{@code pair}（账号 + IP）与 {@code account}（账号总量）。
 * 管理员豁免第二层（见 {@link #adminAccountIgnoresAccountLevelLock()}）。
 */
class LoginAttemptServiceTest {

    private static final String IP_A = "10.0.0.1";
    private static final String IP_B = "10.0.0.2";

    private JdbcTemplate jdbcTemplate;
    private LoginAttemptRepository repository;

    @BeforeEach
    void setUp() {
        // 一条连接的内存 SQLite：连接存活期间表结构与数据都在。
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new LoginAttemptRepository(jdbcTemplate);
        repository.createTableIfNeeded();
    }

    private LoginAttemptService newService(AppSecurityProperties properties) {
        return new LoginAttemptService(properties, repository);
    }

    private static AppSecurityProperties defaults() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(5);
        properties.setLoginLockMillis(60_000L);
        properties.setLoginTotalWindowMinutes(60);
        properties.setLoginTotalFailuresPerHour(30);
        properties.setLoginTotalLockMillis(120_000L);
        return properties;
    }

    @Test
    @DisplayName("第一层：同一 IP 对同一账号连错 5 次锁定，登录成功清零")
    void pairLayerLocksAfterConfiguredFailuresAndUnlocksOnSuccess() {
        LoginAttemptService service = newService(defaults());

        assertFalse(service.check("alice", IP_A, true).locked());
        for (int i = 1; i <= 4; i++) {
            service.recordFailure("alice", IP_A, true);
            assertFalse(service.check("alice", IP_A, true).locked(), "第 " + i + " 次失败不该锁");
        }
        LoginAttemptService.FailureOutcome fifth = service.recordFailure("alice", IP_A, true);
        assertTrue(fifth.locked());
        assertEquals(LoginAttemptService.Layer.PAIR, fifth.layer());

        LoginAttemptService.LockStatus status = service.check("alice", IP_A, true);
        assertTrue(status.locked());
        assertEquals(LoginAttemptService.Layer.PAIR, status.layer());
        assertTrue(status.retryAfterSeconds() > 0);

        service.recordSuccess("alice", IP_A);
        assertFalse(service.check("alice", IP_A, true).locked());
        assertEquals(0, repository.countAll());
    }

    @Test
    @DisplayName("第一层维度正确：换 IP 的受害者不会被别的 IP 锁住")
    void pairLayerDoesNotLockVictimOnOtherIp() {
        LoginAttemptService service = newService(defaults());

        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice", IP_A, true);
        }
        assertTrue(service.check("alice", IP_A, true).locked(), "攻击者自己的 IP 组合被锁");
        assertFalse(service.check("alice", IP_B, true).locked(), "受害者在别的 IP 上仍可登录");
    }

    @Test
    @DisplayName("第二层：同一账号在窗口内累计 30 次失败（跨 IP）才强锁")
    void accountLayerLocksAcrossIps() {
        LoginAttemptService service = newService(defaults());

        // 29 次失败分散在 29 个 IP 上：第一层永不触发，第二层还差一次。
        for (int i = 0; i < 29; i++) {
            LoginAttemptService.FailureOutcome outcome =
                    service.recordFailure("alice", "10.0.1." + i, true);
            assertFalse(outcome.locked(), "第 " + (i + 1) + " 次不该锁");
        }
        assertFalse(service.check("alice", "10.0.2.99", true).locked());

        LoginAttemptService.FailureOutcome thirtieth =
                service.recordFailure("alice", "10.0.2.99", true);
        assertTrue(thirtieth.locked());
        assertEquals(LoginAttemptService.Layer.ACCOUNT, thirtieth.layer());

        LoginAttemptService.LockStatus status = service.check("alice", "10.0.3.7", true);
        assertTrue(status.locked(), "账号总量锁对任何 IP 都生效");
        assertEquals(LoginAttemptService.Layer.ACCOUNT, status.layer());
        assertTrue(status.retryAfterSeconds() > 0);
    }

    @Test
    @DisplayName("管理员豁免第二层：跨 IP 打满也不会被账号总量锁死")
    void adminAccountIgnoresAccountLevelLock() {
        LoginAttemptService service = newService(defaults());

        for (int i = 0; i < 40; i++) {
            LoginAttemptService.FailureOutcome outcome =
                    service.recordFailure("admin", "10.0.9." + i, false);
            assertFalse(outcome.locked(), "管理员不该被总量锁");
        }
        assertFalse(service.check("admin", "10.0.9.99", false).locked());
        assertEquals(0, repository.countByScope(LoginAttemptRepository.SCOPE_ACCOUNT),
                "管理员不写第二层计数");

        // 但第一层仍然生效：同一 IP 连错 5 次照样锁。
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin", IP_A, false);
        }
        assertTrue(service.check("admin", IP_A, false).locked());
    }

    @Test
    @DisplayName("计数原子：并发 8 次失败不会丢计数（审核 B1）")
    void concurrentFailuresAreNotLost() throws Exception {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(100);
        properties.setLoginTotalFailuresPerHour(100);
        LoginAttemptService service = newService(properties);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        service.recordFailure("alice", IP_A, false);
                    } catch (Throwable t) {
                        synchronized (errors) {
                            errors.add(t);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发失败不应抛异常：" + errors);
        assertFalse(service.check("alice", IP_A, false).locked(), "阈值设成 100，8 次不该锁");
        assertEquals(threads, repository.find(LoginAttemptRepository.SCOPE_PAIR, "alice|" + IP_A)
                .orElseThrow().failures(), "8 个并发请求必须一个不丢");
    }

    @Test
    @DisplayName("账号 key 归一：Alice / alice 落到同一条记录")
    void accountKeyIsNormalized() {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(1);
        LoginAttemptService service = newService(properties);

        service.recordFailure("  Alice  ", IP_A, false);
        assertTrue(service.check("alice", IP_A, false).locked());
        assertEquals(1, repository.countAll());
    }

    @Test
    @DisplayName("锁定状态落库：新建服务实例（模拟进程重启）后仍然锁定")
    void lockSurvivesRestart() {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(2);

        LoginAttemptService beforeRestart = newService(properties);
        beforeRestart.recordFailure("alice", IP_A, false);
        beforeRestart.recordFailure("alice", IP_A, false);
        assertTrue(beforeRestart.check("alice", IP_A, false).locked());

        LoginAttemptService afterRestart = newService(properties);
        assertTrue(afterRestart.check("alice", IP_A, false).locked(), "重启后锁定状态应仍然有效");
        assertTrue(afterRestart.check("alice", IP_A, false).retryAfterSeconds() > 0);
    }

    @Test
    @DisplayName("锁定期满后自动清零，下一次失败从 1 重新计数")
    void expiredLockIsCleared() throws Exception {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(1);
        properties.setLoginLockMillis(1_000L); // 1 秒后过期（库里的时间列精度是秒）
        LoginAttemptService service = newService(properties);

        service.recordFailure("alice", IP_A, false);
        assertTrue(service.check("alice", IP_A, false).locked(), "刚失败时是锁的");
        Thread.sleep(1_200);
        assertFalse(service.check("alice", IP_A, false).locked(), "过期后应解锁");
        assertEquals(0, repository.countAll(), "过期记录应被清除");

        service.recordFailure("alice", IP_A, false);
        assertEquals(1, repository.countAll());
    }

    @Test
    @DisplayName("锁定时长配成 0 表示关掉该层（只计数不锁）")
    void zeroLockDurationDisablesLocking() {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(1);
        properties.setLoginLockMillis(0L);
        LoginAttemptService service = newService(properties);

        LoginAttemptService.FailureOutcome outcome = service.recordFailure("alice", IP_A, false);
        assertFalse(outcome.locked());
        assertFalse(service.check("alice", IP_A, false).locked());
        assertEquals(1, repository.countAll());
    }

    @Test
    @DisplayName("时间列是 UTC 文本，不是 epoch 毫秒（审核 B3）")
    void timestampsAreUtcText() {
        AppSecurityProperties properties = defaults();
        properties.setLoginMaxFailures(1);
        LoginAttemptService service = newService(properties);
        service.recordFailure("alice", IP_A, false);

        String lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM login_attempt WHERE scope = 'pair'", String.class);
        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "locked_until 应是 yyyy-MM-dd HH:mm:ss 文本，实际为 " + lockedUntil);
    }

    @Test
    @DisplayName("清理任务：长期无更新的计数行与超期审计行都会被删（审核 B2）")
    void cleanupRemovesExpiredRows() {
        AppSecurityProperties properties = defaults();
        LoginAttemptService service = newService(properties);
        service.recordFailure("stale", IP_A, false);

        // 把计数行改成 40 天前，模拟长期无人再试的垃圾行。
        jdbcTemplate.update("UPDATE login_attempt SET updated_at = datetime('now', '-40 days')");

        LoginLogRepository logRepository = new LoginLogRepository(jdbcTemplate);
        logRepository.createTableIfNeeded();
        logRepository.insert(null, "old", IP_A, "junit", 0, "账号不存在");
        jdbcTemplate.update("UPDATE login_log SET created_at = datetime('now', '-120 days')");

        LoginCleanupJob job = new LoginCleanupJob(repository, logRepository, properties);
        LoginCleanupJob.CleanupResult result = job.runOnce();

        assertTrue(result.idleAttempts() >= 1, "长期无更新的计数行应被清掉");
        assertEquals(0, repository.countAll());
        assertTrue(result.oldLogs() >= 1, "超过保留期的审计行应被清掉");
        assertEquals(0L, logRepository.countAll());
    }
}
