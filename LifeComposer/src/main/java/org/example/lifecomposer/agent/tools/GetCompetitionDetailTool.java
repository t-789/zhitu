package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.AgentToolSupport;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.recommendation.competition.CompetitionRecommendationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读赛事详情工具：按 eventKey 或名称查询单个赛事的完整信息
 * （含 deadlines、eligibility、来源、最后同步时间），可选附带对该用户的适配信息。
 */
@Component
public class GetCompetitionDetailTool implements AgentTool {

    private final CompetitionRecommendationService recommendationService;

    public GetCompetitionDetailTool(CompetitionRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public String name() {
        return "get_competition_detail";
    }

    @Override
    public String description() {
        return "查看单个赛事的详情：报名状态、截止时间、级别、形式、面向对象、赛事介绍、报名链接和来源。"
                + "按 eventKey 精确查，或按名称（精确优先、包含匹配，多条时返回 candidates 供用户挑选）。"
                + "可选 includeFit=true 附带对该用户的适配信息（分档、为什么适合、还缺什么）。"
                + "赛事事实只能来自本工具返回的数据，不得自行编造。";
    }

    @Override
    public String displayDescription() {
        return "查看赛事详情";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("eventKey", Map.of("type", "string",
                "description", "赛事唯一键，例如 cddl_xxx 或 notice_xxx；与 name 至少提供一个"));
        properties.put("name", Map.of("type", "string",
                "description", "赛事名称，用于模糊查询；与 eventKey 至少提供一个"));
        properties.put("includeFit", Map.of("type", "boolean",
                "description", "是否附带对该用户的适配信息（默认 false）"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of(),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        String eventKey = AgentToolSupport.stringArg(arguments, "eventKey");
        String name = AgentToolSupport.stringArg(arguments, "name");
        if ((eventKey == null || eventKey.isBlank()) && (name == null || name.isBlank())) {
            return ToolResult.error("INVALID_ARGUMENTS", "eventKey 或 name 至少提供一个");
        }
        boolean includeFit = boolArg(arguments, "includeFit");

        Map<String, Object> detail = recommendationService.detail(
                context.userId().longValue(), eventKey, name, includeFit);

        Map<String, Object> data = new LinkedHashMap<>(detail);
        data.put("displayInstruction",
                "向用户解释时请使用中文：状态用中文标签、截止和来源照实转述；"
                        + "禁止输出 eventKey、tier、status、verificationStatus、daysLeft 等英文字段名；"
                        + "单源低置信必须提示「报名前请核对官网」；官网未给出截止时间时要如实说明。");
        return ToolResult.ok(data);
    }

    private boolean boolArg(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return false;
        }
        JsonElement value = arguments.get(name);
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
