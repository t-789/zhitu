package org.example.lifecomposer.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.1 M3: composes the system prompt from versioned catalog entries and keeps
 * the version map that is attached to agent responses and persisted messages.
 *
 * <p>Normal chat turns always carry the profile-extraction instructions because
 * the agent may need to propose profile candidates. Direction/path questions
 * additionally carry the direction-explanation and path-suggestion contracts;
 * competition questions carry the competition-recommendation contract; decision
 * continuations carry all five.
 */
public class PromptComposer {

    /**
     * Direction/path recommendation words. "推荐"/"适合" are weak: when a
     * competition strong word is present they do not force the direction prompt.
     */
    private static final List<String> DIRECTION_KEYWORDS = List.of(
            "推荐", "方向", "路径", "适合", "为什么", "差哪", "缺少", "提升", "规划",
            "学习路线", "入门", "刷题", "拿奖", "技能", "能力", "缺什么", "差什么",
            "准备", "怎么准备", "如何准备", "备考");

    /** Direction-specific words, excluding the weak words "推荐"/"适合". */
    private static final List<String> DIRECTION_STRONG_KEYWORDS = List.of(
            "方向", "路径", "为什么", "差哪", "缺少", "提升", "规划",
            "学习路线", "入门", "刷题", "拿奖", "技能", "能力", "缺什么", "差什么",
            "准备", "怎么准备", "如何准备", "备考");

    /** Competition-specific words that select the competition recommendation prompt. */
    private static final List<String> COMPETITION_KEYWORDS = List.of(
            "比赛", "赛事", "竞赛", "参赛", "报名", "截止", "能报", "有什么赛",
            "蓝桥杯", "ACM", "ICPC", "数模", "数学建模", "电赛", "挑战杯", "互联网+",
            "大创", "黑客松", "hackathon");

    private final PromptCatalog catalog;

    public PromptComposer(PromptCatalog catalog) {
        this.catalog = catalog;
    }

    public record ComposedPrompt(String systemPrompt, Map<String, String> versions) {
    }

    public ComposedPrompt forUserMessage(String userMessage) {
        LinkedHashMap<PromptId, Boolean> selected = new LinkedHashMap<>();
        selected.put(PromptId.CHAT_SYSTEM, true);
        selected.put(PromptId.PROFILE_EXTRACTION, true);
        boolean competition = matches(userMessage, COMPETITION_KEYWORDS);
        boolean directionStrong = matches(userMessage, DIRECTION_STRONG_KEYWORDS);
        boolean directionAny = matches(userMessage, DIRECTION_KEYWORDS);
        if (competition) {
            selected.put(PromptId.COMPETITION_RECOMMENDATION, true);
            // "推荐"/"适合" are weak words; only a direction-specific word
            // additionally loads the direction prompt alongside the competition one.
            if (directionStrong) {
                selected.put(PromptId.DIRECTION_EXPLANATION, true);
                selected.put(PromptId.PATH_SUGGESTION, true);
            }
        } else if (directionAny) {
            selected.put(PromptId.DIRECTION_EXPLANATION, true);
            selected.put(PromptId.PATH_SUGGESTION, true);
        }
        return compose(selected.keySet().stream().toList());
    }

    public ComposedPrompt forDecisionContinuation() {
        return compose(List.of(
                PromptId.CHAT_SYSTEM,
                PromptId.PROFILE_EXTRACTION,
                PromptId.DIRECTION_EXPLANATION,
                PromptId.PATH_SUGGESTION,
                PromptId.COMPETITION_RECOMMENDATION));
    }

    /**
     * Contract appended when a recommendation/path tool was actually called,
     * even if the user's original wording did not match the keyword heuristic.
     */
    public ComposedPrompt recommendationContract() {
        return compose(List.of(PromptId.DIRECTION_EXPLANATION, PromptId.PATH_SUGGESTION));
    }

    /**
     * Contract appended when a competition tool was actually called, even if the
     * user's original wording did not match the competition keyword heuristic.
     */
    public ComposedPrompt competitionContract() {
        return compose(List.of(PromptId.COMPETITION_RECOMMENDATION));
    }

    public ComposedPrompt forProfileExtractionOnly() {
        return compose(List.of(PromptId.CHAT_SYSTEM, PromptId.PROFILE_EXTRACTION));
    }

    /** Stable fingerprint stored on assistant messages for traceability. */
    public String fingerprint(Map<String, String> versions) {
        StringBuilder builder = new StringBuilder();
        for (PromptId id : PromptId.values()) {
            String version = versions.get(id.name());
            if (version == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(id.name()).append('=').append(version);
        }
        return builder.toString();
    }

    private boolean matches(String message, List<String> keywords) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private ComposedPrompt compose(List<PromptId> ids) {
        StringBuilder builder = new StringBuilder();
        Map<String, String> versions = new LinkedHashMap<>();
        for (PromptId id : ids) {
            String version = catalog.version(id);
            versions.put(id.name(), version);
            builder.append("## ").append(id.name()).append(" [").append(id.fileName())
                    .append("] (v").append(version).append(")\n");
            builder.append(catalog.text(id)).append("\n\n");
        }
        return new ComposedPrompt(builder.toString().trim(), versions);
    }
}
