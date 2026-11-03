package org.example.lifecomposer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.security.chat-per-minute=2",
        "app.security.chat-per-day=100"
})
class ChatQuotaControllerTest extends BaseControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.example.lifecomposer.Service.ChatConcurrencyGuard chatConcurrencyGuard;

    @Test
    void minuteLimitRejectsThirdRequestWithinWindow() throws Exception {
        MockHttpSession session = registerAndLogin("quotauser1", "testpass123");

        sendChat(session).andExpect(status().isServiceUnavailable());
        sendChat(session).andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/chat/send")
                        .session(session)
                        .header("User-Agent", "TestClient/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"第三条\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("CHAT_MINUTE_LIMIT"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.remainingToday").value(98));

        assertEquals(2, usageCount(session));
    }

    @Test
    void acceptedRequestCountsExactlyOnce() throws Exception {
        MockHttpSession session = registerAndLogin("quotauser2", "testpass123");

        sendChat(session).andExpect(status().isServiceUnavailable());

        assertEquals(1, usageCount(session));
    }

    @Test
    void streamEndpointAlsoUsesQuotaAndRejectsThird() throws Exception {
        MockHttpSession session = registerAndLogin("quotauser3", "testpass123");

        mockMvc.perform(post("/api/chat/stream")
                        .session(session)
                        .header("User-Agent", "TestClient/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"流式1\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        // v0.1.2: one AI request per user; wait for the first stream to finish.
        awaitChatSlotFree(session);

        mockMvc.perform(post("/api/chat/stream")
                        .session(session)
                        .header("User-Agent", "TestClient/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"流式2\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        awaitChatSlotFree(session);

        mockMvc.perform(post("/api/chat/stream")
                        .session(session)
                        .header("User-Agent", "TestClient/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"流式3\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("CHAT_MINUTE_LIMIT"));

        assertEquals(2, usageCount(session));
    }

    @Test
    void adminCanResetTodayQuotaAndNormalUserCannot() throws Exception {
        MockHttpSession userSession = registerAndLogin("quotauser4", "testpass123");
        sendChat(userSession).andExpect(status().isServiceUnavailable());
        int userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Integer.class, "quotauser4");
        assertEquals(1, usageCount(userId));

        mockMvc.perform(post("/api/users/admin/chat-quota/reset/" + userId)
                        .session(userSession)
                        .header("User-Agent", "TestClient/1.0"))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = loginAsAdmin();
        mockMvc.perform(post("/api/users/admin/chat-quota/reset/" + userId)
                        .session(adminSession)
                        .header("User-Agent", "TestClient/1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.usedToday").value(0))
                .andExpect(jsonPath("$.remainingToday").value(100));

        assertEquals(0, usageCount(userId));
    }

    @Test
    void unauthenticatedCannotResetQuota() throws Exception {
        mockMvc.perform(post("/api/users/admin/chat-quota/reset/1")
                        .header("User-Agent", "TestClient/1.0"))
                .andExpect(status().isForbidden());
    }

    @Test
    void safeHistoryReadDoesNotConsumeQuota() throws Exception {
        MockHttpSession session = registerAndLogin("quotauser5", "testpass123");
        mockMvc.perform(get("/api/chat/history")
                        .session(session)
                        .header("User-Agent", "TestClient/1.0"))
                .andExpect(status().isOk());
        assertEquals(0, usageCount(session));
    }

    private org.springframework.test.web.servlet.ResultActions sendChat(MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/api/chat/send")
                .session(session)
                .header("User-Agent", "TestClient/1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"配额测试\"}"));
    }

    private void awaitChatSlotFree(MockHttpSession session) {
        if (!(session.getAttribute("user") instanceof org.example.lifecomposer.Entity.User user)) {
            return;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (chatConcurrencyGuard.isActive(user.getId()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private int usageCount(MockHttpSession session) {
        Object userObject = session.getAttribute("user");
        if (!(userObject instanceof org.example.lifecomposer.Entity.User user)) {
            return 0;
        }
        return usageCount(user.getId());
    }

    private int usageCount(int userId) {
        java.util.List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT request_count FROM chat_usage_daily WHERE user_id = ? AND usage_date = date('now', '+8 hours')",
                Integer.class, userId);
        return counts.isEmpty() || counts.get(0) == null ? 0 : counts.get(0);
    }
}
