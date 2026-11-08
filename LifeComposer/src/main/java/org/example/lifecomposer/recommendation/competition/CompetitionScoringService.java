package org.example.lifecomposer.recommendation.competition;

import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.Service.CapabilityNormalizationService;
import org.example.lifecomposer.Service.ContestStatusCalculator;
import org.example.lifecomposer.config.CompetitionProperties;
import org.example.lifecomposer.embedding.EmbeddingClient;
import org.example.lifecomposer.embedding.EmbeddingException;
import org.example.lifecomposer.embedding.EmbeddingResult;
import org.example.lifecomposer.recommendation.GrowthDirection;
import org.example.lifecomposer.recommendation.GrowthDirectionCatalog;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 赛事推荐的确定性核心：硬过滤 + 六维评分 + 分档 + 多样性。
 *
 * <p>赛事事实只来自 {@link ContestEvent}；状态由 {@link ContestStatusCalculator} 按 now 重算，
 * 不读 status_snapshot；embedding 不可用时降级为纯关键词。
 */
@Service
public class CompetitionScoringService {

    /** 通用可迁移能力标签（跨赛事可复用）。 */
    private static final Set<String> TRANSFERABLE_TAGS = Set.of(
            "文档写作", "表达展示", "团队协作", "项目经验", "英语阅读");

    /** 名称含这些词时总分减 0.10（低质现金/投票类）。 */
    private static final List<String> PENALTY_KEYWORDS = List.of(
            "知识竞答", "答题", "现金奖励", "投票", "人气", "抽奖", "集赞");

    private static final List<String> CATEGORY_PROFILE = List.of(
            // 顺序即匹配优先级。
            "algorithm_contest::算法,程序设计,蓝桥杯,ACM,ICPC,天梯,编程",
            "modeling_contest::数模,数学建模,建模",
            "security_contest::网络安全,信安,CTF,攻防",
            "ai_contest::人工智能,AI,大模型,大数据,数据分析,机器学习,深度学习",
            "hardware_contest::电子,嵌入式,机器人,机械,硬件,物联网",
            "innovation_contest::创新创业,挑战杯,互联网+,大创,创业,商业计划",
            "design_contest::设计,文创,工业设计");

    private final CompetitionProperties properties;
    private final ContestStatusCalculator statusCalculator;
    private final CapabilityNormalizationService normalizationService;
    private final GrowthDirectionCatalog directionCatalog;
    private final EmbeddingClient embeddingClient;

    public CompetitionScoringService(CompetitionProperties properties,
                                     ContestStatusCalculator statusCalculator,
                                     CapabilityNormalizationService normalizationService,
                                     GrowthDirectionCatalog directionCatalog,
                                     EmbeddingClient embeddingClient) {
        this.properties = properties;
        this.statusCalculator = statusCalculator;
        this.normalizationService = normalizationService;
        this.directionCatalog = directionCatalog;
        this.embeddingClient = embeddingClient;
    }

    /** 画像快照（只取推荐所需的维度）。 */
    public record ProfileSnapshot(String grade, String major, List<String> skills,
                                  List<String> interests, List<String> goals,
                                  Double availableHoursPerWeek) {
        public ProfileSnapshot {
            skills = skills == null ? List.of() : List.copyOf(skills);
            interests = interests == null ? List.of() : List.copyOf(interests);
            goals = goals == null ? List.of() : List.copyOf(goals);
        }
    }

    /** 合并后的请求（显式参数 + query 解析 + 默认）。 */
    public record Request(String scope, String eventType, boolean includeOngoing,
                          Integer deadlineWithinDays, String level, String mode,
                          List<String> excludeKeywords, List<String> excludeEventKeys,
                          int limit, String gradeHint, List<String> interestKeywords,
                          boolean beginner, Double availableHours) {
        public Request {
            excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
            excludeEventKeys = excludeEventKeys == null ? List.of() : List.copyOf(excludeEventKeys);
            interestKeywords = interestKeywords == null ? List.of() : List.copyOf(interestKeywords);
        }
    }

