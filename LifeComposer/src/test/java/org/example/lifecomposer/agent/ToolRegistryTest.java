package org.example.lifecomposer.agent;

import com.google.gson.JsonObject;
import org.example.lifecomposer.dto.LlmToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void exposesDefinitionsAndExecutesKnownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));

        List<LlmToolDefinition> definitions = registry.definitions();
        assertEquals(1, definitions.size());
        assertEquals("echo_tool", definitions.get(0).getName());

        ToolResult result = registry.execute("echo_tool", new AgentToolContext(7), "{\"value\":\"hi\"}");
        assertTrue(result.ok());
        assertEquals("hi", ((Map<?, ?>) result.data()).get("echo"));
    }

    @Test
    void rejectsUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolResult result = registry.execute("missing", new AgentToolContext(1), "{}");
        assertFalse(result.ok());
        assertEquals("UNKNOWN_TOOL", result.errorCode());
    }

    @Test
    void rejectsInvalidJsonArguments() {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        ToolResult result = registry.execute("echo_tool", new AgentToolContext(1), "not-json");
        assertFalse(result.ok());
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
    }

    @Test
    void rejectsValueOutsideEnumAndListsAllowedValues() {
        ToolRegistry registry = new ToolRegistry(List.of(new StrictTool()));
        ToolResult result = registry.execute("strict_tool", new AgentToolContext(1), "{\"type\":\"比赛\"}");
        assertFalse(result.ok());
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
        assertTrue(result.message().contains("competition"), result.message());
        assertTrue(result.message().contains("比赛"), result.message());
    }

    @Test
    void rejectsMissingRequiredUnknownAndWrongTypeArguments() {
        ToolRegistry registry = new ToolRegistry(List.of(new StrictTool()));

        ToolResult missing = registry.execute("strict_tool", new AgentToolContext(1), "{}");
        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertTrue(missing.message().contains("缺少必填参数 type"), missing.message());

        ToolResult unknown = registry.execute("strict_tool", new AgentToolContext(1),
                "{\"type\":\"course\",\"foo\":1}");
        assertEquals("INVALID_ARGUMENTS", unknown.errorCode());
        assertTrue(unknown.message().contains("未知参数 foo"), unknown.message());

        ToolResult wrongType = registry.execute("strict_tool", new AgentToolContext(1),
                "{\"type\":\"course\",\"limit\":\"5\"}");
        assertEquals("INVALID_ARGUMENTS", wrongType.errorCode());
        assertTrue(wrongType.message().contains("limit"), wrongType.message());

        ToolResult fractional = registry.execute("strict_tool", new AgentToolContext(1),
                "{\"type\":\"course\",\"limit\":2.5}");
        assertEquals("INVALID_ARGUMENTS", fractional.errorCode());
    }

    @Test
    void validatesNestedArrayItems() {
        ToolRegistry registry = new ToolRegistry(List.of(new StrictTool()));
        ToolResult result = registry.execute("strict_tool", new AgentToolContext(1),
                "{\"type\":\"course\",\"changes\":[{\"field\":\"goals\"},{\"field\":\"bogus\",\"newValue\":\"x\"}]}");
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
        assertTrue(result.message().contains("changes[0].newValue"), result.message());
        assertTrue(result.message().contains("changes[1].field"), result.message());
    }

    @Test
    void acceptsValidArgumentsAndTreatsNullOptionalAsAbsent() {
        ToolRegistry registry = new ToolRegistry(List.of(new StrictTool()));
        ToolResult result = registry.execute("strict_tool", new AgentToolContext(1),
                "{\"type\":\"course\",\"limit\":3,\"note\":null,\"changes\":[{\"field\":\"goals\",\"newValue\":\"x\"}]}");
        assertTrue(result.ok(), result.message());
    }

    @Test
    void doesNotLeakExceptionDetailsToTheModel() {
        ToolRegistry registry = new ToolRegistry(List.of(new ThrowingTool()));
        ToolResult result = registry.execute("throwing_tool", new AgentToolContext(1), "{}");
        assertFalse(result.ok());
        assertEquals("TOOL_EXECUTION_ERROR", result.errorCode());
        assertFalse(result.message().contains("SELECT"), result.message());
        assertFalse(result.message().contains("rag_chunks"), result.message());
        assertFalse(result.message().contains("/Users/"), result.message());
    }

    @Test
    void invalidJsonMessageDoesNotEchoParserDetails() {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        ToolResult result = registry.execute("echo_tool", new AgentToolContext(1), "{\"value\": nope");
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
        assertFalse(result.message().contains("line 1"), result.message());
        assertFalse(result.message().contains("Gson"), result.message());
    }

    private static final class ThrowingTool implements AgentTool {
        @Override
        public String name() {
            return "throwing_tool";
        }

        @Override
        public String description() {
            return "throws";
        }

        @Override
        public String displayDescription() {
            return "抛异常";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            throw new IllegalStateException("SELECT * FROM rag_chunks failed at /Users/liuzy/data.db");
        }
    }

    private static final class StrictTool implements AgentTool {
        @Override
        public String name() {
            return "strict_tool";
        }

        @Override
        public String description() {
            return "strict";
        }

        @Override
        public String displayDescription() {
            return "严格工具";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            Map<String, Object> change = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "field", Map.of("type", "string", "enum", List.of("goals", "interestsJson")),
                            "newValue", Map.of("type", "string")),
                    "required", List.of("field", "newValue"),
                    "additionalProperties", false);
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "type", Map.of("type", "string", "enum", List.of("competition", "course")),
                            "limit", Map.of("type", "integer"),
                            "note", Map.of("type", "string"),
                            "changes", Map.of("type", "array", "items", change)),
                    "required", List.of("type"),
                    "additionalProperties", false);
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            return ToolResult.ok(Map.of("ok", true));
        }
    }

    private static final class EchoTool implements AgentTool {
        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public String displayDescription() {
            return "回显工具";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")));
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            String value = arguments.has("value") ? arguments.get("value").getAsString() : null;
            return ToolResult.ok(Map.of("echo", value == null ? "" : value));
        }
    }
}
