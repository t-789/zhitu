package org.example.lifecomposer.rag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Entity.RagChunk;
import org.example.lifecomposer.Repository.RagChunkRepository;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingFingerprint;
import org.example.lifecomposer.config.RagConfig;
import org.example.lifecomposer.embedding.EmbeddingClient;
import org.example.lifecomposer.embedding.EmbeddingException;
import org.example.lifecomposer.embedding.EmbeddingResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * RAG retrieval over SQLite-stored JSON vectors. Chunks without a successful
 * embedding are excluded. Defaults: topK=5, min similarity=0.2, order by
 * similarity desc then chunk_id asc.
 *
 * <p>Vectors are cached in memory as a normalised {@link RagIndex}, so a search costs
 * one aggregate query plus a dot product per chunk instead of parsing every chunk's
 * JSON. The import CLI runs in a separate process and cannot notify this one, so each
 * search compares a cheap fingerprint of the stored embeddings and rebuilds the index
 * when it changed (with an age cap as a safety net, see
 * {@link RagConfig#getIndexMaxAgeSeconds()}).</p>
 *
 * <p>Only chunks embedded by the same model as the query are compared: two models can
 * share a dimension and still produce meaningless similarities.</p>
 *
 * <p>With {@code rag.hybridEnabled} (default) the vector ranking is fused with a BM25
 * keyword ranking, which finds exact names a small embedding model misses. If the
 * embedding service is down, keyword results are still returned.</p>
 */
@Service
public class RagSearchService {

    private static final Logger LOG = LogManager.getLogger(RagSearchService.class);
    /** Standard reciprocal-rank-fusion constant. */
    private static final int RRF_K = 60;

    private final EmbeddingClient embeddingClient;
    private final RagChunkRepository ragChunkRepository;
    private final RagConfig ragConfig;
    private final LongSupplier clock;

    private final Object rebuildLock = new Object();
    private volatile RagIndex index;

    // Two constructors exist (the second lets tests control the clock), so Spring
    // must be told which one to use.
    @Autowired
    public RagSearchService(EmbeddingClient embeddingClient,
                            RagChunkRepository ragChunkRepository,
                            RagConfig ragConfig) {
        this(embeddingClient, ragChunkRepository, ragConfig, System::currentTimeMillis);
    }

    RagSearchService(EmbeddingClient embeddingClient,
                     RagChunkRepository ragChunkRepository,
                     RagConfig ragConfig,
                     LongSupplier clock) {
        this.embeddingClient = embeddingClient;
        this.ragChunkRepository = ragChunkRepository;
        this.ragConfig = ragConfig;
        this.clock = clock;
    }

    public List<RagHit> search(String query, Integer requestedTopK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int topK = requestedTopK == null || requestedTopK <= 0
                ? ragConfig.getDefaultTopK()
                : Math.min(requestedTopK, ragConfig.getMaxTopK());
        boolean hybrid = ragConfig.isHybridEnabled();

        EmbeddingResult queryEmbedding = null;
        EmbeddingException embeddingFailure = null;
        try {
            queryEmbedding = embeddingClient.embed(query);
        } catch (EmbeddingException e) {
            if (!hybrid) {
                throw new RagSearchException("embedding unavailable: " + e.getMessage(), e);
            }
            embeddingFailure = e;
        }

        RagIndex snapshot = currentIndex();
        Map<String, Double> similarities = new HashMap<>();
        List<Scored> vectorRanking = queryEmbedding == null
                ? List.of()
                : rankByVector(snapshot, queryEmbedding, similarities);

        if (!hybrid) {
            return toHits(vectorRanking.stream().limit(topK)
                    .map(s -> new Ranked(s.chunkId(), s.similarity(), s.similarity(), "vector")).toList());
        }

        int candidates = Math.max(ragConfig.getHybridCandidateK(), topK);
        List<KeywordIndex.Match> keywordRanking = snapshot.keywords().search(query, candidates);
        if (embeddingFailure != null) {
            if (keywordRanking.isEmpty()) {
                // Without vectors "no keyword match" proves nothing: report the outage
                // instead of letting the model conclude the knowledge base has no answer.
                throw new RagSearchException("embedding unavailable: " + embeddingFailure.getMessage(),
                        embeddingFailure);
            }
            LOG.warn("RAG: embedding unavailable, answering from keyword search only");
        }
        return toHits(fuse(vectorRanking, keywordRanking, similarities, candidates).stream().limit(topK).toList());
    }

    /** Vector ranking above {@code minSimilarity}; records every comparable similarity for the fused result. */
    private List<Scored> rankByVector(RagIndex snapshot, EmbeddingResult queryEmbedding,
                                      Map<String, Double> similarities) {
        float[] queryVector = RagIndex.normalize(toDoubles(queryEmbedding.vector()));
        String queryModel = queryEmbedding.model();

        List<Scored> scored = new ArrayList<>();
        int modelMismatch = 0;
        for (RagIndex.Entry entry : snapshot.entries()) {
            if (isModelMismatch(entry.model(), queryModel)) {
                modelMismatch++;
                continue;
            }
            if (entry.vector().length != queryVector.length) {
                continue;
            }
            double similarity = RagIndex.dot(queryVector, entry.vector());
            similarities.put(entry.chunkId(), similarity);
            if (similarity >= ragConfig.getMinSimilarity()) {
                scored.add(new Scored(entry.chunkId(), similarity));
            }
        }
        if (modelMismatch > 0 && snapshot.markModelMismatchLogged()) {
            LOG.warn("RAG: ignoring {} chunk(s) embedded with a different model than the query ({}); "
                    + "re-run the import to re-embed them", modelMismatch, queryModel);
        }
        scored.sort(Comparator
                .comparingDouble(Scored::similarity).reversed()
                .thenComparing(Scored::chunkId));
        return scored;
    }

    /**
     * Reciprocal rank fusion: a chunk's score is the sum of 1 / (60 + rank) over the
     * rankings it appears in. It needs no calibration between cosine similarity and
     * BM25, which live on unrelated scales.
     */
    private List<Ranked> fuse(List<Scored> vectorRanking, List<KeywordIndex.Match> keywordRanking,
                              Map<String, Double> similarities, int candidates) {
        Map<String, Double> fused = new HashMap<>();
        Map<String, Boolean> byVector = new HashMap<>();
        Map<String, Boolean> byKeyword = new HashMap<>();
        for (int i = 0; i < Math.min(candidates, vectorRanking.size()); i++) {
            String id = vectorRanking.get(i).chunkId();
            fused.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            byVector.put(id, true);
        }
        for (int i = 0; i < Math.min(candidates, keywordRanking.size()); i++) {
            String id = keywordRanking.get(i).chunkId();
            fused.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            byKeyword.put(id, true);
        }
        List<Ranked> ranked = new ArrayList<>(fused.size());
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            String id = e.getKey();
            String matchedBy = byVector.containsKey(id) && byKeyword.containsKey(id) ? "both"
                    : byVector.containsKey(id) ? "vector" : "keyword";
            ranked.add(new Ranked(id, similarities.get(id), e.getValue(), matchedBy));
        }
        ranked.sort(Comparator
                .comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparing(Ranked::similarity,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .thenComparing(Ranked::chunkId));
        return ranked;
    }

    /** Titles and text come from the database, so edits and deletions are always honoured. */
    private List<RagHit> toHits(List<Ranked> top) {
        if (top.isEmpty()) {
            return List.of();
        }
        Map<String, RagChunk> byId = new HashMap<>();
        for (RagChunk chunk : ragChunkRepository.findByChunkIds(top.stream().map(Ranked::chunkId).toList())) {
            byId.put(chunk.getChunkId(), chunk);
        }
        List<RagHit> hits = new ArrayList<>(top.size());
        for (Ranked r : top) {
            RagChunk chunk = byId.get(r.chunkId());
            if (chunk == null) {
                continue; // deleted since the index was built
            }
            hits.add(new RagHit(
                    chunk.getChunkId(),
                    chunk.getTitle(),
                    chunk.getText(),
                    chunk.getSourceType(),
                    chunk.getSourceUrl(),
                    chunk.getSourceFile(),
                    chunk.getPageOrSection(),
                    chunk.getRelatedResourceId(),
                    r.similarity(),
                    r.score(),
                    r.matchedBy()));
        }
        return hits;
    }

    private RagIndex currentIndex() {
        EmbeddingFingerprint latest = ragChunkRepository.embeddingFingerprint();
        long maxAgeMillis = ragConfig.getIndexMaxAgeSeconds() * 1000L;
        RagIndex current = index;
        if (current != null && current.isCurrent(latest, clock.getAsLong(), maxAgeMillis)) {
            return current;
        }
        synchronized (rebuildLock) {
            current = index;
            if (current != null && current.isCurrent(latest, clock.getAsLong(), maxAgeMillis)) {
                return current;
            }
            // The fingerprint was read before the rows: if the data moves on in between,
            // the next search sees a different fingerprint and rebuilds again.
            RagIndex rebuilt = RagIndex.build(ragChunkRepository.findEmbeddingRows(), ragChunkRepository.findKeywordRows(),
                    latest, clock.getAsLong());
            index = rebuilt;
            return rebuilt;
        }
    }

    /**
     * Chunks with no recorded model (imported before the model was stored) are still
     * compared by dimension; a recorded model that differs from the query's is skipped.
     */
    private static boolean isModelMismatch(String chunkModel, String queryModel) {
        if (chunkModel == null || chunkModel.isBlank() || queryModel == null || queryModel.isBlank()) {
            return false;
        }
        return !chunkModel.equals(queryModel);
    }

    private static double[] toDoubles(List<Double> vector) {
        double[] out = new double[vector.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vector.get(i);
        }
        return out;
    }

    private record Scored(String chunkId, double similarity) {
    }

    /** A chunk on its way to becoming a hit; {@code similarity} is null when it has no comparable vector. */
    private record Ranked(String chunkId, Double similarity, double score, String matchedBy) {
    }
}
