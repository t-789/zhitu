package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonObject;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.dto.CompetitionRecommendationResult;
import org.example.lifecomposer.recommendation.competition.CompetitionRecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendCompetitionsToolTest {

    private final CompetitionRecommendationService service = mock(CompetitionRecommendationService.class);
    private final RecommendCompetitionsTool tool = new RecommendCompetitionsTool(service);

    private JsonObject args(String query) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", query);
        return arguments;
    }

    @Test
    @DisplayName("工具名、展示名与 schema 元数据")
    void metadata() {
        assertEquals("recommend_competitions", tool.name());
        assertEquals("推荐赛事", tool.displayDescription());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.parameterSchema();
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("query"), schema.get("required"));
        assertTrue(tool.description().contains("画像"));
    }

    @Test
    @DisplayName("缺 query 返回 INVALID_ARGUMENTS")
    void missingQueryIsInvalid() {
        ToolResult result = tool.execute(new AgentToolContext(1), new JsonObject());
        assertFalse(result.ok());
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
    }

    @Test
    @DisplayName("正常调用转发服务并返回带 displayInstruction 的结果")
    void delegatesToServiceAndAddsDisplayInstruction() {
        CompetitionRecommendationResult serviceResult = new CompetitionRecommendationResult();
        serviceResult.setCount(1);
        when(service.recommend(anyLong(), anyString(), any(), any(), anyBoolean(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(serviceResult);

        ToolResult result = tool.execute(new AgentToolContext(1), args("算法比赛"));

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(1, data.get("count"));
        assertTrue(data.containsKey("displayInstruction"));
        assertTrue(((String) data.get("displayInstruction")).contains("报名前请核对官网"));
        verify(service).recommend(anyLong(), anyString(), any(), any(), anyBoolean(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("schema 只含白名单参数与枚举")
    void schemaOnlyWhitelistsParameters() {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.parameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("scope"));
        assertTrue(properties.containsKey("eventType"));
        assertTrue(properties.containsKey("includeOngoing"));
        assertTrue(properties.containsKey("deadlineWithinDays"));
        assertTrue(properties.containsKey("level"));
        assertTrue(properties.containsKey("mode"));
        assertTrue(properties.containsKey("excludeKeywords"));
        assertTrue(properties.containsKey("excludeEventKeys"));
        assertTrue(properties.containsKey("limit"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }
}