    /** 单条打分级结果（内部），由 RecommendationService 转 DTO。 */
    public static final class Scored {
        private final ContestEvent event;
        private final ContestStatusCalculator.StatusResult status;
        private final double total;
        private final double skillFit;
        private final double difficultyFit;
        private final String tier;
        private final String relatedDirection;
        private final List<String> fitReasons;
        private final List<String> gapSkills;
        private final List<String> startPoints;
        private final List<String> riskNotes;
        private final String nextAction;
        private final String primaryCategory;

        Scored(ContestEvent event, ContestStatusCalculator.StatusResult status, double total,
               double skillFit, double difficultyFit, String tier, String relatedDirection,
               List<String> fitReasons, List<String> gapSkills, List<String> startPoints,
               List<String> riskNotes, String nextAction, String primaryCategory) {
            this.event = event;
            this.status = status;
            this.total = total;
            this.skillFit = skillFit;
            this.difficultyFit = difficultyFit;
            this.tier = tier;
            this.relatedDirection = relatedDirection;
            this.fitReasons = fitReasons;
            this.gapSkills = gapSkills;
            this.startPoints = startPoints;
            this.riskNotes = riskNotes;
            this.nextAction = nextAction;
            this.primaryCategory = primaryCategory;
        }

        public ContestEvent event() {
            return event;
        }

        public ContestStatusCalculator.StatusResult status() {
            return status;
        }

        public double total() {
            return total;
        }

        public double skillFit() {
            return skillFit;
        }

        public double difficultyFit() {
            return difficultyFit;
        }

        public String tier() {
            return tier;
        }

        public String relatedDirection() {
            return relatedDirection;
        }

        public List<String> fitReasons() {
            return fitReasons;
        }

        public List<String> gapSkills() {
            return gapSkills;
        }

        public List<String> startPoints() {
            return startPoints;
        }

        public List<String> riskNotes() {
            return riskNotes;
        }

        public String nextAction() {
            return nextAction;
        }

        public String primaryCategory() {
            return primaryCategory;
        }
    }

    /** 排序结果：final top + 候选数（用于 filterStats）。 */
    public record Ranking(List<Scored> items, int total, int afterHardFilter) {
    }

    public Ranking rank(List<ContestEvent> events, ProfileSnapshot profile, Request req, Instant now) {
        int total = events.size();

        // 1. 硬过滤。
        List<ContestEvent> hardFiltered = new ArrayList<>();
        for (ContestEvent e : events) {
            if (hardFilter(e, profile, req, now)) {
                hardFiltered.add(e);
            }
        }

        // 2. 计算当前方向（一次性）。
        GrowthDirection currentDirection = currentDirection(profile);

        // 3. query embedding 一次性计算。
        String queryText = queryText(profile, req);
        List<Double> queryVector = embedOrNull(queryText);

        // 4. 打分。
        List<Scored> scored = new ArrayList<>();
        for (ContestEvent e : hardFiltered) {
            scored.add(scoreOne(e, profile, req, now, currentDirection, queryVector));
        }

        // 5. 排序：总分降序，平局按来源可信度（官网 > 官方通知 > 其他 > 单源）。
        scored.sort(Comparator
                .comparingDouble((Scored s) -> -s.total())
                .thenComparingInt(s -> verificationRank(s.event().getVerificationStatus()))
                .thenComparing(s -> s.event().getName() == null ? "" : s.event().getName()));

        // 6. 多样性 + 截断。
        List<Scored> top = diversify(scored, profile, req.limit(), currentDirection);

        return new Ranking(top, total, hardFiltered.size());
    }

    // ------------------------------------------------------------------
    // 硬过滤
    // ------------------------------------------------------------------

