package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code recommend_competitions} 工具的整体结果：推荐列表 + 画像摘要 + 假设 + 追问 + 筛选统计。
 */
@Getter
@Setter
public class CompetitionRecommendationResult {

    /** 返回给模型的条数（items.size()）。 */
    private int count;
    /** 硬过滤后、排序前的候选数。 */
    private int candidateCount;
    /** 画像是否足够支撑个性化推荐。 */
    private boolean informationSufficient;
    /** 画像摘要：grade/major/skills/availableHoursPerWeek/goals（只含已填项）。 */
    private Map<String, Object> profileSummary = new LinkedHashMap<>();
    /** 本次推荐所做的假设（中文，如“未填年级，按校级~国家级均可考虑”）。 */
    private List<String> assumptions = new ArrayList<>();
    private List<CompetitionRecommendationDto> items = new ArrayList<>();
    /** 画像不足时的追问（中文，≤2 个）。 */
    private List<String> followUpQuestions = new ArrayList<>();
    /** 筛选统计：total / afterHardFilter / returned。 */
    private Map<String, Object> filterStats = new LinkedHashMap<>();
}
