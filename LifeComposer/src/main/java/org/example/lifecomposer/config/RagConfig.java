package org.example.lifecomposer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG retrieval defaults. v0.0.4 uses SQLite JSON vectors plus application-side
 * cosine similarity; no external vector database.
 */
@Configuration
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagConfig {

    /** Default number of chunks returned by search_rag. */
    private int defaultTopK = 5;

    /** Upper bound accepted from tool arguments. */
    private int maxTopK = 20;

    /** Minimum cosine similarity (0..1, cosine mapped from [-1,1]). */
    private double minSimilarity = 0.2;

    /**
     * Safety net for the in-memory vector index. The index is rebuilt as soon as the
     * set of embeddings changes (checked with one cheap query per search); this caps
     * how long a change the check could miss would stay invisible. 0 or less disables it.
     */
    private int indexMaxAgeSeconds = 300;
}
