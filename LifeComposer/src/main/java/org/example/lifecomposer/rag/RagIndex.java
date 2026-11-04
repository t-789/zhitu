package org.example.lifecomposer.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingFingerprint;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingRow;
import org.example.lifecomposer.Repository.RagChunkRepository.KeywordRow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable in-memory snapshot of the chunk embeddings. Vectors are normalised once
 * at build time, so cosine similarity at query time is a plain dot product and no
 * JSON is parsed per search. Only ids, model names and vectors live here; titles and
 * text are read from the database for the few hits that are returned, so edited or
 * deleted chunks are never served from a stale copy.
 */
final class RagIndex {

    private static final Logger LOG = LogManager.getLogger(RagIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Entry(String chunkId, String model, float[] vector) {
    }

    private final List<Entry> entries;
    private final KeywordIndex keywords;
    private final EmbeddingFingerprint fingerprint;
    private final long builtAtMillis;
    private final AtomicBoolean modelMismatchLogged = new AtomicBoolean(false);

    private RagIndex(List<Entry> entries, KeywordIndex keywords, EmbeddingFingerprint fingerprint,
                     long builtAtMillis) {
        this.entries = List.copyOf(entries);
        this.keywords = keywords;
        this.fingerprint = fingerprint;
        this.builtAtMillis = builtAtMillis;
    }

    static RagIndex build(List<EmbeddingRow> rows, List<KeywordRow> keywordRows,
                          EmbeddingFingerprint fingerprint, long nowMillis) {
        List<Entry> entries = new ArrayList<>(rows.size());
        int invalid = 0;
        int legacyWithoutModel = 0;
        for (EmbeddingRow row : rows) {
            float[] vector = parse(row.embeddingJson());
            if (vector == null) {
                invalid++;
                continue;
            }
            if (row.embeddingModel() == null || row.embeddingModel().isBlank()) {
                legacyWithoutModel++;
            }
            entries.add(new Entry(row.chunkId(), row.embeddingModel(), normalize(vector)));
        }
        if (invalid > 0) {
            LOG.warn("RAG index: skipped {} chunk(s) with unreadable embedding JSON", invalid);
        }
        if (legacyWithoutModel > 0) {
            LOG.warn("RAG index: {} chunk(s) have no embedding_model recorded; they are compared by dimension only",
                    legacyWithoutModel);
        }
        LOG.info("RAG index built: {} vector(s), {} chunk(s) for keyword search", entries.size(), keywordRows.size());
        return new RagIndex(entries, KeywordIndex.build(keywordRows), fingerprint, nowMillis);
    }

    List<Entry> entries() {
        return entries;
    }

    KeywordIndex keywords() {
        return keywords;
    }

    /** True while this snapshot still describes the database and is not older than {@code maxAgeMillis}. */
    boolean isCurrent(EmbeddingFingerprint latest, long nowMillis, long maxAgeMillis) {
        if (!fingerprint.equals(latest)) {
            return false;
        }
        return maxAgeMillis <= 0 || nowMillis - builtAtMillis < maxAgeMillis;
    }

    /** Returns true only the first time, so a model mismatch is logged once per snapshot. */
    boolean markModelMismatchLogged() {
        return modelMismatchLogged.compareAndSet(false, true);
    }

    static float[] normalize(double[] vector) {
        return normalize(toFloat(vector));
    }

    static float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        float[] out = new float[vector.length];
        if (sum == 0.0) {
            return out; // zero vector: every dot product is 0, matching the old cosine == 0
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    static float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static float[] toFloat(double[] vector) {
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) vector[i];
        }
        return out;
    }

    private static float[] parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return toFloat(MAPPER.readValue(json, double[].class));
        } catch (Exception e) {
            return null;
        }
    }
}
