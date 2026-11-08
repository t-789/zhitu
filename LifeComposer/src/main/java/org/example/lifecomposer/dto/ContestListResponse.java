package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 竞赛列表响应信封。 */
@Getter
@Setter
public class ContestListResponse {
    private List<ContestItemResponse> items;
    private int total;
    private int limit;
    private int offset;
    /** 本次计算所用时间（UTC ISO）。 */
    private String syncedAt;
    /** 库里最“新”的源生成时间（UTC ISO），用于判断数据新鲜度。 */
    private String sourceGeneratedAt;
    /** 源数据超过 staleHours 未更新时为 true。 */
    private boolean staleSource;
}
