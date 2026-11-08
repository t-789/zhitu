package org.example.lifecomposer.recommendation.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.Entity.UserProfile;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.Repository.UserProfileRepository;
import org.example.lifecomposer.Service.AvailableTimeParser;
import org.example.lifecomposer.Service.CapabilityNormalizationService;
import org.example.lifecomposer.Service.ContestStatusCalculator;
import org.example.lifecomposer.Service.ContestTime;
import org.example.lifecomposer.config.CompetitionProperties;
import org.example.lifecomposer.dto.CompetitionRecommendationDto;
import org.example.lifecomposer.dto.CompetitionRecommendationResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 智能体赛事推荐的编排入口：读画像 → 合并请求（显式参数 > query 解析 > 默认）→
 * 取实时事件 → {@link CompetitionScoringService#rank} → 组装面向 LLM 的结果。
 *
 * <p>赛事事实只来自 contest_events；本服务不做状态计算（交给
 * {@link ContestStatusCalculator}），也不直接写任何表。
 */
@Service
public class CompetitionRecommendationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserProfileRepository userProfileRepository;
    private final ContestEventRepository contestEventRepository;
    private final CapabilityNormalizationService normalizationService;
    private final CompetitionQueryParser queryParser;
    private final CompetitionScoringService scoringService;
    private final CompetitionProperties properties;
    private final ContestStatusCalculator statusCalculator;

    public CompetitionRecommendationService(UserProfileRepository userProfileRepository,
                                            ContestEventRepository contestEventRepository,
                                            CapabilityNormalizationService normalizationService,
                                            CompetitionQueryParser queryParser,
                                            CompetitionScoringService scoringService,
                                            CompetitionProperties properties,
                                            ContestStatusCalculator statusCalculator) {
        this.userProfileRepository = userProfileRepository;
        this.contestEventRepository = contestEventRepository;
        this.normalizationService = normalizationService;
        this.queryParser = queryParser;
        this.scoringService = scoringService;
        this.properties = properties;
        this.statusCalculator = statusCalculator;
    }

    public CompetitionRecommendationResult recommend(Long userId, String query,
                                                     String scope, String eventType,
                                                     boolean includeOngoing, Integer deadlineWithinDays,
                                                     String level, String mode,
                                                     List<String> excludeKeywords, List<String> excludeEventKeys,
                                                     Integer limit) {
        if (!properties.isEnabled()) {
            CompetitionRecommendationResult result = new CompetitionRecommendationResult();
            result.setCount(0);
            result.setCandidateCount(0);
            result.setInformationSufficient(false);
            result.getAssumptions().add("赛事推荐功能当前未开启");
            result.setFilterStats(Map.of("total", 0, "afterHardFilter", 0, "returned", 0));
            return result;
        }

        UserProfile profile = userProfileRepository.findByUserId(userId);
        CompetitionQueryParser.ParsedQuery parsed = queryParser.parse(query);

        CompetitionScoringService.ProfileSnapshot snapshot = snapshot(profile);
        CompetitionScoringService.Request request = merge(scope, eventType, includeOngoing,
                deadlineWithinDays, level, mode, excludeKeywords, excludeEventKeys, limit, parsed);

        List<ContestEvent> events = contestEventRepository.findActive();
        CompetitionScoringService.Ranking ranking = scoringService.rank(events, snapshot, request, Instant.now());

        return buildResult(ranking, snapshot);
    }

    /**
     * 查看单个赛事详情（get_competition_detail 工具后端）。
     *
     * <p>eventKey 或 name 至少提供一个；name 精确/包含匹配到多条时返回 candidates（≤3）。
     * includeFit=true 时附带面向该用户的适配信息（tier/relatedDirection/fitReasons/gapSkills/riskNotes）。
     * 已结束/已归档的赛事仍可查询，fit 写"已结束/已归档"。
     */
    public Map<String, Object> detail(Long userId, String eventKey, String name, boolean includeFit) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!properties.isEnabled()) {
            response.put("found", false);
            response.put("reason", "赛事推荐功能当前未开启");
            return response;
        }

        Instant now = Instant.now();
        ContestEvent event = null;

        if (eventKey != null && !eventKey.isBlank()) {
            event = contestEventRepository.findByEventKey(eventKey.trim());
            if (event == null) {
                response.put("found", false);
                response.put("reason", "未找到赛事：" + eventKey);
                return response;
            }
        } else if (name != null && !name.isBlank()) {
            List<ContestEvent> matches = findByName(name.trim());
            if (matches.isEmpty()) {
                response.put("found", false);
                response.put("reason", "未找到匹配'" + name + "'的赛事");
                return response;
            }
            if (matches.size() > 1) {
                response.put("found", true);
                response.put("multiple", true);
                response.put("candidates", candidates(matches, now));
                return response;
            }
            event = matches.get(0);
        } else {
            response.put("found", false);
            response.put("reason", "eventKey 或 name 至少提供一个");
            return response;
        }

        ContestStatusCalculator.StatusResult r = statusCalculator.compute(event, now);
        response.put("found", true);
        response.put("eventKey", event.getEventKey());
        response.put("name", event.getName());
        response.put("status", r.status());
        response.put("statusLabel", ContestStatusCalculator.STATUS_LABELS.getOrDefault(r.status(), r.status()));
        response.put("deadline", displayDeadline(r.nextDeadline()));
        response.put("daysLeft", r.daysLeft());
        response.put("level", event.getLevel());
        response.put("levelLabel", levelLabel(event.getLevel()));
        response.put("scope", event.getScope());
        response.put("scopeLabel", scopeLabel(event.getScope()));
        response.put("mode", event.getMode());
        response.put("organizer", event.getOrganizer());
        response.put("eligibility", event.getEligibility());
        response.put("description", event.getDescription());
        response.put("officialUrl", event.getOfficialUrl());
        response.put("sourceUrl", event.getSourceUrl());
        response.put("sourceName", event.getSourceName());
        response.put("confidence", event.getConfidence());
        response.put("verificationStatus", event.getVerificationStatus());
        response.put("lastSyncedAt", displayDeadline(event.getFetchedAt()));
        response.put("categories", stringArray(event.getCategoriesJson()));
        response.put("deadlines", parseDeadlines(event.getDeadlinesJson()));

        if (includeFit) {
            response.put("fit", fitFor(userId, event, r.status(), now));
        }
        return response;
    }

    private List<ContestEvent> findByName(String name) {
        List<ContestEvent> exact = new ArrayList<>();
        List<ContestEvent> contains = new ArrayList<>();
        for (ContestEvent e : contestEventRepository.findActive()) {
            if (e.getName() == null) {
                continue;
            }
            if (e.getName().equals(name)) {
                exact.add(e);
            } else if (e.getName().contains(name)) {
                contains.add(e);
            }
        }
        exact.addAll(contains);
        return exact.size() > 3 ? exact.subList(0, 3) : exact;
    }

    private List<Map<String, Object>> candidates(List<ContestEvent> events, Instant now) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ContestEvent e : events) {
            ContestStatusCalculator.StatusResult r = statusCalculator.compute(e, now);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("eventKey", e.getEventKey());
            c.put("name", e.getName());
            c.put("status", r.status());
            c.put("statusLabel", ContestStatusCalculator.STATUS_LABELS.getOrDefault(r.status(), r.status()));
            c.put("deadline", displayDeadline(r.nextDeadline()));
            c.put("daysLeft", r.daysLeft());
            list.add(c);
        }
        return list;
    }

    private Map<String, Object> fitFor(Long userId, ContestEvent event, String status, Instant now) {
        if (!ContestStatusCalculator.ACTIVE.contains(status)) {
            Map<String, Object> fit = new LinkedHashMap<>();
            fit.put("note", statusNote(status));
            return fit;
        }
        UserProfile profile = userProfileRepository.findByUserId(userId);
        CompetitionScoringService.ProfileSnapshot snapshot = snapshot(profile);
        // 宽松请求：只对单条事件算适配，不做 scope/type/level/mode 硬过滤。
        CompetitionScoringService.Request request = new CompetitionScoringService.Request(
                "all", "all", true, null, null, null,
                List.of(), List.of(), 1, null, List.of(), false,
                snapshot.availableHoursPerWeek());
        CompetitionScoringService.Ranking ranking = scoringService.rank(
                List.of(event), snapshot, request, now);
        if (ranking.items().isEmpty()) {
            Map<String, Object> fit = new LinkedHashMap<>();
            fit.put("note", "该赛事暂不满足你的硬性条件");
            return fit;
        }
        CompetitionScoringService.Scored scored = ranking.items().get(0);
        Map<String, Object> fit = new LinkedHashMap<>();
        fit.put("tier", scored.tier());
        fit.put("relatedDirection", scored.relatedDirection());
        fit.put("fitReasons", scored.fitReasons());
        fit.put("gapSkills", scored.gapSkills());
        fit.put("riskNotes", scored.riskNotes());
        fit.put("nextAction", scored.nextAction());
        return fit;
    }

    private String statusNote(String status) {
        String label = ContestStatusCalculator.STATUS_LABELS.getOrDefault(status, status);
        return switch (status) {
            case "ended" -> "该赛事已结束";
            case "archived" -> "该赛事已归档";
            case "registration_closed" -> "该赛事报名已截止";
            default -> "该赛事当前不可报名（" + label + "）";
        };
    }

    private List<Map<String, String>> parseDeadlines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<Map<String, String>> deadlines = new ArrayList<>();
            for (JsonNode item : node) {
                Map<String, String> deadline = new LinkedHashMap<>();
                if (item.has("date") && item.get("date").isTextual()) {
                    deadline.put("date", displayDeadline(item.get("date").asText()));
                }
                if (item.has("label") && item.get("label").isTextual()) {
                    deadline.put("label", item.get("label").asText());
                }
                if (item.has("display") && item.get("display").isTextual()) {
                    deadline.put("display", item.get("display").asText());
                }
                if (!deadline.isEmpty()) {
                    deadlines.add(deadline);
                }
            }
            return deadlines;
        } catch (Exception e) {
            return List.of();
        }
    }

    private CompetitionScoringService.Request merge(String scope, String eventType,
                                                    boolean includeOngoing, Integer deadlineWithinDays,
                                                    String level, String mode,
                                                    List<String> excludeKeywords, List<String> excludeEventKeys,
                                                    Integer limit, CompetitionQueryParser.ParsedQuery parsed) {
        String effScope = scope == null || scope.isBlank() ? "china" : scope;
        Integer effDeadline = deadlineWithinDays != null ? deadlineWithinDays : parsed.deadlineWithinDays();
        String effLevel = level != null && !level.isBlank() ? level : parsed.level();
        String effMode = mode != null && !mode.isBlank() ? mode : parsed.mode();
        boolean effOngoing = includeOngoing || parsed.includeOngoing();

        LinkedHashSet<String> exclude = new LinkedHashSet<>();
        if (excludeKeywords != null) {
            exclude.addAll(excludeKeywords);
        }
        exclude.addAll(parsed.excludeKeywords());

        int effLimit = Math.max(1, Math.min(limit == null ? properties.getMaxItems() : limit,
                properties.getMaxItems()));

        return new CompetitionScoringService.Request(
                effScope, eventType, effOngoing, effDeadline, effLevel, effMode,
                new ArrayList<>(exclude), excludeEventKeys == null ? List.of() : List.copyOf(excludeEventKeys),
                effLimit, parsed.gradeHint(), parsed.interestKeywords(), parsed.beginner(),
                parsed.availableHours());
    }

    private CompetitionScoringService.ProfileSnapshot snapshot(UserProfile profile) {
        List<String> rawSkills = stringArray(profile == null ? null : profile.getSkillsJson());
        List<String> skills = normalizationService.normalizeSkills(rawSkills);
        List<String> interests = stringArray(profile == null ? null : profile.getInterestsJson());
        List<String> goals = stringArray(profile == null ? null : profile.getGoals());
        OptionalDouble hours = AvailableTimeParser.parseHoursPerWeek(
                profile == null ? null : profile.getAvailableTime());
        return new CompetitionScoringService.ProfileSnapshot(
                profile == null ? null : profile.getGrade(),
                profile == null ? null : profile.getMajor(),
                skills, interests, goals,
                hours.isPresent() ? hours.getAsDouble() : null);
    }

    private CompetitionRecommendationResult buildResult(CompetitionScoringService.Ranking ranking,
                                                        CompetitionScoringService.ProfileSnapshot snapshot) {
        CompetitionRecommendationResult result = new CompetitionRecommendationResult();
        result.setCount(ranking.items().size());
        result.setCandidateCount(ranking.afterHardFilter());
        result.setInformationSufficient(!snapshot.skills().isEmpty() || snapshot.availableHoursPerWeek() != null);

        if (snapshot.grade() != null && !snapshot.grade().isBlank()) {
            result.getProfileSummary().put("grade", snapshot.grade());
        }
        if (snapshot.major() != null && !snapshot.major().isBlank()) {
            result.getProfileSummary().put("major", snapshot.major());
        }
        if (!snapshot.skills().isEmpty()) {
            result.getProfileSummary().put("skills", snapshot.skills());
        }
        if (snapshot.availableHoursPerWeek() != null) {
            result.getProfileSummary().put("availableHoursPerWeek", snapshot.availableHoursPerWeek());
        }
        if (!snapshot.goals().isEmpty()) {
            result.getProfileSummary().put("goals", snapshot.goals());
        }

        for (String assumption : assumptions(snapshot)) {
            result.getAssumptions().add(assumption);
        }
        for (CompetitionScoringService.Scored scored : ranking.items()) {
            result.getItems().add(toItem(scored));
        }
        for (String question : followUpQuestions(snapshot)) {
            result.getFollowUpQuestions().add(question);
        }
        result.setFilterStats(Map.of(
                "total", ranking.total(),
                "afterHardFilter", ranking.afterHardFilter(),
                "returned", ranking.items().size()));
        return result;
    }

    private CompetitionRecommendationDto toItem(CompetitionScoringService.Scored scored) {
        ContestEvent e = scored.event();
        ContestStatusCalculator.StatusResult r = scored.status();

        CompetitionRecommendationDto dto = new CompetitionRecommendationDto();
        dto.setEventKey(e.getEventKey());
        dto.setName(e.getName());
        dto.setTier(scored.tier());
        dto.setStatus(r.status());
        dto.setStatusLabel(ContestStatusCalculator.STATUS_LABELS.getOrDefault(r.status(), r.status()));
        dto.setDeadline(displayDeadline(r.nextDeadline()));
        dto.setDaysLeft(r.daysLeft());
        dto.setLevelLabel(levelLabel(e.getLevel()));
        dto.setScopeLabel(scopeLabel(e.getScope()));
        dto.setRelatedDirection(scored.relatedDirection());
        dto.setFitReasons(scored.fitReasons());
        dto.setGapSkills(scored.gapSkills());
        dto.setStartPoints(scored.startPoints());
        dto.setRiskNotes(scored.riskNotes());
        dto.setNextAction(scored.nextAction());
        dto.setOfficialUrl(e.getOfficialUrl());
        dto.setSourceUrl(e.getSourceUrl());
        dto.setSourceName(e.getSourceName());
        dto.setConfidence(e.getConfidence());
        dto.setVerificationStatus(e.getVerificationStatus());
        return dto;
    }

    /** 落库时间是 UTC ISO（Z），对外统一转 Asia/Shanghai +08:00。 */
    private String displayDeadline(String iso) {
        Instant instant = ContestTime.parseIso(iso);
        if (instant == null) {
            return iso;
        }
        return OffsetDateTime.ofInstant(instant, DISPLAY_ZONE).toString();
    }

    private String levelLabel(String level) {
        if (level == null) {
            return "校级";
        }
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "campus" -> "校内";
            case "university" -> "校级";
            case "provincial" -> "省级";
            case "national" -> "国家级";
            case "international" -> "国际级";
            default -> "校级";
        };
    }

    private String scopeLabel(String scope) {
        if (scope == null) {
            return "国内";
        }
        return switch (scope) {
            case "campus" -> "校内";
            case "international" -> "国际";
            default -> "国内";
        };
    }

    private List<String> assumptions(CompetitionScoringService.ProfileSnapshot snapshot) {
        List<String> assumptions = new ArrayList<>();
        if (snapshot.grade() == null || snapshot.grade().isBlank()) {
            assumptions.add("未填年级，按校级~国家级均可考虑");
        }
        if (snapshot.skills().isEmpty()) {
            assumptions.add("未填技能，技能匹配按中性处理");
        }
        if (snapshot.availableHoursPerWeek() == null) {
            assumptions.add("未填每周可投入时间，时间匹配按中性处理");
        }
        return assumptions;
    }

    private List<String> followUpQuestions(CompetitionScoringService.ProfileSnapshot snapshot) {
        List<String> questions = new ArrayList<>();
        if (snapshot.grade() == null || snapshot.grade().isBlank()) {
            questions.add("你的年级是？");
        }
        if (snapshot.availableHoursPerWeek() == null) {
            questions.add("你每周能投入多少小时备赛？");
        }
        while (questions.size() > 2) {
            questions.remove(questions.size() - 1);
        }
        return questions;
    }

    private List<String> stringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                }
            }
            return new ArrayList<>(values);
        } catch (Exception e) {
            return List.of();
        }
    }
}
