package org.example.lifecomposer.Service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt contract tests: the catalog must load every versioned prompt, keep the
 * tool/confirmation boundary in the chat prompt, and require parseable JSON for
 * profile extraction.
 */
class PromptCatalogTest {

    private final PromptCatalog catalog = new PromptCatalog();

    @Test
    void loadsEveryPromptWithVersionAndContent() {
        Map<String, String> versions = catalog.versions();
        for (PromptId id : PromptId.values()) {
            assertTrue(versions.containsKey(id.name()), "missing prompt: " + id);
            assertNotNull(catalog.version(id));
            assertFalse(catalog.text(id).isBlank(), "empty prompt: " + id);
        }
        assertEquals("5", catalog.version(PromptId.CHAT_SYSTEM));
    }

    @Test
    void chatPromptKeepsToolAndConfirmationBoundary() {
        String prompt = catalog.text(PromptId.CHAT_SYSTEM);
        assertTrue(prompt.contains("propose_profile_update"));
        assertTrue(prompt.contains("不能在未确认时声称"));
        assertTrue(prompt.contains("只使用提供的工具") || prompt.contains("只能使用提供的工具"));
        assertTrue(prompt.contains("中文"));
        assertTrue(prompt.contains("不要泄露"));
    }

    @Test
    void profileExtractionPromptRequiresParseableJsonAndWhitelist() {
        String prompt = catalog.text(PromptId.PROFILE_EXTRACTION);
        assertTrue(prompt.contains("\"changes\""));
        assertTrue(prompt.contains("JSON"));
        assertTrue(prompt.contains("studentId") || prompt.contains("学号"));
        assertTrue(prompt.contains("availableTime"));
    }

    @Test
    void composerSelectsTaskPromptsAndExposesVersions() {
        PromptComposer composer = new PromptComposer(catalog);

        PromptComposer.ComposedPrompt chat = composer.forUserMessage("帮我推荐一个适合的方向");
        assertTrue(chat.versions().containsKey("PROFILE_EXTRACTION"));
        assertTrue(chat.versions().containsKey("DIRECTION_EXPLANATION"));
        assertTrue(chat.systemPrompt().contains("profile_extraction"));
        assertTrue(chat.systemPrompt().contains("direction_explanation"));

        PromptComposer.ComposedPrompt decision = composer.forDecisionContinuation();
        assertEquals(5, decision.versions().size());
        assertTrue(decision.systemPrompt().contains("path_suggestion"));
        String fingerprint = composer.fingerprint(decision.versions());
        assertTrue(fingerprint.contains("CHAT_SYSTEM=5"));
        assertTrue(fingerprint.contains("PROFILE_EXTRACTION=2"));
    }

    @Test
    void composerSelectsCompetitionPromptForCompetitionQuestions() {
        PromptComposer composer = new PromptComposer(catalog);

        // Competition strong words select the competition prompt, not the direction one.
        PromptComposer.ComposedPrompt competition = composer.forUserMessage("我想参加蓝桥杯");
        assertTrue(competition.versions().containsKey("COMPETITION_RECOMMENDATION"));
        assertFalse(competition.versions().containsKey("DIRECTION_EXPLANATION"));
        assertTrue(competition.systemPrompt().contains("competition_recommendation"));

        // bug8：赛事词 + “怎么准备”同时命中时，也应带上方向/路径 Prompt。
        PromptComposer.ComposedPrompt preparation = composer.forUserMessage("我想参加蓝桥杯，应该怎么准备？");
        assertTrue(preparation.versions().containsKey("COMPETITION_RECOMMENDATION"));
        assertTrue(preparation.versions().containsKey("DIRECTION_EXPLANATION"));
        assertTrue(preparation.systemPrompt().contains("path_suggestion"));

        // "推荐比赛" is a weak word + competition word → competition prompt only.
        PromptComposer.ComposedPrompt recommend = composer.forUserMessage("推荐比赛");
        assertTrue(recommend.versions().containsKey("COMPETITION_RECOMMENDATION"));
        assertFalse(recommend.versions().containsKey("DIRECTION_EXPLANATION"));

        // Direction question still loads the direction prompts.
        PromptComposer.ComposedPrompt direction = composer.forUserMessage("帮我推荐一个适合的方向");
        assertTrue(direction.versions().containsKey("DIRECTION_EXPLANATION"));
        assertFalse(direction.versions().containsKey("COMPETITION_RECOMMENDATION"));

        // Unrelated message loads neither.
        PromptComposer.ComposedPrompt unrelated = composer.forUserMessage("你好");
        assertFalse(unrelated.versions().containsKey("DIRECTION_EXPLANATION"));
        assertFalse(unrelated.versions().containsKey("COMPETITION_RECOMMENDATION"));
        assertTrue(unrelated.versions().containsKey("PROFILE_EXTRACTION"));
    }

    @Test
    void promptsForbidInternalKeyLeakage() {
        String chat = catalog.text(PromptId.CHAT_SYSTEM);
        assertTrue(chat.contains("goalRelevance"));
        assertTrue(chat.contains("scoreBreakdown"));
        assertTrue(chat.contains("不要把工具返回的 JSON 字段名"));
        assertTrue(chat.contains("不要用你自己的知识补全"));
        assertTrue(chat.contains("不要编造或推断资源名称"));
        String direction = catalog.text(PromptId.DIRECTION_EXPLANATION);
        assertTrue(direction.contains("goalRelevance"));
        assertTrue(direction.contains("中文自然语言"));
        assertTrue(direction.contains("不要推测或列出它具体哪些字段未确认"));
        String path = catalog.text(PromptId.PATH_SUGGESTION);
        assertTrue(path.contains("dataQuality"));
        assertTrue(path.contains("禁止原样输出"));
        assertTrue(path.contains("不要推测或列出具体哪些字段未确认"));
    }

    @Test
    void explanationPromptsDoNotInventScores() {
        String direction = catalog.text(PromptId.DIRECTION_EXPLANATION);
        assertTrue(direction.contains("scoreBreakdown"));
        assertTrue(direction.contains("不得重新发明评分规则") || direction.contains("不得新增"));
        String path = catalog.text(PromptId.PATH_SUGGESTION);
        assertTrue(path.contains("pathPlan"));
        assertTrue(path.contains("needs_review"));
    }
}
