package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.ChatUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.chat-per-day=1",
        "app.security.chat-usage-zone=Asia/Shanghai"
})
class ChatQuotaMidnightTest {

    @Autowired
    private ChatQuotaService chatQuotaService;

    @Autowired
    private ChatUsageRepository chatUsageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setup() {
        jdbcTemplate.execute("DELETE FROM chat_usage_daily");
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void dailyWindowSwitchesAtShanghaiMidnight() {
        // 2026-09-13 23:59 Asia/Shanghai
        when(clock.instant()).thenReturn(Instant.parse("2026-09-13T15:59:00Z"));
        assertTrue(chatQuotaService.tryConsume(1).allowed());
        assertFalse(chatQuotaService.tryConsume(1).allowed());

        // 2026-09-14 00:01 Asia/Shanghai
        when(clock.instant()).thenReturn(Instant.parse("2026-09-13T16:01:00Z"));
        assertTrue(chatQuotaService.tryConsume(1).allowed());

        assertEquals(1, chatUsageRepository.getCount(1, "2026-09-13"));
        assertEquals(1, chatUsageRepository.getCount(1, "2026-09-14"));
    }

    @Test
    void refundGoesBackToTheDayTheRequestWasCountedOn() {
        // Admitted at 23:59 on the 13th ...
        when(clock.instant()).thenReturn(Instant.parse("2026-09-13T15:59:00Z"));
        ChatQuotaDecision admitted = chatQuotaService.tryConsume(2);
        assertTrue(admitted.allowed());
        assertEquals("2026-09-13", admitted.consumedDate());

        // ... and the LLM failure is only noticed after midnight.
        when(clock.instant()).thenReturn(Instant.parse("2026-09-13T16:01:00Z"));
        chatQuotaService.refund(2, admitted);

        assertEquals(0, chatUsageRepository.getCount(2, "2026-09-13"));
        assertEquals(0, chatUsageRepository.getCount(2, "2026-09-14"));
    }
}
