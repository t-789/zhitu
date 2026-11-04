package org.example.lifecomposer.agent;

import org.example.lifecomposer.Entity.CapabilityReference;
import org.example.lifecomposer.Entity.CapabilityTag;
import org.example.lifecomposer.Entity.Resource;
import org.example.lifecomposer.Entity.UserProfile;
import org.example.lifecomposer.Repository.CapabilityReferenceRepository;
import org.example.lifecomposer.Repository.CapabilityTagRepository;
import org.example.lifecomposer.Repository.ResourceRepository;
import org.example.lifecomposer.Repository.UserProfileRepository;
import org.example.lifecomposer.controller.ReferenceDictionarySamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.1 M6: the recommendation/feedback tools are registered and return structured data. */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationAgentToolsTest {

    private static final long USER_ID = 24680L;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private CapabilityTagRepository capabilityTagRepository;

    @Autowired
    private CapabilityReferenceRepository capabilityReferenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void fixtures() {
        jdbcTemplate.execute("DELETE FROM capability_reference");
        jdbcTemplate.execute("DELETE FROM capability_tags");
        jdbcTemplate.execute("DELETE FROM resources");
        jdbcTemplate.execute("DELETE FROM user_profiles");
        jdbcTemplate.execute("DELETE FROM recommendation_feedback");
        for (CapabilityTag tag : ReferenceDictionarySamples.capabilityTags()) {
            capabilityTagRepository.upsert(tag);
        }
        for (CapabilityReference reference : ReferenceDictionarySamples.capabilityReferences()) {
            capabilityReferenceRepository.upsert(reference);
        }
        for (Resource resource : ReferenceDictionarySamples.resources()) {
            resourceRepository.upsert(resource);
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(USER_ID);
        profile.setSkillsJson("[\"Python基础\",\"算法\",\"LeetCode\"]");
        profile.setAvailableTime("8 hours/week");
        profile.setGoals("[\"参加算法竞赛\"]");
        profile.setPreferencesJson("{\"学习方式\":\"组队\"}");
        userProfileRepository.upsert(profile);
    }

    @Test
    @DisplayName("get_user_profile exposes preferences (F layer)")
    void profileToolIncludesPreferences() {
        ToolResult result = toolRegistry.execute("get_user_profile",
                new AgentToolContext((int) USER_ID), "{}");
        assertTrue(result.ok());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals("组队", ((Map<?, ?>) data.get("preferences")).get("学习方式"));
    }

    @Test
    @DisplayName("list_growth_directions returns scored candidates with breakdown")
    void listGrowthDirections() {
        ToolResult result = toolRegistry.execute("list_growth_directions",
                new AgentToolContext((int) USER_ID), "{}");
        assertTrue(result.ok());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals(4, data.get("count"));
        assertNotNull(data.get("recommendations"));
    }

    @Test
    @DisplayName("get_recommendation_reasons explains the top score")
    void recommendationReasons() {
        ToolResult result = toolRegistry.execute("get_recommendation_reasons",
                new AgentToolContext((int) USER_ID), "{\"directionId\":\"algorithm_contest\"}");
        assertTrue(result.ok());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals("algorithm_contest", data.get("directionId"));
        assertNotNull(data.get("matchedTags"));
        assertFalse(data.containsKey("scoreBreakdown"), "不应把原始评分键暴露给 LLM");
        assertNotNull(data.get("explanationHints"));
        assertTrue(((Map<?, ?>) data.get("explanationHints")).containsKey("目标相关性"));
    }

    @Test
    @DisplayName("get_capability_gap and get_path_plan return structured plans")
    void gapAndPath() {
        ToolResult gap = toolRegistry.execute("get_capability_gap",
                new AgentToolContext((int) USER_ID), "{\"directionId\":\"algorithm_contest\"}");
        assertTrue(gap.ok());
        Map<?, ?> gapData = (Map<?, ?>) gap.data();
        assertTrue(((java.util.List<?>) gapData.get("已匹配标签")).contains("编程基础"));

        ToolResult path = toolRegistry.execute("get_path_plan",
                new AgentToolContext((int) USER_ID), "{\"directionId\":\"algorithm_contest\"}");
        assertTrue(path.ok());
        Map<?, ?> plan = (Map<?, ?>) path.data();
        assertEquals("algorithm_contest", plan.get("directionId"));
        assertNotNull(plan.get("gapTasks"));
        assertNotNull(plan.get("resources"));
        java.util.List<?> resources = (java.util.List<?>) plan.get("resources");
        assertFalse(resources.isEmpty());
        Map<?, ?> resource = (Map<?, ?>) resources.get(0);
        assertTrue(resource.containsKey("数据质量"), "资源数据质量应使用中文键");
        assertFalse(resource.containsKey("dataQuality"), "不应向 LLM 暴露 dataQuality 字段名");
    }

    @Test
    @DisplayName("submit_recommendation_feedback records a structured feedback row")
    void submitFeedback() {
        ToolResult result = toolRegistry.execute("submit_recommendation_feedback",
                new AgentToolContext((int) USER_ID),
                "{\"directionId\":\"algorithm_contest\",\"feedbackType\":\"useful\",\"note\":\"有帮助\"}");
        assertTrue(result.ok());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_feedback WHERE user_id = ?", Long.class, USER_ID);
        assertEquals(1L, count);
    }
}