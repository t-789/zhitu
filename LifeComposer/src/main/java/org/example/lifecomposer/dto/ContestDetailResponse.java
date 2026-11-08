package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 竞赛详情（含完整描述、截止节点、报名/比赛时间等全量字段）。 */
@Getter
@Setter
public class ContestDetailResponse {
    private String eventKey;
    private String name;
    private String organizer;
    private String scope;
    private String eventType;
    private String status;
    private String statusLabel;
    private String level;
    private String region;
    private String location;
    private String mode;
    private String eligibility;
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
    private String nextDeadline;
    private Long daysLeft;
    private String publishedAt;
    private String officialUrl;
    private String sourceUrl;
    private String sourceName;
    private String confidence;
    private String verificationStatus;
    private boolean stale;
    private boolean catalogListed;
    private List<String> categories;
    private List<String> tags;
    private List<String> universityTiers;
    private List<Map<String, String>> deadlines;
}
