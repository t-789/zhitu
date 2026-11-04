package org.example.lifecomposer.rag;

/**
 * One ranked RAG chunk returned to the agent tool layer.
 *
 * @param similarity cosine similarity to the query, or {@code null} when the chunk has
 *                   no comparable vector (no embedding, or the embedding service was down)
 * @param score      the value the hit was ranked by: the cosine similarity in pure
 *                   vector mode, the reciprocal-rank-fusion score in hybrid mode
 * @param matchedBy  {@code vector}, {@code keyword} or {@code both}
 */
public record RagHit(
        String chunkId,
        String title,
        String text,
        String sourceType,
        String sourceUrl,
        String sourceFile,
        String pageOrSection,
        String relatedResourceId,
        Double similarity,
        double score,
        String matchedBy
) {
}
