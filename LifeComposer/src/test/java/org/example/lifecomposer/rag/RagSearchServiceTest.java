package org.example.lifecomposer.rag;

import org.example.lifecomposer.Entity.RagChunk;
import org.example.lifecomposer.Repository.RagChunkRepository;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingFingerprint;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingRow;
import org.example.lifecomposer.Repository.RagChunkRepository.KeywordRow;
import org.example.lifecomposer.config.RagConfig;
import org.example.lifecomposer.embedding.EmbeddingClient;
import org.example.lifecomposer.embedding.EmbeddingException;
import org.example.lifecomposer.embedding.EmbeddingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagSearchServiceTest {

    private static final String MODEL = "test-model";

    private EmbeddingClient embeddingClient;
    private RagChunkRepository repository;
    private RagConfig config;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.getModel()).thenReturn(MODEL);
        when(embeddingClient.isEnabled()).thenReturn(true);
        repository = mock(RagChunkRepository.class);
        when(repository.embeddingFingerprint()).thenReturn(new EmbeddingFingerprint(0, null, 0));
        config = new RagConfig();
        config.setDefaultTopK(5);
        config.setMinSimilarity(0.2);
    }

    private RagSearchService service() {
        return new RagSearchService(embeddingClient, repository, config, now::get);
    }

    private void queryVector(Double... values) {
        when(embeddingClient.embed(anyString())).thenReturn(new EmbeddingResult(List.of(values), MODEL, values.length));
    }

    /** Index rows and the full rows the service reads back for hits. */
    private void stored(EmbeddingRow... rows) {
        when(repository.findEmbeddingRows()).thenReturn(List.of(rows));
        when(repository.findByChunkIds(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            List<RagChunk> chunks = new ArrayList<>();
            for (EmbeddingRow row : rows) {
                if (ids.contains(row.chunkId())) {
                    chunks.add(chunk(row.chunkId()));
                }
            }
            return chunks;
        });
    }

    @Test
    void sortsBySimilarityThenChunkIdAndFiltersLowScoresAndBrokenVectors() {
        queryVector(1.0, 0.0);
        stored(row("rag_002", "[0.9,0.1]"), row("rag_003", "[0.0,1.0]"),
                row("rag_004", "not-json"), row("rag_001", "[1.0,0.0]"));

        List<RagHit> hits = service().search("数学建模", null);

        assertEquals(2, hits.size());
        assertEquals("rag_001", hits.get(0).chunkId());
        assertEquals("rag_002", hits.get(1).chunkId());
        assertTrue(hits.get(0).similarity() >= hits.get(1).similarity());
        assertEquals("title-rag_001", hits.get(0).title());
    }

    @Test
    void respectsRequestedTopKAndMaximum() {
        queryVector(1.0);
        stored(row("rag_001", "[1.0]"), row("rag_002", "[1.0]"), row("rag_003", "[1.0]"));
        config.setMaxTopK(2);

        assertEquals(2, service().search("query", 10).size());
    }

    @Test
    void equalScoresAreOrderedByChunkId() {
        queryVector(1.0);
        stored(row("rag_003", "[2.0]"), row("rag_001", "[1.0]"), row("rag_002", "[5.0]"));

        List<String> ids = service().search("q", 5).stream().map(RagHit::chunkId).toList();

        assertEquals(List.of("rag_001", "rag_002", "rag_003"), ids);
    }

    @Test
    void parsesAndNormalisesVectorsOnlyOncePerIndexBuild() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"));
        RagSearchService service = service();

        service.search("a", null);
        service.search("b", null);
        service.search("c", null);

        verify(repository, times(1)).findEmbeddingRows();
        verify(repository, times(3)).embeddingFingerprint();
    }

    @Test
    void rebuildsWhenTheStoredEmbeddingsChange() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"));
        RagSearchService service = service();
        service.search("a", null);

        // The import CLI (another process) added an embedding.
        when(repository.embeddingFingerprint()).thenReturn(new EmbeddingFingerprint(1, "2026-11-04T10:00:00Z", 1));
        service.search("b", null);
        service.search("c", null);

        verify(repository, times(2)).findEmbeddingRows();
    }

    @Test
    void rebuildsAfterTheMaximumAgeEvenIfTheFingerprintLooksUnchanged() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"));
        config.setIndexMaxAgeSeconds(60);
        RagSearchService service = service();

        service.search("a", null);
        now.addAndGet(59_000);
        service.search("b", null);
        verify(repository, times(1)).findEmbeddingRows();

        now.addAndGet(2_000);
        service.search("c", null);
        verify(repository, times(2)).findEmbeddingRows();
    }

    @Test
    void ignoresChunksEmbeddedWithADifferentModelEvenWithTheSameDimension() {
        queryVector(1.0, 0.0);
        stored(row("rag_old", "[1.0,0.0]", "other-model"),
                row("rag_new", "[0.9,0.1]", MODEL));

        List<String> ids = service().search("q", null).stream().map(RagHit::chunkId).toList();

        assertEquals(List.of("rag_new"), ids);
    }

    @Test
    void chunksWithoutARecordedModelAreStillComparedByDimension() {
        queryVector(1.0, 0.0);
        stored(row("rag_legacy", "[1.0,0.0]", null), row("rag_wrongdim", "[1.0,0.0,0.0]", null));

        List<String> ids = service().search("q", null).stream().map(RagHit::chunkId).toList();

        assertEquals(List.of("rag_legacy"), ids);
    }

    @Test
    void chunkDeletedAfterTheIndexWasBuiltIsDroppedWithoutError() {
        queryVector(1.0);
        stored(row("rag_001", "[1.0]"), row("rag_002", "[0.9]"));
        when(repository.findByChunkIds(anyCollection())).thenReturn(List.of(chunk("rag_002")));

        List<String> ids = service().search("q", null).stream().map(RagHit::chunkId).toList();

        assertEquals(List.of("rag_002"), ids);
    }

    @Test
    void emptyStoreYieldsNoHitsAndNoRowLookup() {
        queryVector(1.0);
        stored();

        assertTrue(service().search("q", null).isEmpty());
        verify(repository, times(0)).findByChunkIds(anyCollection());
    }

    // ---- hybrid retrieval -------------------------------------------------

    private void keywords(KeywordRow... rows) {
        when(repository.findKeywordRows()).thenReturn(List.of(rows));
    }

    private static KeywordRow kw(String id, String title, String text) {
        return new KeywordRow(id, title, text);
    }

    @Test
    void keywordRecallFindsANameTheVectorsMiss() {
        queryVector(1.0, 0.0);
        // Both vectors are orthogonal to the query, so the vector ranking is empty.
        stored(row("rag_001", "[0.0,1.0]"), row("rag_002", "[0.0,1.0]"));
        keywords(kw("rag_001", "竞赛概述", "全国大学生数学建模竞赛面向在校生"),
                kw("rag_002", "综测加分规则", "参加竞赛可以获得综测加分"));

        List<RagHit> hits = service().search("综测", null);

        assertEquals(List.of("rag_002"), hits.stream().map(RagHit::chunkId).toList());
        assertEquals("keyword", hits.get(0).matchedBy());
        assertNotNull(hits.get(0).similarity(), "the chunk has a vector, so its similarity is still reported");
    }

    @Test
    void chunkFoundByBothRankingsOutranksChunksFoundByOne() {
        queryVector(1.0, 0.0);
        stored(row("rag_a", "[1.0,0.0]"), row("rag_b", "[0.9,0.1]"), row("rag_c", "[0.0,1.0]"));
        keywords(kw("rag_a", "无关", "无关内容"),
                kw("rag_b", "蓝桥杯备赛", "蓝桥杯需要提前准备"),
                kw("rag_c", "蓝桥杯", "蓝桥杯蓝桥杯蓝桥杯"));

        List<RagHit> hits = service().search("蓝桥杯", 5);

        // rag_b: vector rank 2 + keyword rank 2 ; rag_a: vector rank 1 only ; rag_c: keyword rank 1 only
        assertEquals("rag_b", hits.get(0).chunkId());
        assertEquals("both", hits.get(0).matchedBy());
        assertEquals(java.util.Set.of("rag_a", "rag_c"),
                hits.stream().skip(1).map(RagHit::chunkId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void chunkWithoutAnEmbeddingIsStillFoundByKeyword() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"));
        keywords(kw("rag_001", "无关", "无关内容"), kw("rag_009", "保研政策", "保研加分细则"));
        when(repository.findByChunkIds(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            return ids.stream().map(id -> chunk(String.valueOf(id))).toList();
        });

        List<RagHit> hits = service().search("保研", 5);

        RagHit unembedded = hits.stream().filter(h -> h.chunkId().equals("rag_009")).findFirst().orElseThrow();
        assertEquals("keyword", unembedded.matchedBy());
        assertNull(unembedded.similarity());
    }

    @Test
    void embeddingOutageFallsBackToKeywordSearch() {
        when(embeddingClient.embed(anyString())).thenThrow(new EmbeddingException("ollama down"));
        stored(row("rag_001", "[1.0,0.0]"));
        keywords(kw("rag_001", "综测加分", "综测加分规则"));

        List<RagHit> hits = service().search("综测", null);

        assertEquals(1, hits.size());
        assertEquals("keyword", hits.get(0).matchedBy());
    }

    @Test
    void embeddingOutageWithNoKeywordMatchIsReportedAsUnavailable() {
        when(embeddingClient.embed(anyString())).thenThrow(new EmbeddingException("ollama down"));
        stored(row("rag_001", "[1.0,0.0]"));
        keywords(kw("rag_001", "综测加分", "综测加分规则"));

        // "nothing matched" would be indistinguishable from "the knowledge base has no answer".
        assertThrows(RagSearchException.class, () -> service().search("完全不相干的问题", null));
    }

    @Test
    void disablingHybridRestoresPureVectorSearch() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"), row("rag_002", "[0.0,1.0]"));
        keywords(kw("rag_002", "综测", "综测"));
        config.setHybridEnabled(false);

        List<RagHit> hits = service().search("综测", null);

        assertEquals(List.of("rag_001"), hits.stream().map(RagHit::chunkId).toList());
        assertEquals("vector", hits.get(0).matchedBy());
        assertEquals(hits.get(0).similarity(), hits.get(0).score(), 1e-9);
        // and an embedding outage is an error again, as before
        when(embeddingClient.embed(anyString())).thenThrow(new EmbeddingException("down"));
        assertThrows(RagSearchException.class, () -> service().search("综测", null));
    }

    @Test
    void rebuildsWhenOnlyAnUnembeddedChunkWasAdded() {
        queryVector(1.0, 0.0);
        stored(row("rag_001", "[1.0,0.0]"));
        RagSearchService service = service();
        service.search("a", null);

        when(repository.embeddingFingerprint()).thenReturn(new EmbeddingFingerprint(0, null, 1));
        service.search("b", null);

        verify(repository, times(2)).findKeywordRows();
    }

    @Test
    void normalisedDotProductMatchesPlainCosineRanking() {
        Random random = new Random(42);
        int dim = 64;
        double[] query = randomVector(random, dim);
        queryVector(boxed(query));

        List<EmbeddingRow> rows = new ArrayList<>();
        List<double[]> vectors = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double[] v = randomVector(random, dim);
            vectors.add(v);
            rows.add(row(String.format("rag_%03d", i), json(v)));
        }
        stored(rows.toArray(new EmbeddingRow[0]));
        config.setMinSimilarity(-1.0);
        config.setMaxTopK(40);

        List<RagHit> hits = service().search("q", 40);

        assertEquals(40, hits.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            expected.add(String.format("rag_%03d", i));
        }
        expected.sort((a, b) -> {
            int cmp = Double.compare(cosine(query, vectors.get(Integer.parseInt(b.substring(4)))),
                    cosine(query, vectors.get(Integer.parseInt(a.substring(4)))));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        assertEquals(expected, hits.stream().map(RagHit::chunkId).toList());
        for (RagHit hit : hits) {
            assertEquals(cosine(query, vectors.get(Integer.parseInt(hit.chunkId().substring(4)))),
                    hit.similarity(), 1e-5);
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double[] randomVector(Random random, int dim) {
        double[] v = new double[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = random.nextGaussian();
        }
        return v;
    }

    private static Double[] boxed(double[] v) {
        Double[] out = new Double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i];
        }
        return out;
    }

    private static String json(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append("]").toString();
    }

    private static EmbeddingRow row(String id, String embeddingJson) {
        return row(id, embeddingJson, MODEL);
    }

    private static EmbeddingRow row(String id, String embeddingJson, String model) {
        return new EmbeddingRow(id, embeddingJson, model, null);
    }

    private static RagChunk chunk(String id) {
        RagChunk chunk = new RagChunk();
        chunk.setChunkId(id);
        chunk.setTitle("title-" + id);
        chunk.setText("text");
        return chunk;
    }
}
