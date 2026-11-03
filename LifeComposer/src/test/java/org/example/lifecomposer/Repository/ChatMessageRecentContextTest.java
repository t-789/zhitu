package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v0.1.2: LLM context window over the real SQLite test database. */
@SpringBootTest
@ActiveProfiles("test")
class ChatMessageRecentContextTest {

    private static final int USER_ID = 9901;
    private static final int OTHER_USER_ID = 9902;

    @Autowired
    private ChatMessageRepository repository;

    private long clock;

    @BeforeEach
    void clean() {
        repository.deleteByUserId(USER_ID);
        repository.deleteByUserId(OTHER_USER_ID);
        clock = 1_800_000_000_000L;
    }

    private void save(int userId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(new Timestamp(clock += 1000));
        repository.saveMessage(message);
    }

    private void toolExchange(int userId, String turnId) {
        save(userId, "assistant", ChatMessageRepository.TOOL_CALL_CONTENT_PREFIX
                + ",\"turnId\":\"" + turnId + "\",\"name\":\"search_resources\",\"callId\":\"c-" + turnId
                + "\",\"arguments\":{}}");
        save(userId, "tool", "{\"type\":\"tool_result\",\"turnId\":\"" + turnId + "\",\"callId\":\"c-"
                + turnId + "\",\"ok\":true}");
    }

    @Test
    void returnsWholeHistoryWhenShorterThanLimit() {
        save(USER_ID, "user", "q1");
        toolExchange(USER_ID, "t1");
        save(USER_ID, "assistant", "a1");

        assertEquals(4, repository.findRecentContext(USER_ID, 15).size());
    }

    @Test
    void countsOnlyUserAndAnswerRowsAndKeepsToolRowsInsideWindow() {
        for (int i = 0; i < 10; i++) {
            save(USER_ID, "user", "q" + i);
            toolExchange(USER_ID, "t" + i);
            save(USER_ID, "assistant", "a" + i);
        }
        save(OTHER_USER_ID, "user", "other user");

        List<ChatMessage> window = repository.findRecentContext(USER_ID, 5);

        // 5 conversation rows back from the end: a9, q9, a8, q8, a7 -> starts at a7.
        assertEquals("a7", window.get(0).getContent());
        assertEquals("a9", window.get(window.size() - 1).getContent());
        long conversation = window.stream()
                .filter(m -> !"tool".equals(m.getRole())
                        && !m.getContent().startsWith(ChatMessageRepository.TOOL_CALL_CONTENT_PREFIX))
                .count();
        assertEquals(5, conversation);
        assertEquals(9, window.size(), "两轮工具调用（各 2 行）保留在窗口内但不计数");
        assertEquals(true, window.stream().allMatch(m -> m.getUserId() == USER_ID));
    }

    @Test
    void exactlyLimitMessagesReturnsAll() {
        save(USER_ID, "user", "q1");
        save(USER_ID, "assistant", "a1");
        save(USER_ID, "user", "q2");

        assertEquals(3, repository.findRecentContext(USER_ID, 3).size());
        assertEquals(1, repository.findRecentContext(USER_ID, 1).size());
        assertEquals("q2", repository.findRecentContext(USER_ID, 0).get(0).getContent());
    }

    @Test
    void fullHistoryIsUnchanged() {
        for (int i = 0; i < 20; i++) {
            save(USER_ID, "user", "q" + i);
        }
        assertEquals(20, repository.findByUserId(USER_ID).size());
        assertEquals(15, repository.findRecentContext(USER_ID, 15).size());
    }
}
