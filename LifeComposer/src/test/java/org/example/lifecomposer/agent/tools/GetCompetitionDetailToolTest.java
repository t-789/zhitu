package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonObject;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.recommendation.competition.CompetitionRecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetCompetitionDetailToolTest {

    private final CompetitionRecommendationService service = mock(CompetitionRecommendationService.class);
    private final GetCompetitionDetailTool tool = new GetCompetitionDetailTool(service);

    private JsonObject args(String eventKey, String name, Boolean includeFit) {
        JsonObject arguments = new JsonObject();
        if (eventKey != null) {
            arguments.addProperty("eventKey", eventKey);
        }
        if (name != null) {
            arguments.addProperty("name", name);
        }
        if (includeFit != null) {
            arguments.addProperty("includeFit", includeFit);
        }
        return arguments;
    }

    @Test
    @DisplayName("工具名、展示名与 schema 元数据")
    void metadata() {
        assertEquals("get_competition_detail", tool.name());
        assertEquals("查看赛事详情", tool.displayDescription());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.parameterSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("eventKey"));
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("includeFit"));
    }

    @Test
    @DisplayName("eventKey 与 name 都缺返回 INVALID_ARGUMENTS")
    void missingBothIsInvalid() {
        ToolResult result = tool.execute(new AgentToolContext(1), new JsonObject());
        assertFalse(result.ok());
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
    }

    @Test
    @DisplayName("按 eventKey 委托服务并返回带 displayInstruction 的结果")
    void delegatesToServiceAndAddsDisplayInstruction() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("found", true);
        detail.put("eventKey", "cddl_x");
        detail.put("name", "蓝桥杯");
        when(service.detail(anyLong(), anyString(), any(), anyBoolean())).thenReturn(detail);

        ToolResult result = tool.execute(new AgentToolContext(1), args("cddl_x", null, true));

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(true, data.get("found"));
        assertEquals("蓝桥杯", data.get("name"));
        assertTrue(data.containsKey("displayInstruction"));
        assertTrue(((String) data.get("displayInstruction")).contains("报名前请核对官网"));
        verify(service).detail(anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("按 name 委托服务")
    void delegatesByName() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("found", true);
        detail.put("multiple", true);
        detail.put("candidates", java.util.List.of());
        when(service.detail(anyLong(), any(), anyString(), anyBoolean())).thenReturn(detail);

        ToolResult result = tool.execute(new AgentToolContext(1), args(null, "数学建模", false));

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(true, data.get("multiple"));
        verify(service).detail(anyLong(), any(), anyString(), anyBoolean());
    }
}
