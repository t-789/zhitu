package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.LoginAttemptRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptServiceTest {

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

    @Test
    void locksAfterConfiguredFailuresAndUnlocksOnSuccess() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockMillis(60_000L);
        LoginAttemptService service = newService(properties);

        assertFalse(service.isLocked("alice", "10.0.0.1"));
        service.recordFailure("alice", "10.0.0.1");
        assertFalse(service.isLocked("alice", "10.0.0.1"));
        service.recordFailure("alice", "10.0.0.1");
        assertTrue(service.isLocked("alice", "10.0.0.1"));
        assertTrue(service.retryAfterSeconds("alice", "10.0.0.1") > 0);

        service.recordSuccess("alice", "10.0.0.1");
        assertFalse(service.isLocked("alice", "10.0.0.1"));
    }

    @Test
    void lockIsScopedByUsernameAndIp() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(1);
        LoginAttemptService service = newService(properties);

        service.recordFailure("alice", "10.0.0.1");
        assertTrue(service.isLocked("alice", "10.0.0.1"));
        assertFalse(service.isLocked("alice", "10.0.0.2"));
        assertFalse(service.isLocked("bob", "10.0.0.1"));
    }

    @Test
    @DisplayName("锁定状态落库：新建服务实例（模拟进程重启）后仍然锁定")
    void lockSurvivesRestart() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockMillis(60_000L);

        LoginAttemptService beforeRestart = newService(properties);
        beforeRestart.recordFailure("alice", "10.0.0.1");
        beforeRestart.recordFailure("alice", "10.0.0.1");
        assertTrue(beforeRestart.isLocked("alice", "10.0.0.1"));

        LoginAttemptService afterRestart = newService(properties);
        assertTrue(afterRestart.isLocked("alice", "10.0.0.1"), "重启后锁定状态应仍然有效");
        assertTrue(afterRestart.retryAfterSeconds("alice", "10.0.0.1") > 0);
        assertEquals(1, repository.countAll(), "每个账号+IP 只应有一条记录");
    }

    @Test
    @DisplayName("锁定期满后自动清零，下一次失败从 1 重新计数")
    void expiredLockIsCleared() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(1);
        properties.setLoginLockMillis(0L);
        LoginAttemptService service = newService(properties);

        service.recordFailure("alice", "10.0.0.1");
        assertFalse(service.isLocked("alice", "10.0.0.1"), "锁定时间为 0 时应立即过期");
        assertEquals(0, repository.countAll(), "过期记录应被清除");

        service.recordFailure("alice", "10.0.0.1");
        assertEquals(1, repository.countAll());
    }
}