    private boolean hardFilter(ContestEvent e, ProfileSnapshot profile, Request req, Instant now) {
        // 已归档（findActive 已过滤，这里双保险）；stale 硬过滤。
        if (e.getArchived() != null && e.getArchived() == 1) {
            return false;
        }
        if (e.getStale() != null && e.getStale() == 1) {
            return false;
        }

        // scope。
        String scope = req.scope() == null || req.scope().isBlank() ? "china" : req.scope();
        if (!"all".equals(scope) && !scope.equals(e.getScope())) {
            return false;
        }

        // event_type：默认只留 competition/hackathon/campus_notice。
        if (!matchType(e, req.eventType())) {
            return false;
        }

        // 状态重算。
        ContestStatusCalculator.StatusResult r = statusCalculator.compute(e, now);
        if (!matchStatus(r.status(), req, e)) {
            return false;
        }

        // 截止：nextDeadline 必须 >= now（已结束不算）。
        if (r.nextDeadline() == null && !"notice".equals(e.getEventType())) {
            // 无截止的竞赛只允许官网条目（在 tier 阶段限制 ≤1），这里先放行。
        }

        // deadlineWithinDays。
        if (req.deadlineWithinDays() != null && r.daysLeft() != null
                && r.daysLeft() > req.deadlineWithinDays()) {
            return false;
        }

        // level。
        if (req.level() != null && !req.level().isBlank() && !"all".equals(req.level())) {
            if (!req.level().equals(e.getLevel()) && !levelMatches(req.level(), e.getLevel())) {
                return false;
            }
        }

        // mode：明确线上则排除线下（null 不硬排）。
        if (req.mode() != null && !req.mode().isBlank()) {
            if (!modeCompatible(req.mode(), e.getMode())) {
                return false;
            }
        }

        // excludeEventKeys。
        if (req.excludeEventKeys().contains(e.getEventKey())) {
            return false;
        }

        // excludeKeywords：名称/类别/来源不包含排除词。
        if (!req.excludeKeywords().isEmpty() && containsAnyExcluded(e, req.excludeKeywords())) {
            return false;
        }

        // 年级硬限制：eligibility 明确“仅研究生/仅本科”时按 gradeHint 过滤。
        if (!gradeAllowed(e.getEligibility(), effectiveGrade(profile, req))) {
            return false;
        }

        return true;
    }

    private boolean matchType(ContestEvent e, String eventType) {
        if (eventType == null || eventType.isBlank() || "all".equals(eventType)) {
            return "competition".equals(e.getEventType())
                    || "hackathon".equals(e.getEventType())
                    || "notice".equals(e.getEventType());
        }
        String t = "campus_notice".equals(eventType) ? "notice" : eventType;
        return t.equals(e.getEventType());
    }

    private boolean matchStatus(String status, Request req, ContestEvent e) {
        if (ContestStatusCalculator.ACTIVE.contains(status)) {
            if ("notice".equals(e.getEventType())) {
                // 校内通知放行 submission_open。
                return "registration_open".equals(status)
                        || "registration_upcoming".equals(status)
                        || "submission_open".equals(status);
            }
            return "registration_open".equals(status) || "registration_upcoming".equals(status);
        }
        // includeOngoing 时才放行 ongoing。
        return req.includeOngoing() && "ongoing".equals(status);
    }

    private boolean levelMatches(String requested, String actual) {
        if (actual == null) {
            return false;
        }
        // "campus" 请求时兼容 "university"（都是校级语义）。
        if ("campus".equals(requested)) {
            return "campus".equals(actual) || "university".equals(actual);
        }
        return requested.equals(actual);
    }

    private boolean modeCompatible(String requested, String actual) {
        if (actual == null || actual.isBlank()) {
            return true; // 未标注不硬排。
        }
        if (requested.equals(actual)) {
            return true;
        }
        if ("hybrid".equals(requested)) {
            return true; // 线上线下都能接受。
        }
        if ("hybrid".equals(actual)) {
            return true;
        }
        return false; // online 与 offline 冲突。
    }

