package org.example.lifecomposer.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import org.example.lifecomposer.Exception.ProfileApiException;
import org.example.lifecomposer.Service.RecommendationFeedbackService;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.AgentToolSupport;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.dto.DirectionRecommendationDto;
import org.example.lifecomposer.dto.RecommendationFeedbackDto;
import org.example.lifecomposer.recommendation.RecommendationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** v0.1 M6: records structured feedback on a recommended direction. */
@Component
public class SubmitRecommendationFeedbackTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecommendationFeedbackService feedbackService;
    private final RecommendationService recommendationService;

    public SubmitRecommendationFeedbackTool(RecommendationFeedbackService feedbackService,
                                            RecommendationService recommendationService) {
        this.feedbackService = feedbackService;
        this.recommendationService = recommendationService;
    }

    @Override
    public String name() {
        return "submit_recommendation_feedback";
    }

    @Override
    public String description() {
        return "记录用户对某个成长方向推荐的反馈。反馈类型：useful（有用）、irrelevant（无关）、"
                + "too_hard（太难）、time_mismatch（时间不合适）、goal_changed（目标改变）。"
                + "只在用户明确表达反馈时调用，不要把普通聊天内容当成反馈。";
    }

    @Override
    public String displayDescription() {
        return "记录推荐反馈";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "directionId", Map.of("type", "string", "description", "成长方向 id"),
                        "feedbackType", Map.of("type", "string",
                                "enum", java.util.List.of("useful", "irrelevant", "too_hard", "time_mismatch", "goal_changed")),
                        "note", Map.of("type", "string", "description", "可选的补充说明")),
                "required", java.util.List.of("directionId", "feedbackType"),
                "additionalProperties", false);
    }

    private String snapshot(DirectionRecommendationDto recommendation) {
        if (recommendation == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("directionId", recommendation.getDirectionId());
            snapshot.put("score", recommendation.getScore());
            snapshot.put("classification", recommendation.getClassification());
            snapshot.put("matchedTags", recommendation.getMatchedTags());
            snapshot.put("missingTags", recommendation.getMissingTags());
            snapshot.put("scoringVersion", recommendation.getScoringVersion());
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        String directionId = AgentToolSupport.stringArg(arguments, "directionId");
        String feedbackType = AgentToolSupport.stringArg(arguments, "feedbackType");
        String note = AgentToolSupport.stringArg(arguments, "note");
        if (directionId == null || feedbackType == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "directionId 和 feedbackType 不能为空");
        }
        try {
            Long userId = context.userId().longValue();
            DirectionRecommendationDto recommendation =
                    recommendationService.recommendationFor(userId, directionId);
            if (recommendation == null) {
                return ToolResult.error("DIRECTION_NOT_FOUND", "成长方向不存在: " + directionId);
            }
            String scoringVersion = recommendation == null ? null : recommendation.getScoringVersion();
            RecommendationFeedbackDto dto = feedbackService.record(userId,
                    directionId, feedbackType, note, scoringVersion, snapshot(recommendation));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("recorded", true);
            data.put("directionId", dto.getDirectionId());
            data.put("feedbackType", dto.getFeedbackType());
            data.put("createdAt", dto.getCreatedAt());
            return ToolResult.ok(data);
        } catch (ProfileApiException e) {
            return ToolResult.error("INVALID_FEEDBACK", e.getMessage());
        }
    }
}
