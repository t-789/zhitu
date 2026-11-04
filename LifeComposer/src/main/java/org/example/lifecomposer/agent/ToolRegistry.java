package org.example.lifecomposer.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.dto.LlmToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** White-listed tool registry. The model can never execute arbitrary SQL/code. */
@Service
public class ToolRegistry {

    private static final Logger LOG = LogManager.getLogger(ToolRegistry.class);
    private static final Gson GSON = new Gson();

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans) {
        if (toolBeans != null) {
            for (AgentTool tool : toolBeans) {
                tools.put(tool.name(), tool);
            }
        }
    }

    public List<LlmToolDefinition> definitions() {
        List<LlmToolDefinition> definitions = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            definitions.add(new LlmToolDefinition(tool.name(), tool.description(), tool.parameterSchema()));
        }
        return definitions;
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public String displayDescription(String name) {
        AgentTool tool = tools.get(name);
        return tool != null ? tool.displayDescription() : "未知工具 " + name;
    }

    public ToolResult execute(String name, AgentToolContext context, String argumentsJson) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("UNKNOWN_TOOL", "未知工具: " + name);
        }
        JsonObject arguments = new JsonObject();
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                arguments = GSON.fromJson(argumentsJson, JsonObject.class);
                if (arguments == null) {
                    arguments = new JsonObject();
                }
            } catch (RuntimeException e) {
                LOG.warn("Tool {} received unparseable arguments: {}", name, e.getClass().getSimpleName());
                return ToolResult.error("INVALID_ARGUMENTS", "工具参数不是合法的 JSON 对象");
            }
        }
        List<String> problems = ToolArgumentValidator.validate(tool.parameterSchema(), arguments);
        if (!problems.isEmpty()) {
            LOG.info("Tool {} rejected arguments: {}", name, problems);
            return ToolResult.error("INVALID_ARGUMENTS", "参数不合法：" + String.join("；", problems));
        }
        try {
            ToolResult result = tool.execute(context, arguments);
            return result != null ? result : ToolResult.error("EMPTY_TOOL_RESULT", "工具没有返回结果");
        } catch (RuntimeException e) {
            LOG.error("Tool {} execution failed", name, e);
            // Exception text may contain SQL, table names or paths: log it, never hand it to the model.
            return ToolResult.error("TOOL_EXECUTION_ERROR", "工具执行失败，请换个问法或稍后再试");
        }
    }
}
