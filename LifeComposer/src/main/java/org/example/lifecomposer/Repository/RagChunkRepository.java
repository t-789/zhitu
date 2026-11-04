package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.RagChunk;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class RagChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<RagChunk> RAG_CHUNK_ROW_MAPPER = new RowMapper<>() {
        @Override
        public RagChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            RagChunk chunk = new RagChunk();
            chunk.setChunkId(rs.getString("chunk_id"));
            chunk.setTitle(rs.getString("title"));
            chunk.setText(rs.getString("text"));
            chunk.setSourceType(rs.getString("source_type"));
            chunk.setSourceUrl(rs.getString("source_url"));
            chunk.setSourceFile(rs.getString("source_file"));
            chunk.setPageOrSection(rs.getString("page_or_section"));
            chunk.setRelatedResourceId(rs.getString("related_resource_id"));
            chunk.setCreatedAt(rs.getString("created_at"));
            chunk.setEmbeddingJson(rs.getString("embedding_json"));
            chunk.setEmbeddingModel(rs.getString("embedding_model"));
            int dimensions = rs.getInt("embedding_dimensions");
            chunk.setEmbeddingDimensions(rs.wasNull() ? null : dimensions);
            chunk.setEmbeddingStatus(rs.getString("embedding_status"));
            chunk.setEmbeddingError(rs.getString("embedding_error"));
            chunk.setEmbeddingUpdatedAt(rs.getString("embedding_updated_at"));
            chunk.setContentHash(rs.getString("content_hash"));
            return chunk;
        }
    };

    public List<RagChunk> findAll() {
        String sql = """
                SELECT * FROM rag_chunks
                ORDER BY chunk_id
                """;
        return jdbcTemplate.query(sql, RAG_CHUNK_ROW_MAPPER);
    }

    /** 检索索引需要的最小列：不含标题、正文等大字段。 */
    public record EmbeddingRow(String chunkId, String embeddingJson, String embeddingModel, Integer dimensions) {
    }

    /**
     * 判断检索索引是否过期：有向量的切片数量、其中最新的向量写入时间、切片总数
     * （关键词索引覆盖没有向量的切片，所以新增这类切片也要触发重建）。
     */
    public record EmbeddingFingerprint(int count, String latestUpdate, int totalChunks) {
    }

    /** 关键词索引需要的列。 */
    public record KeywordRow(String chunkId, String title, String text) {
    }

    /** 有向量的切片的最小列；没有向量的切片不参与检索。 */
    public List<EmbeddingRow> findEmbeddingRows() {
        return jdbcTemplate.query("""
                SELECT chunk_id, embedding_json, embedding_model, embedding_dimensions
                FROM rag_chunks
                WHERE embedding_json IS NOT NULL AND TRIM(embedding_json) != ''
                ORDER BY chunk_id
                """, (rs, rowNum) -> {
            int dimensions = rs.getInt("embedding_dimensions");
            return new EmbeddingRow(
                    rs.getString("chunk_id"),
                    rs.getString("embedding_json"),
                    rs.getString("embedding_model"),
                    rs.wasNull() ? null : dimensions);
        });
    }

    /** 一条轻量聚合查询，供检索缓存判断是否需要重建（导入 CLI 是独立进程，无法主动通知）。 */
    public EmbeddingFingerprint embeddingFingerprint() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(CASE WHEN embedding_json IS NOT NULL AND TRIM(embedding_json) != '' THEN 1 END) AS cnt,
                       MAX(CASE WHEN embedding_json IS NOT NULL AND TRIM(embedding_json) != ''
                                THEN embedding_updated_at END) AS latest,
                       COUNT(*) AS total
                FROM rag_chunks
                """, (rs, rowNum) -> new EmbeddingFingerprint(
                rs.getInt("cnt"), rs.getString("latest"), rs.getInt("total")));
    }

    /** 全部切片的标题与正文（含没有向量的切片），用于构建关键词索引。 */
    public List<KeywordRow> findKeywordRows() {
        return jdbcTemplate.query(
                "SELECT chunk_id, title, text FROM rag_chunks ORDER BY chunk_id",
                (rs, rowNum) -> new KeywordRow(rs.getString("chunk_id"), rs.getString("title"), rs.getString("text")));
    }

    /** 按 chunk_id 批量取完整切片；已不存在的 id 不会出现在结果里。 */
    public List<RagChunk> findByChunkIds(java.util.Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT * FROM rag_chunks WHERE chunk_id IN (" + placeholders + ")",
                RAG_CHUNK_ROW_MAPPER,
                chunkIds.toArray());
    }

    /** 按业务键 resources.resource_id 过滤切片（related_resource_id 可为 NULL，过滤时不返回）。 */
    public List<RagChunk> findByRelatedResourceId(String relatedResourceId) {
        String sql = """
                SELECT * FROM rag_chunks
                WHERE related_resource_id = ?
                ORDER BY chunk_id
                """;
        return jdbcTemplate.query(sql, RAG_CHUNK_ROW_MAPPER, relatedResourceId);
    }

    /** 按切片主键 chunk_id 查找（如 rag_001），找不到返回 null。 */
    public RagChunk findById(String chunkId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM rag_chunks WHERE chunk_id = ?",
                    RAG_CHUNK_ROW_MAPPER, chunkId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按 chunk_id 幂等写入业务字段和 content_hash。
     * 不覆盖 embedding 字段：向量更新由 {@link #updateEmbedding} 单独负责，
     * 避免重复导入把已有向量误清空。
     */
    public int upsert(RagChunk chunk) {
        String sql = """
                INSERT INTO rag_chunks
                    (chunk_id, title, text, source_type, source_url,
                     source_file, page_or_section, related_resource_id,
                     created_at, content_hash, embedding_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 'PENDING'))
                ON CONFLICT(chunk_id) DO UPDATE SET
                    title = excluded.title,
                    text = excluded.text,
                    source_type = excluded.source_type,
                    source_url = excluded.source_url,
                    source_file = excluded.source_file,
                    page_or_section = excluded.page_or_section,
                    related_resource_id = excluded.related_resource_id,
                    created_at = excluded.created_at,
                    content_hash = excluded.content_hash
                """;
        return jdbcTemplate.update(sql,
                chunk.getChunkId(),
                chunk.getTitle(),
                chunk.getText(),
                chunk.getSourceType(),
                chunk.getSourceUrl(),
                chunk.getSourceFile(),
                chunk.getPageOrSection(),
                chunk.getRelatedResourceId(),
                chunk.getCreatedAt(),
                chunk.getContentHash(),
                chunk.getEmbeddingStatus());
    }

    public int updateEmbedding(String chunkId,
                               String embeddingJson,
                               String embeddingModel,
                               Integer dimensions,
                               String status,
                               String error,
                               String updatedAt) {
        return jdbcTemplate.update("""
                        UPDATE rag_chunks
                        SET embedding_json = ?,
                            embedding_model = ?,
                            embedding_dimensions = ?,
                            embedding_status = ?,
                            embedding_error = ?,
                            embedding_updated_at = ?
                        WHERE chunk_id = ?
                        """,
                embeddingJson,
                embeddingModel,
                dimensions,
                status,
                error,
                updatedAt,
                chunkId);
    }

    public void createTableIfNeeded() {
        String sql = """
                CREATE TABLE IF NOT EXISTS rag_chunks (
                  chunk_id              TEXT PRIMARY KEY,
                  title                 TEXT NOT NULL,
                  text                  TEXT NOT NULL,
                  source_type           TEXT CHECK(source_type IN ('web', 'pdf', 'json')),
                  source_url            TEXT,
                  source_file           TEXT,
                  page_or_section       TEXT,
                  related_resource_id   TEXT,
                  created_at            TEXT,
                  embedding_json        TEXT,
                  embedding_model       TEXT,
                  embedding_dimensions  INTEGER,
                  embedding_status      TEXT,
                  embedding_error       TEXT,
                  embedding_updated_at  TEXT,
                  content_hash          TEXT
                )
                """;
        jdbcTemplate.execute(sql);
        migrateSchema();

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_related ON rag_chunks(related_resource_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_embedding_status ON rag_chunks(embedding_status)");
    }

    /** 幂等迁移：老库逐列补齐 v0.0.4 的 embedding 字段。 */
    public void migrateSchema() {
        if (!hasColumn("embedding_json")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_json TEXT");
        }
        if (!hasColumn("embedding_model")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_model TEXT");
        }
        if (!hasColumn("embedding_dimensions")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_dimensions INTEGER");
        }
        if (!hasColumn("embedding_status")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_status TEXT");
        }
        if (!hasColumn("embedding_error")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_error TEXT");
        }
        if (!hasColumn("embedding_updated_at")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN embedding_updated_at TEXT");
        }
        if (!hasColumn("content_hash")) {
            jdbcTemplate.execute("ALTER TABLE rag_chunks ADD COLUMN content_hash TEXT");
        }
    }

    private boolean hasColumn(String columnName) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(rag_chunks)",
                (rs, rowNum) -> rs.getString("name"));
        return columns.stream().anyMatch(col -> col.equalsIgnoreCase(columnName));
    }
}
