package org.example.lifecomposer.recommendation.competition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompetitionQueryParserTest {

    private final CompetitionQueryParser parser = new CompetitionQueryParser();

    @Test
    @DisplayName("null / 空白查询返回空默认")
    void emptyQueryReturnsEmpty() {
        CompetitionQueryParser.ParsedQuery empty = parser.parse(null);
        assertNull(empty.deadlineWithinDays());
        assertNull(empty.gradeHint());
        assertNull(empty.mode());
        assertNull(empty.level());
        assertTrue(empty.interestKeywords().isEmpty());
        assertTrue(empty.excludeKeywords().isEmpty());
        assertFalse(empty.beginner());
        assertFalse(empty.includeOngoing());
        assertNull(empty.availableHours());

        CompetitionQueryParser.ParsedQuery blank = parser.parse("   ");
        assertNull(blank.deadlineWithinDays());
    }

    @Test
    @DisplayName("解析截止时间窗：下个月/本月/最近一周/最近三天")
    void parsesDeadlineWindow() {
        assertEquals(30, parser.parse("下个月截止的数学建模").deadlineWithinDays());
        assertEquals(30, parser.parse("最近30天内").deadlineWithinDays());
        assertEquals(14, parser.parse("本月内").deadlineWithinDays());
        assertEquals(14, parser.parse("最近两周").deadlineWithinDays());
        assertEquals(7, parser.parse("最近一周").deadlineWithinDays());
        assertEquals(3, parser.parse("最近三天").deadlineWithinDays());
    }

    @Test
    @DisplayName("解析年级提示：返回精确年级文本（供难度按年级细分）")
    void parsesGradeHint() {
        assertEquals("研一", parser.parse("我是研一").gradeHint());
        assertEquals("大二", parser.parse("我是大二").gradeHint());
        assertEquals("博士生", parser.parse("我是博士生").gradeHint());
        assertNull(parser.parse("想找比赛").gradeHint());
    }

    @Test
    @DisplayName("解析形式：线上/线下/线上线下；否定形式反转为相反形式")
    void parsesMode() {
        assertEquals("online", parser.parse("想找线上的比赛").mode());
        assertEquals("offline", parser.parse("要线下的").mode());
        assertEquals("hybrid", parser.parse("线上线下结合").mode());
        assertNull(parser.parse("随便").mode());
        // bug4：不要线下 → online，不要线上 → offline。
        assertEquals("online", parser.parse("不要线下的").mode());
        assertEquals("offline", parser.parse("不想线上的").mode());
    }

    @Test
    @DisplayName("解析级别：国家级/省级/校级/国际级")
    void parsesLevel() {
        assertEquals("national", parser.parse("国家级比赛").level());
        assertEquals("provincial", parser.parse("省级").level());
        assertEquals("campus", parser.parse("校级").level());
        assertEquals("international", parser.parse("国际赛事").level());
        assertNull(parser.parse("比赛").level());
    }

    @Test
    @DisplayName("解析兴趣关键词与排除关键词")
    void parsesInterestAndExclude() {
        CompetitionQueryParser.ParsedQuery q = parser.parse("想找数学建模比赛，不要网安");
        assertTrue(q.interestKeywords().contains("数学建模"));
        assertTrue(q.excludeKeywords().contains("网安"));
    }

    @Test
    @DisplayName("bug5：不想打数模 → 排除词识别出数模，且数模不再当兴趣词")
    void parsesExcludeAliasNotPollutingInterest() {
        CompetitionQueryParser.ParsedQuery q = parser.parse("不想打数模");
        assertTrue(q.excludeKeywords().contains("数模"), "排除词应识别出数模");
        assertFalse(q.interestKeywords().contains("数模"), "数模不应被当兴趣词");
        assertFalse(q.interestKeywords().contains("建模"), "建模不应被当兴趣词");
    }

    @Test
    @DisplayName("解析新手与进行中标记")
    void parsesBeginnerAndOngoing() {
        assertTrue(parser.parse("零基础").beginner());
        assertTrue(parser.parse("新手入门").beginner());
        assertFalse(parser.parse("有经验").beginner());
        assertTrue(parser.parse("还能参加的").includeOngoing());
        assertTrue(parser.parse("正在进行").includeOngoing());
        assertFalse(parser.parse("报名中的").includeOngoing());
    }

    @Test
    @DisplayName("解析每周可投入小时（中文与英文）")
    void parsesAvailableHours() {
        assertEquals(6.0, parser.parse("每周6小时").availableHours(), 0.0001);
        assertEquals(6.0, parser.parse("6 hours/week").availableHours(), 0.0001);
        assertEquals(10.0, parser.parse("周均10小时").availableHours(), 0.0001);
        assertNull(parser.parse("时间不确定").availableHours());
    }
}
