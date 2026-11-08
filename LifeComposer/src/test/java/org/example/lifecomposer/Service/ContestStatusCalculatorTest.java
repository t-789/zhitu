package org.example.lifecomposer.Service;

import org.example.lifecomposer.Entity.ContestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态重算的单元测试，覆盖交接反馈里的通知状态（bug1）与"仅比赛开始时间"（bug14）。
 */
class ContestStatusCalculatorTest {

    private final ContestStatusCalculator calculator = new ContestStatusCalculator();
    private final Instant now = Instant.parse("2026-11-08T12:00:00Z");

    private ContestEvent notice(String deadlinesJson) {
        ContestEvent e = new ContestEvent();
        e.setEventKey("notice_x");
        e.setSourceKind("notice");
        e.setEventType("notice");
        e.setScope("campus");
        e.setArchived(0);
        e.setDeadlinesJson(deadlinesJson);
        return e;
    }

    private ContestEvent competition() {
        ContestEvent e = new ContestEvent();
        e.setEventKey("cddl_x");
        e.setSourceKind("competition");
        e.setEventType("competition");
        e.setScope("china");
        e.setArchived(0);
        return e;
    }

    @Test
    @DisplayName("bug1：通知同时有报名(3天后)与材料(10天后)节点时应判报名中、剩3天")
    void noticeWithRegistrationAndMaterialDeadlinesIsRegistrationOpen() {
        ContestEvent e = notice("["
                + "{\"date\":\"2026-11-11T23:59:59+08:00\",\"label\":\"报名截止\"},"
                + "{\"date\":\"2026-11-18T23:59:59+08:00\",\"label\":\"材料提交\"}"
                + "]");

        ContestStatusCalculator.StatusResult r = calculator.compute(e, now);

        assertEquals("registration_open", r.status());
        assertEquals(3L, r.daysLeft(), "应取未来最早的报名截止（3天），而非材料的10天");
    }

    @Test
    @DisplayName("通知只有材料节点（无报名）时判提交中")
    void noticeWithOnlyMaterialDeadlineIsSubmissionOpen() {
        ContestEvent e = notice("[{\"date\":\"2026-11-18T23:59:59+08:00\",\"label\":\"材料提交\"}]");

        ContestStatusCalculator.StatusResult r = calculator.compute(e, now);

        assertEquals("submission_open", r.status());
        assertEquals(10L, r.daysLeft());
    }

    @Test
    @DisplayName("bug14：仅有比赛开始时间、无任何报名信息时不能判即将报名")
    void competitionWithOnlyStartDateIsNotRegistrationUpcoming() {
        ContestEvent e = competition();
        e.setCompetitionStart("2026-12-01T00:00:00+08:00");
        // 无 registration_start / registration_deadline。

        ContestStatusCalculator.StatusResult r = calculator.compute(e, now);

        assertNotEquals("registration_upcoming", r.status(),
                "没有报名信息不能声称即将报名");
        assertEquals("unknown", r.status());
    }

    @Test
    @DisplayName("报名未开始且报名开始时间在未来 → 即将报名")
    void competitionBeforeRegistrationStartIsUpcoming() {
        ContestEvent e = competition();
        e.setRegistrationStart("2026-11-20T00:00:00+08:00");
        e.setRegistrationDeadline("2026-12-01T00:00:00+08:00");

        ContestStatusCalculator.StatusResult r = calculator.compute(e, now);

        assertEquals("registration_upcoming", r.status());
    }

    @Test
    @DisplayName("报名截止在未来 → 报名中")
    void competitionWithinRegistrationWindowIsOpen() {
        ContestEvent e = competition();
        e.setRegistrationDeadline("2026-11-30T00:00:00+08:00");

        ContestStatusCalculator.StatusResult r = calculator.compute(e, now);

        assertEquals("registration_open", r.status());
    }
}
