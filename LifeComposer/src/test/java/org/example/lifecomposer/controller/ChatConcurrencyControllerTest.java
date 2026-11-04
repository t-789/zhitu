package org.example.lifecomposer.controller;

import org.example.lifecomposer.Service.ChatConcurrencyGuard;
import org.example.lifecomposer.Service.LlmClientFactory;
import org.example.lifecomposer.support.StubChatLlm;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.example.lifecomposer.Service.ProfileChangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.1.2 P0-2 / P0-3: one AI request per user, bounded stream executor. */
@TestPropertySource(properties = "app.security.chat-stream-max-concurrent=1")
class ChatConcurrencyControllerTest extends BaseControllerTest {

    private static final String UA = "TestClient/1.0";

    @Autowired
    private ChatConcurrencyGuard guard;

    @Autowired
    @Qualifier("chatStreamExecutor")
    private ExecutorService chatStreamExecutor;

    @Autowired
    private ProfileChangeService profileChangeService;

    @MockitoBean
    private LlmClientFactory llmClientFactory;

    @BeforeEach
    void healthyLlm() {
        when(llmClientFactory.getClient("chat")).thenReturn(StubChatLlm.replying("ok"));
    }

    private int userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Integer.class, username);
    }

    private int usageCount(int userId) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT request_count FROM chat_usage_daily WHERE user_id = ? AND usage_date = date('now', '+8 hours')",
                Integer.class, userId);
        return counts.isEmpty() || counts.get(0) == null ? 0 : counts.get(0);
    }

    private ResultActions chat(MockHttpSession session, String path) throws Exception {
        return mockMvc.perform(post(path)
                .session(session)
                .header("User-Agent", UA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"并发测试\"}"));
    }

    @Test
    void sendIsRejectedWithoutQuotaWhileAnotherRequestIsRunning() throws Exception {
        MockHttpSession session = registerAndLogin("concurrent1", "testpass123");
        int id = userId("concurrent1");

        assertTrue(guard.tryAcquire(id));
        try {
            chat(session, "/api/chat/send")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CHAT_IN_PROGRESS"));
            chat(session, "/api/chat/stream")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CHAT_IN_PROGRESS"));
            assertEquals(0, usageCount(id), "被拒绝的并发请求不扣额度");
        } finally {
            guard.release(id);
        }

        // Slot free again: the request proceeds, is served and charged, and the
        // slot is released afterwards.
        chat(session, "/api/chat/send").andExpect(status().isOk());
        assertEquals(1, usageCount(id));
        assertFalse(guard.isActive(id));
    }

    @Test
    void streamReleasesTheSlotWhenItFinishes() throws Exception {
        MockHttpSession session = registerAndLogin("concurrent2", "testpass123");
        int id = userId("concurrent2");

        chat(session, "/api/chat/stream").andExpect(status().isOk()).andExpect(request().asyncStarted());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (guard.isActive(id) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(guard.isActive(id), "流结束后必须释放用户锁");
    }

    @Test
    void saturatedExecutorAnswers503AndRefundsQuota() throws Exception {
        MockHttpSession session = registerAndLogin("concurrent3", "testpass123");
        int id = userId("concurrent3");

        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        chatStreamExecutor.execute(() -> {
            occupied.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
        try {
            chat(session, "/api/chat/stream")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("CHAT_SERVER_BUSY"));
            assertEquals(0, usageCount(id), "未进入模型的请求应退还额度");
            assertFalse(guard.isActive(id), "拒绝后必须释放用户锁");
        } finally {
            finish.countDown();
        }
    }

    @Test
    void profileDecisionIsSavedButContinuationSkippedWhileChatIsRunning() throws Exception {
        MockHttpSession session = registerAndLogin("concurrent4", "testpass123");
        int id = userId("concurrent4");
        var created = profileChangeService.propose((long) id, List.of(
                new ProfileChangeService.ProfileChangeProposal(
                        "availableTime", "每周 6 小时", "用户说每周能投入 6 小时")), "测试");

        assertTrue(guard.tryAcquire(id));
        try {
            mockMvc.perform(post("/api/profiles/change-candidates/" + created.get(0).getCandidateId() + "/decision")
                            .session(session).header("User-Agent", UA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"CONFIRM\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.agentAnswered").value(false));
        } finally {
            guard.release(id);
        }
        assertEquals("每周 6 小时", jdbcTemplate.queryForObject(
                "SELECT available_time FROM user_profiles WHERE user_id = ?", String.class, id));
        assertEquals(0, usageCount(id), "跳过续答不扣额度");
    }
}