    private boolean containsAnyExcluded(ContestEvent e, List<String> excludeKeywords) {
        StringBuilder hay = new StringBuilder();
        append(hay, e.getName());
        append(hay, e.getCategoriesJson());
        append(hay, e.getTagsJson());
        append(hay, e.getSourceName());
        String lower = hay.toString().toLowerCase(Locale.ROOT);
        for (String keyword : excludeKeywords) {
            if (!keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean gradeAllowed(String eligibility, String grade) {
        if (eligibility == null || eligibility.isBlank() || grade == null) {
            return true;
        }
        String lower = eligibility.toLowerCase(Locale.ROOT);
        boolean onlyGraduate = lower.contains("仅研究生") || lower.contains("仅限研究生")
                || lower.contains("研究生方可") || lower.contains("只面向研究生");
        boolean onlyUndergraduate = lower.contains("仅本科生") || lower.contains("仅限本科生")
                || lower.contains("本科生方可") || lower.contains("只面向本科生");
        if (onlyGraduate && !"graduate".equals(grade)) {
            return false;
        }
        if (onlyUndergraduate && !"undergraduate".equals(grade)) {
            return false;
        }
        return true;
    }

    private String effectiveGrade(ProfileSnapshot profile, Request req) {
        if (req.gradeHint() != null) {
            return req.gradeHint();
        }
        if (profile.grade() == null || profile.grade().isBlank()) {
            return null;
        }
        String lower = profile.grade().toLowerCase(Locale.ROOT);
        if (lower.contains("研")) {
            return "graduate";
        }
        if (lower.contains("大")) {
            return "undergraduate";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 打分
    // ------------------------------------------------------------------

    private Scored scoreOne(ContestEvent e, ProfileSnapshot profile, Request req, Instant now,
                            GrowthDirection currentDirection, List<Double> queryVector) {
        ContestStatusCalculator.StatusResult status = statusCalculator.compute(e, now);

        String profileKey = skillProfileKey(e);
        List<String> required = requiredSkills(profileKey);
        List<String> userSkills = profile.skills();

        double skillFit = skillFit(userSkills, required, profileKey);
        double directionFit = directionFit(required, currentDirection);
        double semanticFit = semanticFit(e, profile, req, queryVector);
        double goalFit = clamp(0.5 * directionFit + 0.5 * semanticFit);
        double difficultyFit = difficultyFit(e.getLevel(), effectiveGrade(profile, req), req.beginner());
        double timeFit = timeFit(e, profile, req);
        double transferFit = transferFit(required);
        double deadlineFit = deadlineFit(status.daysLeft());

        double total = skillFit * properties.getSkillWeight()
                + goalFit * properties.getGoalWeight()
                + difficultyFit * properties.getDifficultyWeight()
                + timeFit * properties.getTimeWeight()
                + transferFit * properties.getTransferWeight()
                + deadlineFit * properties.getDeadlineWeight();
        if (containsAny(e.getName(), PENALTY_KEYWORDS)) {
            total -= 0.10;
        }
        total = clamp(total);

        String relatedDirection = currentDirection == null ? null : currentDirection.getName();
        List<String> gapSkills = new ArrayList<>(required);
        gapSkills.removeAll(userSkills);
        List<String> fitReasons = fitReasons(skillFit, directionFit, semanticFit, status, e, profile, req);
        List<String> riskNotes = riskNotes(profileKey, e, status);
        List<String> startPoints = startPoints(gapSkills);
        String nextAction = nextAction(status.daysLeft(), gapSkills, e, profile);

        String tier = tier(total, status, difficultyFit, skillFit, e, req);

        return new Scored(e, status, total, skillFit, difficultyFit, tier, relatedDirection,
                fitReasons, gapSkills, startPoints, riskNotes, nextAction, primaryCategory(e, profileKey));
    }

    private double skillFit(List<String> required, List<String> userSkills, String profileKey) {
        if (profileKey == null) {
            return 0.5; // 类别未识别 → 中性。
        }
        if (required.isEmpty()) {
            return 0.5; // 画像缺失或 profile 不存在 → 中性。
        }
        if (userSkills.isEmpty()) {
            return 0.5;
        }
        long matched = required.stream().filter(userSkills::contains).count();
        return clamp((double) matched / required.size());
    }

    private double directionFit(List<String> required, GrowthDirection direction) {
        if (direction == null) {
            return 0.5;
        }
        if (required.isEmpty()) {
            return 0.5;
        }
        LinkedHashSet<String> target = new LinkedHashSet<>(direction.getTargetTags());
        target.addAll(direction.getEntryTags());
        if (target.isEmpty()) {
            return 0.5;
        }
        long matched = required.stream().filter(target::contains).count();
        return clamp((double) matched / target.size());
    }

    private double semanticFit(ContestEvent e, ProfileSnapshot profile, Request req, List<Double> queryVector) {
        double keyword = keywordSemanticFit(e, profile, req);
        double embedding = embeddingSemanticFit(e, queryVector);
        return Math.max(keyword, embedding);
    }

    private double keywordSemanticFit(ContestEvent e, ProfileSnapshot profile, Request req) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(profile.interests());
        terms.addAll(profile.goals());
        terms.addAll(req.interestKeywords());
        if (terms.isEmpty()) {
            return 0.0;
        }
        StringBuilder hay = new StringBuilder();
        append(hay, e.getName());
        append(hay, e.getCategoriesJson());
        append(hay, e.getTagsJson());
        append(hay, e.getDescription());
        String lower = hay.toString().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double embeddingSemanticFit(ContestEvent e, List<Double> queryVector) {
        if (queryVector == null || queryVector.isEmpty()) {
            return 0.0;
        }
        List<Double> eventVector = embedOrNull(eventText(e));
        if (eventVector == null || eventVector.isEmpty()) {
            return 0.0;
        }
        return cosine(queryVector, eventVector);
    }

    private List<Double> embedOrNull(String text) {
        if (text == null || text.isBlank() || embeddingClient == null || !embeddingClient.isEnabled()) {
            return null;
        }
        try {
            EmbeddingResult result = embeddingClient.embed(text);
            return result == null ? null : result.vector();
        } catch (EmbeddingException e) {
            return null;
        }
    }

    private double cosine(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        double sim = dot / (Math.sqrt(na) * Math.sqrt(nb));
        return clamp((sim + 1.0) / 2.0); // [-1,1] → [0,1]
    }

    private double difficultyFit(String level, String grade, boolean beginner) {
        if (beginner) {
            return 1.0; // 入门用户：低门槛都算匹配。
        }
        int userLevel = userLevel(grade);
        int compLevel = compLevel(level);
        int diff = compLevel - userLevel;
        if (diff <= -1) {
            return 0.8;
        }
        if (diff == 0) {
            return 1.0;
        }
        if (diff == 1) {
            return 0.6;
        }
        return 0.2; // diff >= 2
    }

    private int userLevel(String grade) {
        if (grade == null) {
            return 2; // 未知按校级~省级中性。
        }
        String lower = grade.toLowerCase(Locale.ROOT);
        if (lower.contains("研二") || lower.contains("研三")) {
            return 4;
        }
        if (lower.contains("研一") || lower.contains("大三") || lower.contains("大四")) {
            return 3;
        }
        if (lower.contains("大二")) {
            return 2;
        }
        if (lower.contains("大一")) {
            return 1;
        }
        return 2;
    }

    private int compLevel(String level) {
        if (level == null) {
            return 2;
        }
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "campus" -> 1;
            case "university" -> 2;
            case "provincial" -> 3;
            case "national" -> 4;
            case "international" -> 5;
            default -> 2;
        };
    }

    private double timeFit(ContestEvent e, ProfileSnapshot profile, Request req) {
        Double hours = req.availableHours() != null ? req.availableHours() : profile.availableHoursPerWeek();
        if (hours == null) {
            return 0.5;
        }
        double est = estimatedWeeklyHours(e);
        return clamp(hours / Math.max(1.0, est));
    }

    private double estimatedWeeklyHours(ContestEvent e) {
        double base = switch (compLevel(e.getLevel())) {
            case 1 -> 2;
            case 2 -> 2;
            case 3 -> 4;
            case 4 -> 6;
            case 5 -> 8;
            default -> 2;
        };
        String hay = (e.getName() == null ? "" : e.getName()) + " "
                + (e.getCategoriesJson() == null ? "" : e.getCategoriesJson());
        String lower = hay.toLowerCase(Locale.ROOT);
        if (lower.contains("hackathon") || lower.contains("黑客松")) {
            base += 2;
        }
        if (lower.contains("数模") || lower.contains("建模")) {
            base += 2;
        }
        if (lower.contains("电子") || lower.contains("硬件") || lower.contains("嵌入式") || lower.contains("机器人")) {
            base += 1;
        }
        return base;
    }

    private double transferFit(List<String> required) {
        if (required.isEmpty()) {
            return 0.5;
        }
        long transferable = required.stream().filter(TRANSFERABLE_TAGS::contains).count();
        return clamp((double) transferable / required.size());
    }

    private double deadlineFit(Long daysLeft) {
        if (daysLeft == null) {
            return 0.35;
        }
        if (daysLeft >= 14 && daysLeft <= 45) {
            return 1.0;
        }
        if (daysLeft > 45 && daysLeft <= 90) {
            return 0.7;
        }
        if (daysLeft >= 7 && daysLeft < 14) {
            return 0.85;
        }
        if (daysLeft >= 3 && daysLeft < 7) {
            return 0.55;
        }
        if (daysLeft >= 0 && daysLeft < 3) {
            return 0.25;
        }
        return 0.5; // >90 或负数。
    }

    private String tier(double total, ContestStatusCalculator.StatusResult status,
                        double difficultyFit, double skillFit, ContestEvent e, Request req) {
        if (total < properties.getMinDisplayScore()) {
            return null; // 不展示。
        }
        if (status.nextDeadline() == null || status.daysLeft() == null) {
            return "长期关注";
        }
        if (difficultyFit < 0.6 && total >= properties.getReachMinScore()) {
            return "冲一冲";
        }
        if (difficultyFit >= 0.8 && (skillFit >= 0.75 || "notice".equals(e.getEventType()))) {
            return "练手";
        }
        return "比较匹配";
    }

    // ------------------------------------------------------------------
    // 理由 / 风险 / 起点 / 下一步
    // ------------------------------------------------------------------

    private List<String> fitReasons(double skillFit, double directionFit, double semanticFit,
                                    ContestStatusCalculator.StatusResult status, ContestEvent e,
                                    ProfileSnapshot profile, Request req) {
        List<String> reasons = new ArrayList<>();
        if (skillFit >= 0.75 && !profile.skills().isEmpty()) {
            reasons.add("技能匹配度高");
        }
        if (directionFit >= 0.6) {
            reasons.add("与你当前成长方向相关");
        }
        if (semanticFit >= 0.5 && !profile.interests().isEmpty()) {
            reasons.add("与你的兴趣相关");
        }
        if (status.daysLeft() != null && status.daysLeft() >= 7 && status.daysLeft() <= 45) {
            reasons.add("截止时间合适");
        } else if (status.daysLeft() != null && status.daysLeft() <= 3 && status.daysLeft() >= 0) {
            reasons.add("截止临近，需尽快确认");
        }
        if ("official_site".equals(e.getVerificationStatus())) {
            reasons.add("官网核验，信息可靠");
        }
        if ("notice".equals(e.getEventType()) || "campus".equals(e.getLevel()) || "university".equals(e.getLevel())) {
            reasons.add("校内/校级，门槛适中");
        }
        // 截断到 ≤3 条。
        while (reasons.size() > 3) {
            reasons.remove(reasons.size() - 1);
        }
        if (reasons.isEmpty()) {
            reasons.add("综合匹配度尚可");
        }
        return reasons;
    }

    private List<String> riskNotes(String profileKey, ContestEvent e,
                                   ContestStatusCalculator.StatusResult status) {
        List<String> notes = new ArrayList<>();
        if (profileKey == null) {
            notes.add("赛事类别未能识别，匹配度按中性处理");
        }
        if ("single_source".equals(e.getVerificationStatus()) || "low".equals(e.getConfidence())) {
            notes.add("报名前请核对官网");
        }
        if (status.nextDeadline() == null) {
            notes.add("官网未给出明确截止时间");
        }
        while (notes.size() > 2) {
            notes.remove(notes.size() - 1);
        }
        return notes;
    }

    private List<String> startPoints(List<String> gapSkills) {
        List<String> points = new ArrayList<>();
        for (String tag : gapSkills) {
            points.add("先用课程或资料补齐「" + tag + "」，或寻找有该能力的队友");
            if (points.size() >= 2) {
                break;
            }
        }
        return points;
    }

    private String nextAction(Long daysLeft, List<String> gapSkills, ContestEvent e, ProfileSnapshot profile) {
        if (daysLeft != null && daysLeft <= 3) {
            return "截止临近，先确认报名资格和材料";
        }
        if (!gapSkills.isEmpty()) {
            return "先补齐基础能力或找队友，再决定是否报名";
        }
        if ("official_site".equals(e.getVerificationStatus())) {
            return "去官网查看报名条件和流程";
        }
        if (profile.skills().isEmpty() || profile.availableHoursPerWeek() == null) {
            return "补充年级和每周可投入时间，结果会更准";
        }
        return "可以去官网确认报名条件";
    }

    // ------------------------------------------------------------------
    // 方向 / 类别 / 画像
    // ------------------------------------------------------------------

    private GrowthDirection currentDirection(ProfileSnapshot profile) {
        GrowthDirection best = null;
        double bestScore = 0.0;
        for (GrowthDirection direction : directionCatalog.all()) {
            double score = 0.0;
            for (String goal : profile.goals()) {
                String lower = goal.toLowerCase(Locale.ROOT);
                for (String keyword : direction.getKeywords()) {
                    if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                        score = Math.max(score, 1.0);
                    }
                }
                if (lower.contains(direction.getName().toLowerCase(Locale.ROOT))) {
                    score = Math.max(score, 1.0);
                }
            }
            if (score == 0.0 && !profile.skills().isEmpty() && !direction.getTargetTags().isEmpty()) {
                long matched = direction.getTargetTags().stream().filter(profile.skills()::contains).count();
                score = Math.max(score, (double) matched / direction.getTargetTags().size());
            }
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return bestScore > 0.0 ? best : null;
    }

    private String skillProfileKey(ContestEvent e) {
        StringBuilder hay = new StringBuilder();
        append(hay, e.getName());
        append(hay, e.getCategoriesJson());
        String lower = hay.toString().toLowerCase(Locale.ROOT);
        for (String entry : CATEGORY_PROFILE) {
            int sep = entry.indexOf("::");
            String key = entry.substring(0, sep);
            String[] words = entry.substring(sep + 2).split(",");
            for (String word : words) {
                if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                    return key;
                }
            }
        }
        return null;
    }

    private List<String> requiredSkills(String profileKey) {
        if (profileKey == null) {
            return List.of();
        }
        return normalizationService.expandRequiredSkills("{\"profile\":\"" + profileKey + "\"}");
    }

    private String primaryCategory(ContestEvent e, String profileKey) {
        if (profileKey != null) {
            return profileKey;
        }
        List<String> categories = parseStringList(e.getCategoriesJson());
        return categories.isEmpty() ? "其他" : categories.get(0);
    }

    // ------------------------------------------------------------------
    // 多样性
    // ------------------------------------------------------------------

    private List<Scored> diversify(List<Scored> scored, ProfileSnapshot profile, int limit,
                                   GrowthDirection currentDirection) {
        List<Scored> collapsed = collapseMultiTrack(scored);

        // 贪心构建：每 primaryCategory ≤2；优先官网/校内。
        List<Scored> top = new ArrayList<>();
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        for (Scored s : collapsed) {
            if (top.size() >= limit) {
                break;
            }
            String cat = s.primaryCategory();
            int count = categoryCount.getOrDefault(cat, 0);
            if (count >= 2) {
                continue;
            }
            top.add(s);
            categoryCount.put(cat, count + 1);
        }

        // 保证 ≥1 条校内/校级（若有）。
        if (top.stream().noneMatch(this::isCampus)) {
            for (Scored s : collapsed) {
                if (!isCampus(s)) {
                    continue;
                }
                if (top.stream().noneMatch(t -> t.event().getEventKey().equals(s.event().getEventKey()))) {
                    top.add(s);
                    break;
                }
            }
        }

        // 有方向时，保证 ≥1 条与方向相关（directionFit 高已在 relatedDirection 体现；这里按 relatedDirection 判断）。
        if (currentDirection != null && top.stream().noneMatch(s -> currentDirection.getName().equals(s.relatedDirection()))) {
            for (Scored s : collapsed) {
                if (currentDirection.getName().equals(s.relatedDirection())
                        && top.stream().noneMatch(t -> t.event().getEventKey().equals(s.event().getEventKey()))) {
                    top.add(s);
                    break;
                }
            }
        }

        // 最终按总分重排并截断。
        top.sort(Comparator.comparingDouble((Scored s) -> -s.total()));
        if (top.size() > limit) {
            top = new ArrayList<>(top.subList(0, limit));
        }
        return top;
    }

    private boolean isCampus(Scored s) {
        ContestEvent e = s.event();
        return "notice".equals(e.getEventType()) || "campus".equals(e.getLevel()) || "university".equals(e.getLevel());
    }

    /** 同赛事多赛道折叠：同名保留截止最早的一条，附“共 N 赛道”。 */
    private List<Scored> collapseMultiTrack(List<Scored> scored) {
        Map<String, List<Scored>> byName = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Scored s : scored) {
            String name = normalizeName(s.event().getName());
            byName.computeIfAbsent(name, k -> {
                order.add(k);
                return new ArrayList<>();
            }).add(s);
        }
        List<Scored> out = new ArrayList<>();
        for (String name : order) {
            List<Scored> group = byName.get(name);
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            // 保留截止最早（daysLeft 最小）的一条。
            group.sort(Comparator.comparing((Scored s) -> s.status().daysLeft() == null
                    ? Long.MAX_VALUE : s.status().daysLeft()));
            Scored kept = group.get(0);
            List<String> notes = new ArrayList<>(kept.riskNotes());
            notes.add("同一赛事多个赛道已合并（共 " + group.size() + " 赛道）");
            while (notes.size() > 2) {
                notes.remove(notes.size() - 1);
            }
            out.add(new Scored(kept.event(), kept.status(), kept.total(), kept.skillFit(),
                    kept.difficultyFit(), kept.tier(), kept.relatedDirection(), kept.fitReasons(),
                    kept.gapSkills(), kept.startPoints(), notes, kept.nextAction(), kept.primaryCategory()));
        }
        return out;
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        int paren = normalized.indexOf('（');
        if (paren >= 0) {
            normalized = normalized.substring(0, paren);
        }
        int bracket = normalized.indexOf('(');
        if (bracket >= 0) {
            normalized = normalized.substring(0, bracket);
        }
        return normalized.trim();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private int verificationRank(String verificationStatus) {
        if ("official_site".equals(verificationStatus)) {
            return 0;
        }
        if ("official_notice".equals(verificationStatus)) {
            return 1;
        }
        if ("single_source".equals(verificationStatus)) {
            return 3;
        }
        return 2;
    }

    private String queryText(ProfileSnapshot profile, Request req) {
        StringBuilder sb = new StringBuilder();
        for (String goal : profile.goals()) {
            append(sb, goal);
        }
        for (String interest : profile.interests()) {
            append(sb, interest);
        }
        for (String keyword : req.interestKeywords()) {
            append(sb, keyword);
        }
        return sb.toString();
    }

    private String eventText(ContestEvent e) {
        StringBuilder sb = new StringBuilder();
        append(sb, e.getName());
        append(sb, e.getCategoriesJson());
        append(sb, e.getTagsJson());
        append(sb, e.getDescription());
        return sb.toString();
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.core.type.TypeReference<List<String>> type = new com.fasterxml.jackson.core.type.TypeReference<>() {
            };
            List<String> list = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, type);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
    }

    private boolean containsAny(String text, List<String> needles) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
