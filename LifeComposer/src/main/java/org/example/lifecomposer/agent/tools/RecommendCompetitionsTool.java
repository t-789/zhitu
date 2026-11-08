package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.AgentToolSupport;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.dto.CompetitionRecommendationResult;
import org.example.lifecomposer.recommendation.competition.CompetitionRecommendationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读赛事推荐工具：根据用户画像 + 本次请求约束，从实时 {@code contest_events}
 * 返回确定性打分、分档（冲一冲/比较匹配/练手/长期关注）后的赛事候选。
 */
@Component
public class RecommendCompetitionsTool implements AgentTool {

    private final CompetitionRecommendationService recommendationService;

    public RecommendCompetitionsTool(CompetitionRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public String name() {
        return "recommend_competitions";
    }

    @Override
    public String description() {
        return "根据用户画像（年级、专业、技能、每周可投入时间、目标）和对话中的约束，推荐可报名或即将报名的竞赛/黑客松/校内通知。"
                + "返回按「冲一冲/比较匹配/练手/长期关注」分档的候选，每条含报名状态、截止时间、为什么适合、还缺什么、下一步和来源。"
                + "赛事事实只能来自本工具返回的数据，不得自行编造或补充。";
    }

    @Override
    public String displayDescription() {
        return "推荐赛事";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "用户本次的赛事需求或约束，例如“我是大二，想找数学建模比赛，不要线下的”"));
        properties.put("scope", Map.of("type", "string", "enum", List.of("china", "campus", "all"),
                "description", "范围：china 国内、campus 校内、all 全部；默认 china"));
        properties.put("eventType", Map.of("type", "string", "enum",
                List.of("competition", "hackathon", "campus_notice", "all"),
                "description", "赛事类型；默认 competition+hackathon+campus_notice"));
        properties.put("includeOngoing", Map.of("type", "boolean",
                "description", "是否包含进行中的赛事；默认 false（只推报名中/即将报名）"));
        properties.put("deadlineWithinDays", Map.of("type", "integer",
                "description", "只看 N 天内截止的赛事（1-180）"));
        properties.put("level", Map.of("type", "string", "enum",
                List.of("campus", "university", "provincial", "national", "international"),
                "description", "赛事级别：campus 校内 / university 校级 / provincial 省级 / national 国家级 / international 国际级"));
        properties.put("mode", Map.of("type", "string", "enum", List.of("online", "offline", "hybrid"),
                "description", "形式：online 线上 / offline 线下 / hybrid 线上线下结合"));
        properties.put("excludeKeywords", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "要排除的类别关键词，如 [\"数模\",\"网安\"]"));
        properties.put("excludeEventKeys", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "要排除的具体赛事键（eventKey）"));
        properties.put("limit", Map.of("type", "integer", "description", "最多返回条数，默认 5，最大 5"));
        return Map.of("type", "object", "properties", properties, "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        String query = AgentToolSupport.stringArg(arguments, "query");
        if (query == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "query 不能为空");
        }
        String scope = AgentToolSupport.stringArg(arguments, "scope");
        String eventType = AgentToolSupport.stringArg(arguments, "eventType");
        Boolean includeOngoing = boolArg(arguments, "includeOngoing");
        Integer deadlineWithinDays = AgentToolSupport.intArg(arguments, "deadlineWithinDays");
        String level = AgentToolSupport.stringArg(arguments, "level");
        String mode = AgentToolSupport.stringArg(arguments, "mode");
        List<String> excludeKeywords = stringArrayArg(arguments, "excludeKeywords");
        List<String> excludeEventKeys = stringArrayArg(arguments, "excludeEventKeys");
        Integer limit = AgentToolSupport.intArg(arguments, "limit");

        CompetitionRecommendationResult result = recommendationService.recommend(
                context.userId().longValue(), query, scope, eventType,
                includeOngoing != null && includeOngoing, deadlineWithinDays, level, mode,
                excludeKeywords, excludeEventKeys, limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", result.getCount());
        data.put("candidateCount", result.getCandidateCount());
        data.put("informationSufficient", result.isInformationSufficient());
        data.put("profileSummary", result.getProfileSummary());
        data.put("assumptions", result.getAssumptions());
        data.put("items", result.getItems());
        data.put("followUpQuestions", result.getFollowUpQuestions());
        data.put("filterStats", result.getFilterStats());
        data.put("displayInstruction",
                "向用户解释时请使用中文：分档用「冲一冲/比较匹配/练手/长期关注」、状态用中文标签、"
                        + "截止和「还缺什么」「下一步」照实转述；禁止输出 eventKey、tier、status、"
                        + "skillFit、scoreBreakdown、relatedDirection 等英文字段名；"
                        + "单源低置信的条目必须提示「报名前请核对官网」。");
        return ToolResult.ok(data);
    }

    private Boolean boolArg(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        JsonElement value = arguments.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return value.getAsBoolean();
    }

    private List<String> stringArrayArg(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return List.of();
        }
        JsonElement value = arguments.get(name);
        if (!value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement item : array) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String text = item.getAsString();
                if (text != null && !text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return out;
    }
}
