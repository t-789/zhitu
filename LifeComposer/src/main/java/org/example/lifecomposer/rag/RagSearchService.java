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
 */
@Service
public class RagSearchService {

    private static final Logger LOG = LogManager.getLogger(RagSearchService.class);

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

        EmbeddingResult queryEmbedding;
        try {
            queryEmbedding = embeddingClient.embed(query);
        } catch (EmbeddingException e) {
            throw new RagSearchException("embedding unavailable: " + e.getMessage(), e);
        }

        float[] queryVector = RagIndex.normalize(toDoubles(queryEmbedding.vector()));
        String queryModel = queryEmbedding.model();
        RagIndex snapshot = currentIndex();

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
            if (similarity < ragConfig.getMinSimilarity()) {
                continue;
            }
            scored.add(new Scored(entry.chunkId(), similarity));
        }
        if (modelMismatch > 0 && snapshot.markModelMismatchLogged()) {
            LOG.warn("RAG: ignoring {} chunk(s) embedded with a different model than the query ({}); "
                    + "re-run the import to re-embed them", modelMismatch, queryModel);
        }

        scored.sort(Comparator
                .comparingDouble(Scored::similarity).reversed()
                .thenComparing(Scored::chunkId));
        List<Scored> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        return toHits(top);
    }

    /** Titles and text come from the database, so edits and deletions are always honoured. */
    private List<RagHit> toHits(List<Scored> top) {
        if (top.isEmpty()) {
            return List.of();
        }
        Map<String, RagChunk> byId = new HashMap<>();
        for (RagChunk chunk : ragChunkRepository.findByChunkIds(top.stream().map(Scored::chunkId).toList())) {
            byId.put(chunk.getChunkId(), chunk);
        }
        List<RagHit> hits = new ArrayList<>(top.size());
        for (Scored s : top) {
            RagChunk chunk = byId.get(s.chunkId());
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
                    s.similarity()));
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
            RagIndex rebuilt = RagIndex.build(ragChunkRepository.findEmbeddingRows(), latest, clock.getAsLong());
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
}
