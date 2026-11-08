package org.example.lifecomposer.recommendation.competition;

import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.recommendation.competition.CompetitionScoringService.ProfileSnapshot;
import org.example.lifecomposer.recommendation.competition.CompetitionScoringService.Request;
import org.example.lifecomposer.recommendation.competition.CompetitionScoringService.Ranking;
import org.example.lifecomposer.recommendation.competition.CompetitionScoringService.Scored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性评分的单元回归：覆盖交接反馈 bug2（技能匹配参数反）、bug3（年级对难度无影响）、
 * bug6（低分不过滤）、bug7（无关赛事标成方向相关）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "lifecomposer.competition.enabled=true",
        "lifecomposer.contest-sync.enabled=false"
})
class CompetitionScoringServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    @Autowired
    private CompetitionScoringService scoringService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedSkillProfiles() {
        // 测试库不导入样例数据，需手动写入 skill_profiles 供 requiredSkills() 展开。
        jdbcTemplate.update("""
                INSERT INTO capability_reference (section, ref_key, ref_value, note)
                VALUES ('skill_profiles', 'algorithm_contest', '["编程基础","算法与数据结构"]', NULL)
                ON CONFLICT(section, ref_key) DO UPDATE SET ref_value = excluded.ref_value
                """);
    }

    private ContestEvent algoCompetition(String eventKey, String level) {
        ContestEvent e = new ContestEvent();
        e.setEventKey(eventKey);
        e.setSource("contest-ddl");
        e.setSourceKind("competition");
        e.setSourceId(eventKey);
        e.setScope("china");
        e.setEventType("competition");
        e.setName("蓝桥杯算法赛");
        e.setLevel(level == null ? "national" : level);
        e.setCategoriesJson("[\"算法\",\"程序设计\"]");
        e.setRegistrationDeadline("2026-12-01T00:00:00+08:00");
        e.setVerificationStatus("single_source");
        e.setConfidence("low");
        e.setArchived(0);
        e.setStale(0);
        e.setCatalogListed(0);
        e.setSourceName("赛氪");
        e.setFetchedAt(NOW.toString());
        return e;
    }

    private static Request request(String grade) {
        return new Request("china", null, false, null, null, null,
                List.of(), List.of(), 5, grade, null, false, null);
    }

    private static Scored find(Ranking ranking, String eventKey) {
        return ranking.items().stream()
                .filter(s -> s.event().getEventKey().equals(eventKey))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("bug2：算法赛要求 2 项技能用户全具备，skillFit 应为 1.0（参数不再反）")
    void skillFitIsOneWhenAllRequiredSkillsPresent() {
        ContestEvent event = algoCompetition("cddl_skill", "national");
        ProfileSnapshot profile = new ProfileSnapshot("大二", null,
                List.of("编程基础", "算法与数据结构", "数学建模", "数据分析", "英语阅读"),
                List.of(), List.of("算法"), 8.0);

        Ranking ranking = scoringService.rank(List.of(event), profile, request("大二"), NOW);
        Scored scored = find(ranking, "cddl_skill");

        assertNotNull(scored, "赛事应通过硬过滤");
        assertEquals(1.0, scored.skillFit(), 0.0001,
                "要求 2 项全具备应得 1.0，而不是用 5 项用户技能当分母得 0.4");
    }

    @Test
    @DisplayName("bug3：同一国家级赛事，大四的难度匹配分应高于大一")
    void difficultyFitDiffersByGrade() {
        ContestEvent event = algoCompetition("cddl_grade", "national");

        Ranking freshman = scoringService.rank(List.of(event),
                new ProfileSnapshot("大一", null, List.of("编程基础", "算法与数据结构"),
                        List.of(), List.of("算法"), 8.0),
                request("大一"), NOW);
        Ranking senior = scoringService.rank(List.of(event),
                new ProfileSnapshot("大四", null, List.of("编程基础", "算法与数据结构"),
                        List.of(), List.of("算法"), 8.0),
                request("大四"), NOW);

        Scored f = find(freshman, "cddl_grade");
        Scored s = find(senior, "cddl_grade");
        assertNotNull(f);
        assertNotNull(s);
        assertTrue(s.difficultyFit() > f.difficultyFit(),
                "大四对国家级赛的难度匹配应高于大一，而非完全无影响");
    }

    @Test
    @DisplayName("bug6：弱匹配（总分低于展示阈值）的赛事被过滤，不返回 tier=null 的条目")
    void lowScoreEventsAreFilteredOut() {
        ContestEvent weak = algoCompetition("cddl_weak", "national");
        // 无画像：技能/目标/时间全缺失，国家级赛事应弱匹配。
        ProfileSnapshot empty = new ProfileSnapshot(null, null,
                List.of(), List.of(), List.of(), null);

        Ranking ranking = scoringService.rank(List.of(weak), empty, request(null), NOW);

        assertNull(find(ranking, "cddl_weak"), "弱匹配赛事不应出现在结果里");
        assertTrue(ranking.items().stream().allMatch(s -> s.tier() != null),
                "返回的每条赛事都必须有分档（tier 非空）");
    }

    @Test
    @DisplayName("bug7：与当前方向无关的赛事不标注 relatedDirection（不虚设方向相关）")
    void relatedDirectionNullWhenDirectionUnrelated() {
        // 用户的技能指向算法方向（algorithm_contest），但赛事是设计类，应判为不相关。
        ContestEvent event = new ContestEvent();
        event.setEventKey("cddl_design");
        event.setSource("contest-ddl");
        event.setSourceKind("competition");
        event.setSourceId("cddl_design");
        event.setScope("china");
        event.setEventType("competition");
        event.setName("全国工业设计大赛");
        event.setLevel("national");
        event.setCategoriesJson("[\"设计\"]");
        event.setRegistrationDeadline("2026-12-01T00:00:00+08:00");
        event.setVerificationStatus("single_source");
        event.setConfidence("low");
        event.setArchived(0);
        event.setStale(0);
        event.setCatalogListed(0);
        event.setSourceName("赛氪");
        event.setFetchedAt(NOW.toString());

        ProfileSnapshot algoUser = new ProfileSnapshot("大二", null,
                List.of("编程基础", "算法与数据结构"), List.of(), List.of(), 8.0);

        Ranking ranking = scoringService.rank(List.of(event), algoUser, request("大二"), NOW);
        Scored scored = find(ranking, "cddl_design");

        assertNotNull(scored, "设计赛事应通过硬过滤");
        assertNull(scored.relatedDirection(),
                "设计赛事与算法方向无关，不能标注方向相关");
    }
}
