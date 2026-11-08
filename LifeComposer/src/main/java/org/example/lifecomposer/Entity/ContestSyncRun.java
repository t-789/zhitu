package org.example.lifecomposer.Entity;

import lombok.Getter;
import lombok.Setter;

/** 一次竞赛同步的运行记录，用于 last-good 判断与管理员 sync-status。 */
@Getter
@Setter
public class ContestSyncRun {
    private Long id;
    private String startedAt;
    private String finishedAt;
    /** running | success | partial | failed */
    private String status;
    private String source;
    private Integer httpStatus;
    private String generatedAt;
    private Integer itemsSeen;
    private Integer itemsUpserted;
    private Integer itemsSkipped;
    private String error;
}
