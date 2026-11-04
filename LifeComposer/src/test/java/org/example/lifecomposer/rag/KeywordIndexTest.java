package org.example.lifecomposer.rag;

import org.example.lifecomposer.Repository.RagChunkRepository.KeywordRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    private static KeywordRow row(String id, String title, String text) {
        return new KeywordRow(id, title, text);
    }

    @Test
    void hanTextBecomesOverlappingPairsAndALoneCharacterStaysSingle() {
        assertEquals(List.of("蓝桥", "桥杯"), KeywordIndex.tokenize("蓝桥杯"));
        assertEquals(List.of("综测"), KeywordIndex.tokenize("综测"));
        assertEquals(List.of("我"), KeywordIndex.tokenize("我"));
    }

    @Test
    void latinAndDigitsAreLowerCasedWordsAndPunctuationBreaksRuns() {
        assertEquals(List.of("ccsp", "2026"), KeywordIndex.tokenize("CCSP-2026"));
        assertEquals(List.of("c", "语言"), KeywordIndex.tokenize("C语言"));
        // no pair may span the comma
        assertEquals(List.of("竞赛", "保研"), KeywordIndex.tokenize("竞赛，保研"));
        assertTrue(KeywordIndex.tokenize("  ,.;！").isEmpty());
    }

    @Test
    void twoCharacterNamesAreSearchable() {
        KeywordIndex index = KeywordIndex.build(List.of(
                row("a", "竞赛概述", "数学建模竞赛面向在校生"),
                row("b", "加分规则", "综测加分按竞赛级别计算")));

        List<KeywordIndex.Match> matches = index.search("综测", 5);

        assertEquals(1, matches.size());
        assertEquals("b", matches.get(0).chunkId());
    }

    @Test
    void rareTermsOutweighCommonOnes() {
        KeywordIndex index = KeywordIndex.build(List.of(
                row("a", "竞赛", "竞赛竞赛竞赛"),
                row("b", "竞赛", "竞赛 ICPC 说明"),
                row("c", "竞赛", "竞赛 经验分享")));

        // "竞赛" is in every chunk (low weight); "icpc" only in b.
        assertEquals("b", index.search("竞赛 ICPC", 3).get(0).chunkId());
    }

    @Test
    void aTitleMatchOutranksTheSameWordBuriedInBodyText() {
        KeywordIndex index = KeywordIndex.build(List.of(
                row("body", "其他主题", "这里顺带提到保研政策"),
                row("title", "保研政策", "这里讲其他内容")));

        assertEquals("title", index.search("保研政策", 2).get(0).chunkId());
    }

    @Test
    void ties_areBrokenByChunkIdAndLimitIsHonoured() {
        KeywordIndex index = KeywordIndex.build(List.of(
                row("b", "同题", "同文"), row("a", "同题", "同文"), row("c", "同题", "同文")));

        assertEquals(List.of("a", "b"), index.search("同题", 2).stream().map(KeywordIndex.Match::chunkId).toList());
    }

    @Test
    void emptyOrUnmatchedInputsYieldNothing() {
        KeywordIndex index = KeywordIndex.build(List.of(row("a", "标题", "正文")));
        assertTrue(index.search("", 5).isEmpty());
        assertTrue(index.search(null, 5).isEmpty());
        assertTrue(index.search("完全无关zzz", 5).isEmpty());
        assertTrue(index.search("标题", 0).isEmpty());
        assertTrue(KeywordIndex.build(List.of()).search("标题", 5).isEmpty());
    }

    @Test
    void nullTitleOrTextDoesNotBreakTheIndex() {
        KeywordIndex index = KeywordIndex.build(List.of(row("a", null, "只有正文"), row("b", "只有标题", null)));
        assertEquals("a", index.search("正文", 5).get(0).chunkId());
        assertEquals("b", index.search("标题", 5).get(0).chunkId());
    }
}
