package org.example.lifecomposer.Entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 实时竞赛事件（contest-ddl 竞赛/黑客松 + 校内通知）。普通 POJO，非 JPA Entity。
 * 所有时间字段统一存 UTC ISO-8601 文本（{@code yyyy-MM-dd'T'HH:mm:ss'Z'}）；
 * created_at / updated_at 由 SQLite CURRENT_TIMESTAMP 生成（{@code yyyy-MM-dd HH:mm:ss}）。
 * 布尔字段用 INTEGER（0/1），符合项目 BOOLEAN 用 INTEGER 的约定。
 */
@Getter
@Setter
public class ContestEvent {
    private Long id;
    /** 业务唯一键：cddl_&lt;id&gt; / notice_&lt;id&gt; */
    private String eventKey;
    /** 来源：contest-ddl / where-to-study-notices */
    private String source;
    /** competition | notice */
    private String sourceKind;
    /** 源数据里的原始 id */
    private String sourceId;
    /** china | international | campus */
    private String scope;
    /** competition | hackathon | summer_camp | conference | pre_admission | notice */
    private String eventType;
    private String name;
    private String organizer;
    private String level;
    private String region;
    private String location;
    /** online | offline | hybrid */
    private String mode;
    private String eligibility;
    private String categoriesJson;
    private String tagsJson;
    private String universityTiersJson;
    private String officialUrl;
    private String sourceUrl;
    private String description;
    private String notes;
    private String imageUrl;
    private String registrationStart;
    private String registrationDeadline;
    private String competitionStart;
    private String competitionEnd;
    private String abstractDeadline;
    private String submissionDeadline;
    private String primaryDeadline;
    private String deadlinesJson;
    private String publishedAt;
    /** 源端预计算状态，仅作 hint，读取时必须按 now 重算。 */
    private String statusHint;
    /** 最近一次同步时算出的状态快照（仅供排查，不作为实时状态）。 */
    private String statusSnapshot;
    /** high | medium | low */
    private String confidence;
    /** official_site | single_source | official_notice */
    private String verificationStatus;
    private Integer catalogListed;
    private String sourceName;
    private Integer stale;
    private Integer archived;
    private String firstSeenAt;
    private String lastSeenAt;
    private String sourceGeneratedAt;
    private String fetchedAt;
    private String rawJson;
    private String createdAt;
    private String updatedAt;
}
