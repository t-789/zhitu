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

        assertFalse(service.isLocked("alice"));
        service.recordFailure("alice");
        assertFalse(service.isLocked("alice"));
        service.recordFailure("alice");
        assertTrue(service.isLocked("alice"));
        assertTrue(service.retryAfterSeconds("alice") > 0);

        service.recordSuccess("alice");
        assertFalse(service.isLocked("alice"));
    }

    @Test
    @DisplayName("锁定只认账号：换 IP 不解锁，换账号互不影响")
    void lockIsScopedByAccountOnly() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(1);
        LoginAttemptService service = newService(properties);

        // 同一个账号只记一次失败，就已经锁定（IP 不再参与 key）
        service.recordFailure("alice");
        assertTrue(service.isLocked("alice"));
        assertFalse(service.isLocked("bob"));
    }

    @Test
    @DisplayName("账号大小写与首尾空白归一：Alice / alice 是同一条记录")
    void accountKeyIsNormalized() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(1);
        LoginAttemptService service = newService(properties);

        service.recordFailure("  Alice  ");
        assertTrue(service.isLocked("alice"));
        assertEquals(1, repository.countAll());
    }

    @Test
    @DisplayName("锁定状态落库：新建服务实例（模拟进程重启）后仍然锁定")
    void lockSurvivesRestart() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockMillis(60_000L);

        LoginAttemptService beforeRestart = newService(properties);
        beforeRestart.recordFailure("alice");
        beforeRestart.recordFailure("alice");
        assertTrue(beforeRestart.isLocked("alice"));

        LoginAttemptService afterRestart = newService(properties);
        assertTrue(afterRestart.isLocked("alice"), "重启后锁定状态应仍然有效");
        assertTrue(afterRestart.retryAfterSeconds("alice") > 0);
        assertEquals(1, repository.countAll(), "每个账号只应有一条记录");
    }

    @Test
    @DisplayName("锁定期满后自动清零，下一次失败从 1 重新计数")
    void expiredLockIsCleared() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLoginMaxFailures(1);
        properties.setLoginLockMillis(0L);
        LoginAttemptService service = newService(properties);

        service.recordFailure("alice");
        assertFalse(service.isLocked("alice"), "锁定时间为 0 时应立即过期");
        assertEquals(0, repository.countAll(), "过期记录应被清除");

        service.recordFailure("alice");
        assertEquals(1, repository.countAll());
    }
}
