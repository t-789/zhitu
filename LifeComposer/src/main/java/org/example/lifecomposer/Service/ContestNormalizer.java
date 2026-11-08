package org.example.lifecomposer.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lifecomposer.Entity.ContestEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 contest-ddl 竞赛条目 / where-to-study 校内通知条目归一化成 {@link ContestEvent}。
 * 字段映射与 {@code tmp/contest-ddl/prototype_sync.py} 对齐；所有时间统一存 UTC ISO。
 */
@Service
public class ContestNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** contest-ddl 的机器标签，不是给学生看的赛事标签。 */
    private static final Set<String> META_TAGS = Set.of(
            "saikr", "needs_official_verification", "official_site", "single_source",
            "trusted_community", "competition", "hackathon");

    private static final Set<String> STATUS_KEYS = ContestStatusCalculator.STATUS_KEYS;

    /** 归一化一条竞赛；缺 id 或 name 返回 null（跳过）。 */
    public ContestEvent normalizeCompetition(JsonNode item, String generatedAtIso, String fetchedAtIso) {
        String sourceId = str(item, "id");
        String name = strOr(item, "name", "catalog_name");
        if (sourceId == null || name == null) {
            return null;
        }
        String region = str(item, "region");
        SourceRef sourceRef = sourceOf(item);
        String statusHint = str(item, "status");

        ContestEvent e = new ContestEvent();
        e.setEventKey("cddl_" + sourceId);
        e.setSource("contest-ddl");
        e.setSourceKind("competition");
        e.setSourceId(sourceId);
        e.setScope("china".equals(region) ? "china" : "international");
        e.setEventType(strOrDefault(item, "event_type", "competition"));
        e.setName(name);
        e.setOrganizer(str(item, "organizer"));
        e.setLevel(str(item, "level"));
        e.setRegion(region);
        e.setLocation(str(item, "location"));
        e.setMode(str(item, "mode"));
        e.setEligibility(str(item, "eligibility"));
        e.setCategoriesJson(jsonArray(item.get("categories")));
        e.setTagsJson(jsonList(userTags(item)));
        e.setUniversityTiersJson(jsonList(listText(item, "university_tiers")));
        e.setOfficialUrl(strOr(item, "official_url", "catalog_reference_url"));
        e.setSourceUrl(sourceRef.url());
        e.setDescription(strOr(item, "description", "notes"));
        e.setNotes(str(item, "notes"));
        e.setImageUrl(str(item, "image_url"));
        e.setRegistrationStart(iso(item, "registration_start"));
        e.setRegistrationDeadline(iso(item, "registration_deadline"));
        e.setCompetitionStart(iso(item, "competition_start"));
        e.setCompetitionEnd(iso(item, "competition_end"));
        e.setAbstractDeadline(iso(item, "abstract_deadline"));
        e.setSubmissionDeadline(iso(item, "submission_deadline"));
        e.setPrimaryDeadline(iso(item, "primary_deadline"));
        e.setDeadlinesJson(jsonDeadlines(item.get("deadlines")));
        e.setPublishedAt(null);
        e.setStatusHint(STATUS_KEYS.contains(statusHint) ? statusHint : null);
        e.setConfidence(str(item, "confidence"));
        e.setVerificationStatus(str(item, "verification_status"));
        e.setCatalogListed(boolInt(item, "catalog_listed"));
        e.setSourceName(sourceRef.name());
        e.setStale(boolInt(item, "stale"));
        e.setArchived(boolInt(item, "archived"));
        e.setFirstSeenAt(iso(item, "first_seen_at"));
        String lastSeen = iso(item, "last_seen_at");
        e.setLastSeenAt(lastSeen != null ? lastSeen : fetchedAtIso);
        e.setSourceGeneratedAt(generatedAtIso);
        e.setFetchedAt(fetchedAtIso);
        e.setRawJson(item.toString());
        return e;
    }

    /** 归一化一条校内通知；缺 id 或 name 返回 null。 */
    public ContestEvent normalizeNotice(JsonNode item, String generatedAtIso, String fetchedAtIso) {
        String sourceId = str(item, "id");
        String name = strOr(item, "title", "name");
        if (sourceId == null || name == null) {
            return null;
        }

        ContestEvent e = new ContestEvent();
        e.setEventKey("notice_" + sourceId);
        e.setSource("where-to-study-notices");
        e.setSourceKind("notice");
        e.setSourceId(sourceId);
        e.setScope("campus");
        e.setEventType("notice");
        e.setName(name);
        e.setOrganizer(defaultOrganizer(item));
        e.setLevel("campus");
        e.setRegion("china");
        e.setLocation("北京邮电大学");
        e.setMode(null);
        e.setEligibility(null);
        e.setCategoriesJson("[\"校内通知\"]");
        e.setTagsJson("[\"校内竞赛\"]");
        e.setUniversityTiersJson("[]");
        e.setOfficialUrl(null);
        e.setSourceUrl(str(item, "source_url"));
        e.setDescription(str(item, "name"));
        e.setNotes(str(item, "name"));
        e.setImageUrl(null);
        e.setRegistrationStart(null);
        e.setRegistrationDeadline(null);
        e.setCompetitionStart(null);
        e.setCompetitionEnd(null);
        e.setAbstractDeadline(null);
        e.setSubmissionDeadline(null);
        e.setPrimaryDeadline(iso(item, "primary_deadline"));
        e.setDeadlinesJson(jsonDeadlines(item.get("deadlines")));
        e.setPublishedAt(iso(item, "published_at"));
        e.setStatusHint(null);
        e.setConfidence("high");
        e.setVerificationStatus("official_notice");
        e.setCatalogListed(0);
        e.setSourceName(str(item, "source"));
        e.setStale(0);
        e.setArchived(0);
        e.setFirstSeenAt(iso(item, "first_seen_at"));
        String lastSeen = iso(item, "updated_at");
        e.setLastSeenAt(lastSeen != null ? lastSeen : fetchedAtIso);
        e.setSourceGeneratedAt(generatedAtIso);
        e.setFetchedAt(fetchedAtIso);
        e.setRawJson(item.toString());
        return e;
    }

    private String defaultOrganizer(JsonNode item) {
        String src = str(item, "source");
        return src != null ? src : "北京邮电大学教学云平台";
    }

    private record SourceRef(String name, String url) {
    }

    private SourceRef sourceOf(JsonNode item) {
        JsonNode src = item.get("source");
        if (src != null && src.isObject()) {
            return new SourceRef(str(src, "name"), str(src, "url"));
        }
        if (src != null && src.isArray()) {
            for (JsonNode entry : src) {
                if (entry.isObject() && str(entry, "name") != null) {
                    return new SourceRef(str(entry, "name"), str(entry, "url"));
                }
            }
        }
        JsonNode sources = item.get("sources");
        if (sources != null && sources.isArray()) {
            for (JsonNode entry : sources) {
                if (entry.isObject() && str(entry, "name") != null) {
                    return new SourceRef(str(entry, "name"), str(entry, "url"));
                }
            }
        }
        return new SourceRef(null, null);
    }

    private List<String> userTags(JsonNode item) {
        List<String> out = new ArrayList<>();
        for (String tag : listText(item, "tags")) {
            if (!META_TAGS.contains(tag.toLowerCase())) {
                out.add(tag);
            }
        }
        return out;
    }

    private String jsonDeadlines(JsonNode node) {
        List<Map<String, String>> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                if (!entry.isObject()) {
                    continue;
                }
                Instant parsed = ContestTime.parse(str(entry, "date"));
                if (parsed == null) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                m.put("date", ContestTime.format(parsed));
                if (str(entry, "display") != null) {
                    m.put("display", str(entry, "display"));
                }
                if (str(entry, "label") != null) {
                    m.put("label", str(entry, "label"));
                }
                list.add(m);
            }
        }
        return write(list);
    }

    private String jsonArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return "[]";
        }
        return node.toString();
    }

    private String jsonList(List<String> list) {
        return write(list);
    }

    private String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String str(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private String strOr(JsonNode node, String primary, String fallback) {
        String value = str(node, primary);
        return value != null ? value : str(node, fallback);
    }

    private String strOrDefault(JsonNode node, String key, String def) {
        String value = str(node, key);
        return value != null ? value : def;
    }

    private String iso(JsonNode node, String key) {
        return ContestTime.format(ContestTime.parse(str(node, key)));
    }

    private List<String> listText(JsonNode node, String key) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) {
            return out;
        }
        for (JsonNode entry : value) {
            String text = entry.isNull() ? null : entry.asText().trim();
            if (text != null && !text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private int boolInt(JsonNode node, String key) {
        if (node == null) {
            return 0;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? 1 : 0;
        }
        if (value.isNumber()) {
            return value.asInt() != 0 ? 1 : 0;
        }
        if (value.isTextual()) {
            return ("true".equalsIgnoreCase(value.asText()) || "1".equals(value.asText())) ? 1 : 0;
        }
        return 0;
    }
}
