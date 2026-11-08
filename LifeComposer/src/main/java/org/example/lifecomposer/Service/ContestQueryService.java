package org.example.lifecomposer.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.config.CompetitionProperties;
import org.example.lifecomposer.dto.ContestDetailResponse;
import org.example.lifecomposer.dto.ContestItemResponse;
import org.example.lifecomposer.dto.ContestListResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 竞赛查询服务：读库内原始行，逐条按 now 重算状态，做 scope/type/status/q 过滤、排序、分页。
 * 不直接对外返回落库的 status_snapshot。
 */
@Service
public class ContestQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STUDENT_TYPES = Set.of("competition", "hackathon", "notice");
    private static final int DESCRIPTION_TRUNCATE = 120;

    private final ContestEventRepository eventRepository;
    private final ContestStatusCalculator statusCalculator;
    private final CompetitionProperties properties;

    public ContestQueryService(ContestEventRepository eventRepository,
                               ContestStatusCalculator statusCalculator,
                               CompetitionProperties properties) {
        this.eventRepository = eventRepository;
        this.statusCalculator = statusCalculator;
        this.properties = properties;
    }

    public ContestListResponse list(String scope, String status, String type, String q,
                                    int limit, int offset, String sort) {
        Instant now = Instant.now();
        List<ContestEvent> all = eventRepository.findActive();

        List<Item> matched = new ArrayList<>();
        for (ContestEvent e : all) {
            if (!matchScope(e, scope)) {
                continue;
            }
            if (!matchType(e, type)) {
                continue;
            }
            ContestStatusCalculator.StatusResult r = statusCalculator.compute(e, now);
            if (!matchStatus(status, r)) {
                continue;
            }
            if (!matchQuery(e, q)) {
                continue;
            }
            matched.add(new Item(e, r));
        }

        matched.sort(comparator(sort));

        int total = matched.size();
        int from = Math.min(offset, total);
        int to = Math.min(offset + limit, total);
        List<Item> page = matched.subList(from, to);

        ContestListResponse resp = new ContestListResponse();
        resp.setItems(new ArrayList<>());
        for (Item item : page) {
            resp.getItems().add(toItem(item.event(), item.result()));
        }
        resp.setTotal(total);
        resp.setLimit(limit);
        resp.setOffset(offset);
        resp.setSyncedAt(ContestTime.format(now));
        resp.setSourceGeneratedAt(eventRepository.findLatestSourceGeneratedAt());
        resp.setStaleSource(isStale(resp.getSourceGeneratedAt(), now));
        return resp;
    }

    public ContestDetailResponse detail(String eventKey) {
        ContestEvent e = eventRepository.findByEventKey(eventKey);
        if (e == null) {
            return null;
        }
        Instant now = Instant.now();
        ContestStatusCalculator.StatusResult r = statusCalculator.compute(e, now);

        ContestDetailResponse d = new ContestDetailResponse();
        d.setEventKey(e.getEventKey());
        d.setName(e.getName());
        d.setOrganizer(e.getOrganizer());
        d.setScope(e.getScope());
        d.setEventType(e.getEventType());
        d.setStatus(r.status());
        d.setStatusLabel(ContestStatusCalculator.STATUS_LABELS.getOrDefault(r.status(), r.status()));
        d.setLevel(e.getLevel());
        d.setRegion(e.getRegion());
        d.setLocation(e.getLocation());
        d.setMode(e.getMode());
        d.setEligibility(e.getEligibility());
        d.setDescription(e.getDescription());
        d.setNotes(e.getNotes());
        d.setImageUrl(e.getImageUrl());
        d.setRegistrationStart(e.getRegistrationStart());
        d.setRegistrationDeadline(e.getRegistrationDeadline());
        d.setCompetitionStart(e.getCompetitionStart());
        d.setCompetitionEnd(e.getCompetitionEnd());
        d.setAbstractDeadline(e.getAbstractDeadline());
        d.setSubmissionDeadline(e.getSubmissionDeadline());
        d.setPrimaryDeadline(e.getPrimaryDeadline());
        d.setNextDeadline(r.nextDeadline());
        d.setDaysLeft(r.daysLeft());
        d.setPublishedAt(e.getPublishedAt());
        d.setOfficialUrl(e.getOfficialUrl());
        d.setSourceUrl(e.getSourceUrl());
        d.setSourceName(e.getSourceName());
        d.setConfidence(e.getConfidence());
        d.setVerificationStatus(e.getVerificationStatus());
        d.setStale(e.getStale() != null && e.getStale() == 1);
        d.setCatalogListed(e.getCatalogListed() != null && e.getCatalogListed() == 1);
        d.setCategories(parseStringList(e.getCategoriesJson()));
        d.setTags(parseStringList(e.getTagsJson()));
        d.setUniversityTiers(parseStringList(e.getUniversityTiersJson()));
        d.setDeadlines(parseDeadlines(e.getDeadlinesJson()));
        return d;
    }

    private record Item(ContestEvent event, ContestStatusCalculator.StatusResult result) {
    }

    private boolean matchScope(ContestEvent e, String scope) {
        if (scope == null || scope.isBlank() || "all".equals(scope)) {
            return true;
        }
        return scope.equals(e.getScope());
    }

    private boolean matchType(ContestEvent e, String type) {
        if (type == null || type.isBlank() || "all".equals(type)) {
            return STUDENT_TYPES.contains(e.getEventType());
        }
        String t = "campus_notice".equals(type) ? "notice" : type;
        return t.equals(e.getEventType());
    }

    private boolean matchStatus(String status, ContestStatusCalculator.StatusResult r) {
        if (status == null || status.isBlank()) {
            return true;
        }
        if ("active".equals(status)) {
            return ContestStatusCalculator.ACTIVE.contains(r.status());
        }
        return status.equals(r.status());
    }

    private boolean matchQuery(ContestEvent e, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.trim().toLowerCase();
        return (e.getName() != null && e.getName().toLowerCase().contains(needle))
                || (e.getOrganizer() != null && e.getOrganizer().toLowerCase().contains(needle));
    }

    private Comparator<Item> comparator(String sort) {
        if ("name".equals(sort)) {
            return Comparator.comparing((Item i) -> i.event().getName() == null ? "" : i.event().getName())
                    .thenComparing(i -> i.result().daysLeft() == null ? Long.MAX_VALUE : i.result().daysLeft());
        }
        // 默认 deadline：daysLeft 升序（无截止排最后），再按名称。
        return Comparator.comparing((Item i) -> i.result().daysLeft() == null ? 1 : 0)
                .thenComparing(i -> i.result().daysLeft() == null ? Long.MAX_VALUE : i.result().daysLeft())
                .thenComparing(i -> i.event().getName() == null ? "" : i.event().getName());
    }

    private boolean isStale(String sourceGeneratedAt, Instant now) {
        if (sourceGeneratedAt == null) {
            return true;
        }
        Instant generated = ContestTime.parseIso(sourceGeneratedAt);
        if (generated == null) {
            return true;
        }
        long ageHours = (now.getEpochSecond() - generated.getEpochSecond()) / 3600L;
        return ageHours > properties.getStaleHours();
    }

    private ContestItemResponse toItem(ContestEvent e, ContestStatusCalculator.StatusResult r) {
        ContestItemResponse item = new ContestItemResponse();
        item.setEventKey(e.getEventKey());
        item.setName(e.getName());
        item.setOrganizer(e.getOrganizer());
        item.setScope(e.getScope());
        item.setEventType(e.getEventType());
        item.setStatus(r.status());
        item.setStatusLabel(ContestStatusCalculator.STATUS_LABELS.getOrDefault(r.status(), r.status()));
        item.setLevel(e.getLevel());
        item.setMode(e.getMode());
        item.setRegistrationDeadline(e.getRegistrationDeadline());
        item.setNextDeadline(r.nextDeadline());
        item.setDaysLeft(r.daysLeft());
        item.setOfficialUrl(e.getOfficialUrl());
        item.setSourceUrl(e.getSourceUrl());
        item.setSourceName(e.getSourceName());
        item.setConfidence(e.getConfidence());
        item.setVerificationStatus(e.getVerificationStatus());
        item.setStale(e.getStale() != null && e.getStale() == 1);
        item.setCatalogListed(e.getCatalogListed() != null && e.getCatalogListed() == 1);
        item.setDescription(truncate(e.getDescription(), DESCRIPTION_TRUNCATE));
        item.setTags(parseStringList(e.getTagsJson()));
        return item;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = MAPPER.readValue(json, new TypeReference<>() {
            });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, String>> parseDeadlines(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> list = MAPPER.readValue(json, new TypeReference<>() {
            });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
