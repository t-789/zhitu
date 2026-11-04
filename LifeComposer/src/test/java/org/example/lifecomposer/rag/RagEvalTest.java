package org.example.lifecomposer.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lifecomposer.Entity.RagChunk;
import org.example.lifecomposer.Repository.RagChunkRepository;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingFingerprint;
import org.example.lifecomposer.Repository.RagChunkRepository.EmbeddingRow;
import org.example.lifecomposer.Repository.RagChunkRepository.KeywordRow;
import org.example.lifecomposer.config.EmbeddingConfig;
import org.example.lifecomposer.config.RagConfig;
import org.example.lifecomposer.embedding.EmbeddingClient;
import org.example.lifecomposer.embedding.EmbeddingException;
import org.example.lifecomposer.embedding.EmbeddingResult;
import org.example.lifecomposer.embedding.OllamaEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline retrieval evaluation: Recall@5 and MRR of pure vector, pure keyword and
 * hybrid search on {@code 样例/rag/test_questions.json}. Use it to judge changes to
 * {@code minSimilarity}, the chunking, or the hybrid parameters.
 *
 * <p>Needs a local Ollama with the configured embedding model and the (git-ignored)
 * sample files, so it only runs when asked:</p>
 * <pre>./mvnw test -Dtest=RagEvalTest -Drag.eval=true [-Drag.eval.dir=../样例/rag]</pre>
 *
 * <p>It builds its own in-memory index from {@code chunks.json}, embedding the chunk
 * text exactly as the import CLI does, so it neither reads nor writes any database.</p>
 */
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
class RagEvalTest {

    private static final int K = 5;

    @Test
    void compareRetrievalModes() throws Exception {
        Path dir = Path.of(System.getProperty("rag.eval.dir", "../样例/rag"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode chunkNodes = mapper.readTree(Files.readString(dir.resolve("chunks.json")));
        JsonNode questionNodes = mapper.readTree(Files.readString(dir.resolve("test_questions.json")));

        EmbeddingClient embedding = new OllamaEmbeddingClient(new EmbeddingConfig());
        List<RagChunk> chunks = new ArrayList<>();
        List<EmbeddingRow> embeddingRows = new ArrayList<>();
        List<KeywordRow> keywordRows = new ArrayList<>();
        for (JsonNode node : chunkNodes) {
            RagChunk chunk = new RagChunk();
            chunk.setChunkId(node.get("chunk_id").asText());
            chunk.setTitle(node.path("title").asText(""));
            chunk.setText(node.path("text").asText(""));
            chunks.add(chunk);
            keywordRows.add(new KeywordRow(chunk.getChunkId(), chunk.getTitle(), chunk.getText()));
            EmbeddingResult result = embedding.embed(chunk.getText());
            embeddingRows.add(new EmbeddingRow(chunk.getChunkId(), mapper.writeValueAsString(result.vector()),
                    result.model(), result.dimensions()));
        }

        RagChunkRepository repository = mock(RagChunkRepository.class);
        when(repository.embeddingFingerprint())
                .thenReturn(new EmbeddingFingerprint(embeddingRows.size(), "eval", chunks.size()));
        when(repository.findEmbeddingRows()).thenReturn(embeddingRows);
        when(repository.findKeywordRows()).thenReturn(keywordRows);
        when(repository.findByChunkIds(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            return chunks.stream().filter(c -> ids.contains(c.getChunkId())).toList();
        });

        EmbeddingClient down = mock(EmbeddingClient.class);
        when(down.embed(org.mockito.ArgumentMatchers.anyString())).thenThrow(new EmbeddingException("keyword-only run"));

        Function<Boolean, RagConfig> config = hybrid -> {
            RagConfig c = new RagConfig();
            c.setHybridEnabled(hybrid);
            return c;
        };
        Map<String, RagSearchService> modes = new java.util.LinkedHashMap<>();
        modes.put("vector", new RagSearchService(embedding, repository, config.apply(false)));
        modes.put("keyword", new RagSearchService(down, repository, config.apply(true)));
        modes.put("hybrid", new RagSearchService(embedding, repository, config.apply(true)));

        System.out.printf("%n== RAG retrieval evaluation: %d chunks, %d questions, k=%d ==%n",
                chunks.size(), questionNodes.size(), K);
        System.out.printf("%-8s %10s %10s %10s%n", "mode", "Recall@" + K, "Hit@" + K, "MRR");
        StringBuilder detail = new StringBuilder();
        Map<String, StringBuilder> perQuestion = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, RagSearchService> mode : modes.entrySet()) {
            double recall = 0, hit = 0, mrr = 0;
            for (JsonNode q : questionNodes) {
                List<String> expected = new ArrayList<>();
                q.get("expected_chunk_ids").forEach(n -> expected.add(n.asText()));
                List<String> ranked;
                try {
                    ranked = mode.getValue().search(q.get("question").asText(), K).stream().map(RagHit::chunkId).toList();
                } catch (RagSearchException e) {
                    ranked = List.of(); // keyword-only run with no keyword match
                }
                long found = ranked.stream().filter(expected::contains).count();
                perQuestion.computeIfAbsent(q.get("id").asText(), k -> new StringBuilder())
                        .append(String.format(" %s=%d/%d", mode.getKey(), found, expected.size()));
                recall += (double) found / expected.size();
                hit += found > 0 ? 1 : 0;
                for (int i = 0; i < ranked.size(); i++) {
                    if (expected.contains(ranked.get(i))) {
                        mrr += 1.0 / (i + 1);
                        break;
                    }
                }
                if (found == 0) {
                    detail.append(String.format("  [%s] MISS %s %s -> got %s%n", mode.getKey(),
                            q.get("id").asText(), q.get("question").asText(), ranked));
                }
            }
            int n = questionNodes.size();
            System.out.printf("%-8s %10.3f %10.3f %10.3f%n", mode.getKey(), recall / n, hit / n, mrr / n);
        }
        System.out.println("Per question (expected chunks found in top " + K + "):");
        perQuestion.forEach((id, line) -> System.out.println("  " + id + line));
        System.out.println(detail.length() == 0 ? "(no question without a hit in any mode)" : "Questions with no hit:\n" + detail);
    }
}
