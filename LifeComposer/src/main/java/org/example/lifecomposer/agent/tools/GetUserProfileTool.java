package org.example.lifecomposer.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import org.example.lifecomposer.Entity.UserProfile;
import org.example.lifecomposer.Repository.UserProfileRepository;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read the authenticated user's growth profile. Never accepts an arbitrary user id. */
@Component
public class GetUserProfileTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserProfileRepository userProfileRepository;

    public GetUserProfileTool(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    public String name() {
        return "get_user_profile";
    }

    @Override
    public String description() {
        return "查询当前登录用户的成长画像，包括专业、年级、技能、兴趣、经历、可投入时间和目标。";
    }

    @Override
    public String displayDescription() {
        return "读取当前用户画像";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        UserProfile profile = userProfileRepository.findByUserId(context.userId().longValue());
        if (profile == null) {
            return ToolResult.ok(Map.of("found", false, "message", "当前用户还没有填写画像"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("college", profile.getCollege());
        data.put("major", profile.getMajor());
        data.put("grade", profile.getGrade());
        data.put("availableTime", profile.getAvailableTime());
        data.put("skills", parseJson(profile.getSkillsJson()));
        data.put("interests", parseJson(profile.getInterestsJson()));
        data.put("experiences", parseJson(profile.getExperiencesJson()));
        data.put("preferences", parseJson(profile.getPreferencesJson()));
        data.put("goals", parseJson(profile.getGoals()));
        return ToolResult.ok(data);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
