package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class ChatMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ChatMessage> ROW_MAPPER = new RowMapper<>() {
        @Override
        public ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChatMessage msg = new ChatMessage();
            msg.setId(rs.getInt("id"));
            msg.setUserId(rs.getInt("user_id"));
            msg.setRole(rs.getString("role"));
            msg.setContent(rs.getString("content"));
            msg.setPromptVersion(rs.getString("prompt_version"));
            msg.setCreateTime(rs.getTimestamp("create_time"));
            return msg;
        }
    };

    public void createChatMessageTableIfNeeded() {
        String sql = """
                CREATE TABLE IF NOT EXISTS chat_messages (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  role TEXT NOT NULL,
                  content TEXT NOT NULL,
                  prompt_version TEXT,
                  create_time TIMESTAMP NOT NULL
                )
                """;
        jdbcTemplate.execute(sql);
    }

    /** v0.1 M3: idempotent column migration for databases created before prompt versions. */
    public void migrateSchema() {
        if (!hasColumn("chat_messages", "prompt_version")) {
            jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN prompt_version TEXT");
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<String> columns = jdbcTemplate.query("PRAGMA table_info(" + tableName + ")",
                (rs, rowNum) -> rs.getString("name"));
        return columns.stream().anyMatch(col -> col.equalsIgnoreCase(columnName));
    }

    public int saveMessage(ChatMessage msg) {
        String sql = "INSERT INTO chat_messages(user_id, role, content, prompt_version, create_time) "
                + "VALUES (?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, msg.getUserId());
            ps.setString(2, msg.getRole());
            ps.setString(3, msg.getContent());
            ps.setString(4, msg.getPromptVersion());
            ps.setTimestamp(5, msg.getCreateTime());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    /** Atomically insert a tool_call + tool_result pair (or similar batch). */
    @Transactional
    public void saveMessages(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            saveMessage(message);
        }
    }

    public List<ChatMessage> findByUserId(Integer userId) {
        return jdbcTemplate.query(
                "SELECT * FROM chat_messages WHERE user_id = ? ORDER BY create_time ASC, id ASC",
                ROW_MAPPER, userId);
    }

    /**
     * Prefix of every persisted assistant tool_call row. AgentOrchestrator builds
     * that payload with "type" as the first key; a test pins this contract.
     */
    public static final String TOOL_CALL_CONTENT_PREFIX = "{\"type\":\"tool_call\"";

    /**
     * v0.1.2: the LLM context window. Returns the rows starting at the
     * {@code limit}-th most recent "conversation" message (a user message or an
     * assistant final answer), including the tool_call / tool rows that follow
     * it. Tool rows are not counted. With fewer conversation messages than
     * {@code limit}, the whole history is returned. The full history stays in
     * the table; {@link #findByUserId} is unchanged.
     */
    public List<ChatMessage> findRecentContext(Integer userId, int limit) {
        int safeLimit = Math.max(1, limit);
        return jdbcTemplate.query("""
                        SELECT * FROM chat_messages
                        WHERE user_id = ? AND id >= COALESCE((
                            SELECT id FROM chat_messages
                            WHERE user_id = ?
                              AND (role = 'user'
                                   OR (role = 'assistant' AND content NOT LIKE ?))
                            ORDER BY create_time DESC, id DESC
                            LIMIT 1 OFFSET ?), 0)
                        ORDER BY create_time ASC, id ASC
                        """,
                ROW_MAPPER, userId, userId, TOOL_CALL_CONTENT_PREFIX + "%", safeLimit - 1);
    }

    public int deleteByUserId(Integer userId) {
        return jdbcTemplate.update("DELETE FROM chat_messages WHERE user_id = ?", userId);
    }
}
