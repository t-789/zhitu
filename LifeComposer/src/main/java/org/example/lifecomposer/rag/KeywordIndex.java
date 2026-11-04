package org.example.lifecomposer.rag;

import org.example.lifecomposer.Repository.RagChunkRepository.KeywordRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory BM25 index over chunk title + text. It needs no dictionary: Han
 * characters are indexed as overlapping character pairs (a lone character stays a
 * single token), Latin letters and digits as lower-cased words. That makes two-character
 * names such as "综测" or "蓝桥" searchable, which SQLite's FTS5 trigram tokenizer
 * cannot do. Chunks without an embedding are indexed too, so keyword recall keeps
 * working when the embedding service is down.
 */
final class KeywordIndex {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    record Match(String chunkId, double score) {
    }

    private record Posting(int doc, int termFrequency) {
    }

    private final String[] chunkIds;
    private final int[] docLength;
    private final double averageLength;
    private final Map<String, List<Posting>> postings;

    private KeywordIndex(String[] chunkIds, int[] docLength, double averageLength,
                         Map<String, List<Posting>> postings) {
        this.chunkIds = chunkIds;
        this.docLength = docLength;
        this.averageLength = averageLength;
        this.postings = postings;
    }

    static KeywordIndex build(List<KeywordRow> rows) {
        int n = rows.size();
        String[] ids = new String[n];
        int[] lengths = new int[n];
        Map<String, List<Posting>> postings = new HashMap<>();
        long totalLength = 0;
        for (int doc = 0; doc < n; doc++) {
            KeywordRow row = rows.get(doc);
            ids[doc] = row.chunkId();
            // The title is the most concise statement of what a chunk is about: count it twice.
            List<String> tokens = tokenize(nullToEmpty(row.title()));
            tokens.addAll(new ArrayList<>(tokens));
            tokens.addAll(tokenize(nullToEmpty(row.text())));
            lengths[doc] = tokens.size();
            totalLength += tokens.size();

            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens) {
                counts.merge(token, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Posting(doc, e.getValue()));
            }
        }
        double average = n == 0 ? 0.0 : (double) totalLength / n;
        return new KeywordIndex(ids, lengths, average, postings);
    }

    /** Highest BM25 scores first, ties by chunk id; chunks sharing no term with the query are omitted. */
    List<Match> search(String query, int limit) {
        if (query == null || query.isBlank() || chunkIds.length == 0 || limit <= 0) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>(tokenize(query));
        double[] scores = new double[chunkIds.length];
        int n = chunkIds.length;
        for (String term : terms) {
            List<Posting> list = postings.get(term);
            if (list == null) {
                continue;
            }
            int df = list.size();
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (Posting p : list) {
                double lengthNorm = 1.0 - B + B * (averageLength == 0 ? 1.0 : docLength[p.doc()] / averageLength);
                scores[p.doc()] += idf * (p.termFrequency() * (K1 + 1.0)) / (p.termFrequency() + K1 * lengthNorm);
            }
        }
        List<Match> matches = new ArrayList<>();
        for (int doc = 0; doc < n; doc++) {
            if (scores[doc] > 0.0) {
                matches.add(new Match(chunkIds[doc], scores[doc]));
            }
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::chunkId));
        return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder han = new StringBuilder();
        StringBuilder word = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                han.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushHan(han, tokens);
                word.appendCodePoint(cp);
            } else {
                flushHan(han, tokens);
                flushWord(word, tokens);
            }
        }
        flushHan(han, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        if (han.length() == 0) {
            return;
        }
        int[] cps = han.toString().codePoints().toArray();
        if (cps.length == 1) {
            tokens.add(new String(cps, 0, 1));
        } else {
            for (int i = 0; i + 1 < cps.length; i++) {
                tokens.add(new String(cps, i, 2));
            }
        }
        han.setLength(0);
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
