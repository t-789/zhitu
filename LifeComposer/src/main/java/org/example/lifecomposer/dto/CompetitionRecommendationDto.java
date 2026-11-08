package org.example.lifecomposer.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条赛事推荐（面向 LLM 的裁剪结果，不含 description 全文 / raw_json / 内部评分 / 数据库 id）。
 *
 * <p>字段名是给模型看的内部标识；{@code competition_recommendation.md} 明确要求模型
 * 把 tier/status/eventKey 等翻译成中文自然语言，不向用户原样输出。
 */
@Getter
@Setter
public class CompetitionRecommendationDto {

    /** 业务唯一键 cddl_&lt;id&gt; / notice_&lt;id&gt;（模型不得向用户原样输出）。 */
    private String eventKey;
    private String name;
    /** 冲一冲 / 比较匹配 / 练手 / 长期关注 */
    private String tier;
    /** registration_open 等（内部状态键）。 */
    private String status;
    /** 报名中 / 即将报名 / 提交中 等中文标签。 */
    private String statusLabel;
    /** 展示用截止时间，Asia/Shanghai。 */
    private String deadline;
    /** 距当前时间的整天数（可为负；无截止为 null）。 */
    private Long daysLeft;
    /** 校级 / 省级 / 国家级 / 国际级 等中文标签。 */
    private String levelLabel;
    /** 国内 / 校内 / 国际。 */
    private String scopeLabel;
    /** 与哪个成长方向相关（方向中文名；无方向为 null）。 */
    private String relatedDirection;
    /** 为什么适合（中文理由，≤3 条）。 */
    private List<String> fitReasons = new ArrayList<>();
    /** 还缺什么（标准能力标签中文，可空）。 */
    private List<String> gapSkills = new ArrayList<>();
    /** 补能力的起点（资源库条目名或建议文案，可空）。 */
    private List<String> startPoints = new ArrayList<>();
    /** 风险提示（中文，≤2 条），如“类别未识别”“以官网为准”。 */
    private List<String> riskNotes = new ArrayList<>();
    /** 下一步行动（中文）。 */
    private String nextAction;
    private String officialUrl;
    private String sourceUrl;
    private String sourceName;
    /** high | medium | low */
    private String confidence;
    /** official_site | single_source | official_notice */
    private String verificationStatus;
}
