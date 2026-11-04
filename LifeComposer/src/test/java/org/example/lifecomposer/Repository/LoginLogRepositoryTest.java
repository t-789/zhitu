package org.example.lifecomposer.Repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoginLogRepositoryTest {

    private LoginLogRepository repository;

    @BeforeEach
    void setUp() {
        // 一条连接的内存 SQLite：连接存活期间表结构与数据都在。
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        repository = new LoginLogRepository(new JdbcTemplate(dataSource));
        repository.createTableIfNeeded();
    }

    @Test
    @DisplayName("成功与失败各写一行，字段与状态可读")
    void recordsSuccessAndFailure() {
        long okId = repository.insert(7, "20260001", "10.0.0.1", "Mozilla/5.0", LoginLogRepository.STATUS_SUCCESS, null);
        long failId = repository.insert(7, "20260001", "10.0.0.1", "Mozilla/5.0", LoginLogRepository.STATUS_FAILURE,
                "密码错误，累计失败 1 次");

        assertTrue(okId > 0 && failId > okId);
        assertEquals(2, repository.countAll());

        List<LoginLogRepository.Entry> rows = repository.findRecentByAccount("20260001", 10);
        assertEquals(2, rows.size());
        // 新的在前
        assertFalse(rows.get(0).success());
        assertEquals("密码错误，累计失败 1 次", rows.get(0).failureReason());
        assertTrue(rows.get(1).success());
        assertNull(rows.get(1).failureReason());
        assertEquals(7, rows.get(1).userId());
        assertEquals("10.0.0.1", rows.get(1).ipAddress());
        assertEquals("Mozilla/5.0", rows.get(1).userAgent());
        assertNotNull(rows.get(1).createdAt());
    }

    @Test
    @DisplayName("账号不存在时 user_id 可为空，审计仍然可写")
    void allowsAnonymousFailure() {
        repository.insert(null, "nosuchuser", "10.0.0.2", null, LoginLogRepository.STATUS_FAILURE, "账号不存在");

        LoginLogRepository.Entry entry = repository.findLatestByAccount("nosuchuser").orElseThrow();
        assertNull(entry.userId());
        assertNull(entry.userAgent());
        assertEquals("账号不存在", entry.failureReason());
    }

    @Test
    @DisplayName("超长 UA 与原因被截断，不会写爆表")
    void truncatesLongValues() {
        String longAgent = "A".repeat(500);
        String longReason = "原".repeat(500);
        repository.insert(1, "u", null, longAgent, LoginLogRepository.STATUS_FAILURE, longReason);

        LoginLogRepository.Entry entry = repository.findLatestByAccount("u").orElseThrow();
        assertEquals(256, entry.userAgent().length());
        assertEquals(200, entry.failureReason().length());
        assertNull(entry.ipAddress());
    }

    @Test
    @DisplayName("查询按账号隔离")
    void filtersByAccount() {
        repository.insert(1, "alice", "10.0.0.1", null, LoginLogRepository.STATUS_SUCCESS, null);
        repository.insert(2, "bob", "10.0.0.2", null, LoginLogRepository.STATUS_SUCCESS, null);

        assertEquals(1, repository.findRecentByAccount("alice", 10).size());
        assertEquals("bob", repository.findRecentByAccount("bob", 10).get(0).account());
        assertTrue(repository.findRecentByAccount("carol", 10).isEmpty());
    }
}
