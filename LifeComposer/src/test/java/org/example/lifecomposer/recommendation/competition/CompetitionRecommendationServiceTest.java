package org.example.lifecomposer.recommendation.competition;

import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.dto.CompetitionRecommendationDto;
import org.example.lifecomposer.dto.CompetitionRecommendationResult;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 赛事推荐端到端（实时层已落地）：硬过滤、状态重算、低置信徽章、校内通知放行、
 * 无画像追问、排除关键词，与方案 §13 验收对齐。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "lifecomposer.competition.enabled=true",
        "lifecomposer.contest-sync.enabled=false"
})
class CompetitionRecommendationServiceTest {

    private static final long USER_ID = 654321L;

    @Autowired
    private CompetitionRecommendationService recommendationService;

    @Autowired
    private ContestEventRepository contestEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.execute("DELETE FROM contest_events");
        jdbcTemplate.execute("DELETE FROM user_profiles");
    }

    private ContestEvent competition(String eventKey, String name, String categoriesJson,
                                     String registrationDeadline, String verificationStatus,
                                     String confidence, String level, String scope) {
        ContestEvent e = new ContestEvent();
        e.setEventKey(eventKey);
        e.setSource("contest-ddl");
        e.setSourceKind("competition");
        e.setSourceId(eventKey);
        e.setScope(scope == null ? "china" : scope);
        e.setEventType("competition");
        e.setName(name);
        e.setLevel(level == null ? "national" : level);
        e.setRegistrationDeadline(registrationDeadline);
        e.setCategoriesJson(categoriesJson);
        e.setVerificationStatus(verificationStatus == null ? "single_source" : verificationStatus);
        e.setConfidence(confidence == null ? "low" : confidence);
        e.setArchived(0);
        e.setStale(0);
        e.setCatalogListed(0);
        e.setSourceName("赛氪");
        e.setFetchedAt(Instant.now().toString());
        return e;
    }

    private static String iso(int daysFromNow) {
        return Instant.now().plusSeconds((long) daysFromNow * 86400L).toString();
    }

    @Test
    @DisplayName("已归档/已 stale/已截止的赛事被硬过滤")
    void hardFilterExcludesArchivedStaleExpired() {
        contestEventRepository.upsert(competition("cddl_active", "活跃算法赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china"));
        ContestEvent archived = competition("cddl_archived", "已归档赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china");
        archived.setArchived(1);
        contestEventRepository.upsert(archived);
        ContestEvent stale = competition("cddl_stale", "过期数据赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china");
        stale.setStale(1);
        contestEventRepository.upsert(stale);
        ContestEvent expired = competition("cddl_expired", "已截止赛", "[\"算法\"]",
                iso(-10), "single_source", "low", "national", "china");
        contestEventRepository.upsert(expired);

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "算法比赛", null, null, false, null, null, null,
                null, null, null);

        assertTrue(result.getItems().stream().anyMatch(i -> i.getEventKey().equals("cddl_active")));
        assertTrue(result.getItems().stream().noneMatch(i -> i.getEventKey().equals("cddl_archived")));
        assertTrue(result.getItems().stream().noneMatch(i -> i.getEventKey().equals("cddl_stale")));
        assertTrue(result.getItems().stream().noneMatch(i -> i.getEventKey().equals("cddl_expired")));
    }

    @Test
    @DisplayName("单源低置信条目带「报名前请核对官网」风险提示")
    void lowConfidenceSingleSourceCarriesWarning() {
        contestEventRepository.upsert(competition("cddl_low", "低置信算法赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china"));

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "算法比赛", null, null, false, null, null, null,
                null, null, null);

        CompetitionRecommendationDto first = result.getItems().get(0);
        assertTrue(first.getRiskNotes().stream().anyMatch(n -> n.contains("报名前请核对官网")),
                "single_source/low 条目必须带「报名前请核对官网」提示");
    }

    @Test
    @DisplayName("无画像时信息不足且带追问与假设，但不硬编国家级")
    void noProfileAsksQuestions() {
        contestEventRepository.upsert(competition("cddl_np", "算法赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china"));

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "算法比赛", null, null, false, null, null, null,
                null, null, null);

        assertFalse(result.isInformationSufficient());
        assertFalse(result.getFollowUpQuestions().isEmpty());
        assertFalse(result.getAssumptions().isEmpty());
        assertTrue(result.getFollowUpQuestions().stream().anyMatch(q -> q.contains("年级")));
    }

    @Test
    @DisplayName("排除关键词命中名称/类别时不再返回该类")
    void excludeKeywordsFiltersCategory() {
        contestEventRepository.upsert(competition("cddl_mcm", "全国大学生数学建模竞赛", "[\"数学建模\"]",
                iso(30), "official_site", "high", "national", "china"));
        contestEventRepository.upsert(competition("cddl_algo", "蓝桥杯算法赛", "[\"算法\"]",
                iso(30), "single_source", "low", "national", "china"));

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "找比赛", null, null, false, null, null, null,
                List.of("数学建模"), null, null);

        assertTrue(result.getItems().stream().noneMatch(i -> i.getEventKey().equals("cddl_mcm")));
        assertTrue(result.getItems().stream().anyMatch(i -> i.getEventKey().equals("cddl_algo")));
    }

    @Test
    @DisplayName("校内通知放行 submission_open（scope=all）")
    void campusNoticeSubmissionOpenAllowed() {
        ContestEvent notice = new ContestEvent();
        notice.setEventKey("notice_sub");
        notice.setSource("where-to-study-notices");
        notice.setSourceKind("notice");
        notice.setSourceId("notice_sub");
        notice.setScope("campus");
        notice.setEventType("notice");
        notice.setName("校内竞赛通知");
        notice.setLevel("campus");
        notice.setDeadlinesJson("[{\"date\":\"" + iso(15) + "\",\"display\":\"材料提交\",\"label\":\"材料提交\"}]");
        notice.setVerificationStatus("official_notice");
        notice.setConfidence("high");
        notice.setArchived(0);
        notice.setStale(0);
        notice.setCatalogListed(0);
        notice.setSourceName("北邮云平台");
        notice.setFetchedAt(Instant.now().toString());
        contestEventRepository.upsert(notice);

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "校内比赛", "all", null, false, null, null, null,
                null, null, null);

        CompetitionRecommendationDto item = result.getItems().stream()
                .filter(i -> i.getEventKey().equals("notice_sub"))
                .findFirst().orElseThrow();
        assertEquals("submission_open", item.getStatus());
        assertEquals("提交中", item.getStatusLabel());
    }

    @Test
    @DisplayName("分档只暴露 tier 中文值，不返回内部匹配度")
    void tierIsExposedOnlyAsChineseLabel() {
        contestEventRepository.upsert(competition("cddl_tier", "蓝桥杯", "[\"算法\"]",
                iso(30), "official_site", "high", "national", "china"));

        CompetitionRecommendationResult result = recommendationService.recommend(
                USER_ID, "算法比赛", null, null, false, null, null, null,
                null, null, null);

        CompetitionRecommendationDto item = result.getItems().get(0);
        assertNotNull(item.getTier());
        assertTrue(List.of("冲一冲", "比较匹配", "练手", "长期关注").contains(item.getTier()));
    }

    @Test
    @DisplayName("按 eventKey 查详情返回全字段并可选附适配信息")
    void detailByEventKeyReturnsFullInfo() {
        contestEventRepository.upsert(competition("cddl_detail", "蓝桥杯算法赛", "[\"算法\"]",
                iso(30), "official_site", "high", "national", "china"));

        Map<String, Object> detail = recommendationService.detail(USER_ID, "cddl_detail", null, true);

        assertEquals(true, detail.get("found"));
        assertEquals("cddl_detail", detail.get("eventKey"));
        assertEquals("蓝桥杯算法赛", detail.get("name"));
        assertNotNull(detail.get("statusLabel"));
        assertNotNull(detail.get("deadlines"));
        assertTrue(detail.containsKey("fit"), "includeFit=true 应附带适配信息");
    }

    @Test
    @DisplayName("按名称模糊匹配到多条时返回 candidates（≤3）")
    void detailByNameReturnsCandidatesWhenMultiple() {
        contestEventRepository.upsert(competition("cddl_n1", "全国大学生数学建模竞赛", "[\"数学建模\"]",
                iso(30), "official_site", "high", "national", "china"));
        contestEventRepository.upsert(competition("cddl_n2", "数学建模挑战赛", "[\"数学建模\"]",
                iso(20), "single_source", "low", "national", "china"));
        contestEventRepository.upsert(competition("cddl_n3", "数学建模校赛", "[\"数学建模\"]",
                iso(15), "single_source", "low", "campus", "china"));

        Map<String, Object> detail = recommendationService.detail(USER_ID, null, "数学建模", false);

        assertEquals(true, detail.get("found"));
        assertEquals(true, detail.get("multiple"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) detail.get("candidates");
        assertNotNull(candidates);
        assertTrue(candidates.size() >= 2 && candidates.size() <= 3, "候选数应在 2~3 之间");
    }

    @Test
    @DisplayName("已结束赛事仍可查，fit 写「已结束」提示")
    void detailEndedShowsEndedFitNote() {
        ContestEvent ended = competition("cddl_ended", "已结束算法赛", "[\"算法\"]",
                iso(-10), "official_site", "high", "national", "china");
        ended.setStatusHint("ended");
        contestEventRepository.upsert(ended);

        Map<String, Object> detail = recommendationService.detail(USER_ID, "cddl_ended", null, true);

        assertEquals(true, detail.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fit = (Map<String, Object>) detail.get("fit");
        assertNotNull(fit);
        assertTrue(String.valueOf(fit.get("note")).contains("已结束"),
                "已结束赛事的 fit 应写已结束提示");
    }

    @Test
    @DisplayName("不存在的 eventKey 返回 found=false")
    void detailMissingReturnsNotFound() {
        Map<String, Object> detail = recommendationService.detail(USER_ID, "cddl_ghost", null, false);

        assertEquals(false, detail.get("found"));
        assertNotNull(detail.get("reason"));
    }
}
