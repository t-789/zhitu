package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.RagChunk;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingFingerprint;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The three lightweight queries behind the RAG vector cache, against real SQLite. */
@SpringBootTest
@ActiveProfiles("test")
class RagChunkEmbeddingQueriesTest {

    @Autowired
    private RagChunkRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM rag_chunks");
    }

    @Test
    void embeddingRowsOnlyIncludeChunksThatHaveAVector() {
        insert("q_001", "资料一");
        insert("q_002", "资料二");
        insert("q_003", "资料三");
        repository.updateEmbedding("q_001", "[1.0,0.0]", "m1", 2, "SUCCESS", null, "2026-11-04T10:00:00Z");
        repository.updateEmbedding("q_002", null, "m1", null, "FAILED", "boom", "2026-11-04T10:00:01Z");

        List<EmbeddingRow> rows = repository.findEmbeddingRows();

        assertEquals(1, rows.size());
        assertEquals("q_001", rows.get(0).chunkId());
        assertEquals("[1.0,0.0]", rows.get(0).embeddingJson());
        assertEquals("m1", rows.get(0).embeddingModel());
        assertEquals(2, rows.get(0).dimensions());
    }

    @Test
    void fingerprintChangesWhenAnEmbeddingIsAddedOrRewritten() {
        insert("q_001", "资料一");
        insert("q_002", "资料二");
        EmbeddingFingerprint empty = repository.embeddingFingerprint();
        assertEquals(0, empty.count());
        assertNull(empty.latestUpdate());
        assertEquals(2, empty.totalChunks(), "chunks without a vector still count: keyword search covers them");

        repository.updateEmbedding("q_001", "[1.0]", "m1", 1, "SUCCESS", null, "2026-11-04T10:00:00.100Z");
        EmbeddingFingerprint one = repository.embeddingFingerprint();
        assertEquals(1, one.count());
        assertNotEquals(empty, one);

        repository.updateEmbedding("q_001", "[0.5]", "m1", 1, "SUCCESS", null, "2026-11-04T10:00:00.900Z");
        EmbeddingFingerprint rewritten = repository.embeddingFingerprint();
        assertEquals(1, rewritten.count());
        assertNotEquals(one, rewritten);

        repository.updateEmbedding("q_002", "[0.5]", "m1", 1, "SUCCESS", null, "2026-11-04T10:00:01.000Z");
        assertEquals(2, repository.embeddingFingerprint().count());
    }

    @Test
    void findByChunkIdsReturnsOnlyExistingRowsAndToleratesEmptyInput() {
        insert("q_001", "资料一");
        insert("q_002", "资料二");

        List<RagChunk> found = repository.findByChunkIds(List.of("q_002", "q_missing", "q_001"));

        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(c -> "资料一".equals(c.getTitle())));
        assertTrue(repository.findByChunkIds(List.of()).isEmpty());
        assertTrue(repository.findByChunkIds(null).isEmpty());
    }

    @Test
    void keywordRowsCoverEveryChunkIncludingUnembeddedOnes() {
        insert("q_001", "资料一");
        insert("q_002", "资料二");
        repository.updateEmbedding("q_001", "[1.0]", "m1", 1, "SUCCESS", null, "2026-11-04T10:00:00Z");

        List<RagChunkRepository.KeywordRow> rows = repository.findKeywordRows();

        assertEquals(List.of("q_001", "q_002"), rows.stream().map(RagChunkRepository.KeywordRow::chunkId).toList());
        assertEquals("资料二", rows.get(1).title());
        assertEquals("正文 q_002", rows.get(1).text());
    }

    private void insert(String id, String title) {
        RagChunk chunk = new RagChunk();
        chunk.setChunkId(id);
        chunk.setTitle(title);
        chunk.setText("正文 " + id);
        chunk.setSourceType("json");
        chunk.setCreatedAt("2026-11-04 10:00:00");
        chunk.setContentHash("h-" + id);
        repository.upsert(chunk);
    }
}
