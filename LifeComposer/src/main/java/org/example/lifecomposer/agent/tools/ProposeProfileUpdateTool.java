package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.lifecomposer.Entity.ProfileChangeCandidate;
import org.example.lifecomposer.Service.ProfileChangeService;
import org.example.lifecomposer.Exception.ProfileApiException;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.AgentToolSupport;
import org.example.lifecomposer.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-facing profile change proposal tool. It only creates
 * PENDING_CONFIRMATION candidates; it never writes the formal profile.
 */
@Component
public class ProposeProfileUpdateTool implements AgentTool {

    private final ProfileChangeService profileChangeService;

    public ProposeProfileUpdateTool(ProfileChangeService profileChangeService) {
        this.profileChangeService = profileChangeService;
    }

    @Override
    public String name() {
        return "propose_profile_update";
    }

    @Override
    public String description() {
        return "当用户在对话中明确提供了可投入时间、技能、兴趣、经历或目标，但尚未通过正式表单确认时，"
                + "调用本工具提出画像变更候选。工具不会直接写入正式画像，只创建待用户确认的候选。"
                + "不要提议学号、学院、专业、年级等身份字段。";
    }

    @Override
    public String displayDescription() {
        return "提议画像变更";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "object");
        change.put("properties", Map.of(
                "field", Map.of(
                        "type", "string",
                        "enum", List.of("availableTime", "skillsJson", "interestsJson", "experiencesJson", "goals")),
                "newValue", Map.of("type", "string", "description", "新值：可用时间文本，或 JSON 数组字符串"),
                "rationale", Map.of("type", "string", "description", "提议依据（引用用户原话/上下文）")));
        change.put("required", List.of("field", "newValue"));
        change.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "changes", Map.of("type", "array", "items", change),
                "rationale", Map.of("type", "string", "description", "整体提议依据")));
        schema.put("required", List.of("changes"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        if (arguments == null || !arguments.has("changes") || !arguments.get("changes").isJsonArray()) {
            return ToolResult.error("INVALID_ARGUMENTS", "changes 必须是数组");
        }
        String defaultRationale = AgentToolSupport.stringArg(arguments, "rationale");
        JsonArray changes = arguments.getAsJsonArray("changes");
        List<ProfileChangeService.ProfileChangeProposal> proposals = new ArrayList<>();
        for (JsonElement element : changes) {
            if (!element.isJsonObject()) {
                return ToolResult.error("INVALID_ARGUMENTS", "changes 的元素必须是对象");
            }
            JsonObject item = element.getAsJsonObject();
            String field = AgentToolSupport.stringArg(item, "field");
            String newValue = AgentToolSupport.stringArg(item, "newValue");
            if (field == null || newValue == null) {
                return ToolResult.error("INVALID_ARGUMENTS", "每个变更都需要 field 和 newValue");
            }
            proposals.add(new ProfileChangeService.ProfileChangeProposal(
                    field, newValue, AgentToolSupport.stringArg(item, "rationale")));
        }

        try {
            List<ProfileChangeCandidate> created = profileChangeService.propose(
                    context.userId().longValue(), proposals, defaultRationale);
            return ToolResult.ok(profileChangeService.toToolPayload(created));
        } catch (ProfileApiException e) {
            // Business-rule rejections carry curated Chinese text. Anything else
            // propagates to ToolRegistry, which hides the details from the model.
            return ToolResult.error("PROFILE_PROPOSAL_REJECTED", e.getMessage());
        }
    }
}
