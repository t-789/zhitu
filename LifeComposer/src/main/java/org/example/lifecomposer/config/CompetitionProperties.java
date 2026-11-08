package org.example.lifecomposer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体赛事推荐评分配置（版本化，非最终权重）。
 * 对应 {@code lifecomposer.competition.*}，供 {@code CompetitionRecommendationService}
 * 与 {@code CompetitionScoringService} 使用。
 */
@Configuration
@ConfigurationProperties(prefix = "lifecomposer.competition")
@Getter
@Setter
public class CompetitionProperties {

    /** 总开关；关闭时两个赛事工具直接返回功能关闭的错误结果，不改变现有行为。 */
    private boolean enabled = true;

    /** 记录在每次推荐结果里的配置版本。 */
    private String scoringVersion = "competition-v1";

    private double skillWeight = 0.25;
    private double goalWeight = 0.20;
    private double difficultyWeight = 0.15;
    private double timeWeight = 0.15;
    private double transferWeight = 0.10;
    private double deadlineWeight = 0.15;

    /** 总分低于该值不展示。 */
    private double minDisplayScore = 0.45;
    /** 冲一冲档位的总分下限。 */
    private double reachMinScore = 0.55;
    /** 单次最多推荐条数（含上限 5）。 */
    private int maxItems = 5;
    /** 数据源超过该小时数未更新时，回答里提示数据可能不是最新。 */
    private int staleHours = 48;
}
