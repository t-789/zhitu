package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 竞赛列表单项（列表接口里 description 截断，详情接口用 ContestDetailResponse）。 */
@Getter
@Setter
public class ContestItemResponse {
    private String eventKey;
    private String name;
    private String organizer;
    private String scope;
    private String eventType;
    private String status;
    private String statusLabel;
    private String level;
    private String mode;
    private String registrationDeadline;
    private String nextDeadline;
    private Long daysLeft;
    private String officialUrl;
    private String sourceUrl;
    private String sourceName;
    private String confidence;
    private String verificationStatus;
    private boolean stale;
    private boolean catalogListed;
    private String description;
    private List<String> tags;
}
