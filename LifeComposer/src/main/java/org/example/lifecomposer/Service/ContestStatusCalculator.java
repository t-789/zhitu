package org.example.lifecomposer.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lifecomposer.Entity.ContestEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 竞赛状态计算。状态必须在读取时按当前时间（now）重算，落库的 status_hint / status_snapshot
 * 只作兜底或排查，不作为实时状态。算法与 {@code tmp/contest-ddl/prototype_sync.py} 的
 * {@code compute_status} 对齐。
 */
@Service
public class ContestStatusCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> STATUS_KEYS = Set.of(
            "registration_upcoming", "registration_open", "registration_closed",
            "ongoing", "submission_open", "ended", "archived", "unknown");

    /** 仍可行动的实时状态集合（"active" 过滤用）。 */
    public static final Set<String> ACTIVE = Set.of(
            "registration_upcoming", "registration_open", "ongoing", "submission_open");

    public static final Map<String, String> STATUS_LABELS = Map.of(
            "registration_upcoming", "即将报名",
            "registration_open", "报名中",
            "registration_closed", "报名截止",
            "ongoing", "进行中",
            "submission_open", "提交中",
            "ended", "已结束",
            "archived", "已归档",
            "unknown", "时间待确认");

    /** 实时状态 + 下一个截止时间（UTC ISO）+ 距 now 的整天数（可为负）。 */
    public record StatusResult(String status, String nextDeadline, Long daysLeft) {
    }

    public StatusResult compute(ContestEvent e, Instant now) {
        if (e.getArchived() != null && e.getArchived() == 1) {
            return new StatusResult("archived", null, null);
        }

        // 校内通知：deadlines_json 是唯一可信的截止来源，primary_deadline 只是其中一个节点。
        // 必须先走通知逻辑，否则下方的 primary 分支会把它误判成“提交中”（bug：报名 vs 材料节点）。
        if ("notice".equals(e.getSourceKind()) || "notice".equals(e.getEventType())) {
            return computeNotice(e, now);
        }
        return computeCompetition(e, now);
    }

    /** 校内通知：任一未来截止节点即仍可行动；优先取“报名”节点，否则“提交中”。 */
    private StatusResult computeNotice(ContestEvent e, Instant now) {
        List<Deadline> deadlines = parseDeadlines(e.getDeadlinesJson());
        List<Deadline> future = deadlines.stream()
                .filter(d -> !d.date().isBefore(now))
                .sorted(Comparator.comparing(Deadline::date))
                .toList();
        if (!future.isEmpty()) {
            boolean hasRegistration = future.stream()
                    .anyMatch(d -> d.label() != null && d.label().contains("报名"));
            return result(hasRegistration ? "registration_open" : "submission_open",
                    future.get(0).date(), now);
        }
        if (!deadlines.isEmpty()) {
            Instant maxDeadline = deadlines.stream()
                    .map(Deadline::date).max(Instant::compareTo).orElse(null);
            if (maxDeadline != null && now.isAfter(maxDeadline)) {
                return result("ended", maxDeadline, now);
            }
        }
        // 无 deadlines 节点时回退 primary_deadline。
        Instant primary = parse(e.getPrimaryDeadline());
        if (primary != null) {
            return now.isAfter(primary) ? result("ended", primary, now) : result("submission_open", primary, now);
        }
        return result("unknown", null, now);
    }

    private StatusResult computeCompetition(ContestEvent e, Instant now) {
        Instant regStart = parse(e.getRegistrationStart());
        Instant regEnd = parse(e.getRegistrationDeadline());
        Instant compStart = parse(e.getCompetitionStart());
        Instant compEnd = parse(e.getCompetitionEnd());
        Instant primary = parse(e.getPrimaryDeadline());

        // 注意：List.of 不允许 null 元素，而 regEnd/compEnd 常为 null，故用 Arrays.asList。
        Instant nextDeadline = nextFuture(Arrays.asList(regEnd, compEnd, parse(e.getSubmissionDeadline()),
                parse(e.getAbstractDeadline()), primary), now);

        if (regStart != null && now.isBefore(regStart)) {
            return result("registration_upcoming", nextDeadline, now);
        }
        if (regEnd != null && !now.isAfter(regEnd)) {
            return result("registration_open", nextDeadline, now);
        }
        if (compStart != null && compEnd != null && !now.isBefore(compStart) && !now.isAfter(compEnd)) {
            return result("ongoing", nextDeadline, now);
        }
        if (compStart != null && now.isBefore(compStart)) {
            // 比赛未开始：只有存在报名信息时才给出报名状态；无任何报名信息不能声称“即将报名”。
            if (regEnd != null) {
                return result("registration_closed", nextDeadline, now);
            }
            if (regStart != null) {
                return result("registration_upcoming", nextDeadline, now);
            }
            // 无报名信息：落到下方 unknown。
        }
        if (primary != null && !now.isAfter(primary)) {
            return result("submission_open", nextDeadline, now);
        }
        if (compEnd != null && !now.isAfter(compEnd) && (compStart == null || !now.isBefore(compStart))) {
            return result("ongoing", nextDeadline, now);
        }

        String hint = e.getStatusHint();
        if (hint != null && STATUS_KEYS.contains(hint)) {
            return result(hint, nextDeadline, now);
        }
        return result("unknown", nextDeadline, now);
    }

    private StatusResult result(String status, Instant deadline, Instant now) {
        return new StatusResult(status, ContestTime.format(deadline), ContestTime.daysLeft(deadline, now));
    }

    private Instant parse(String iso) {
        return ContestTime.parseIso(iso);
    }

    private Instant nextFuture(List<Instant> candidates, Instant now) {
        List<Instant> nonNull = candidates.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return null;
        }
        Instant firstFuture = nonNull.stream()
                .filter(d -> !d.isBefore(now))
                .min(Instant::compareTo)
                .orElse(null);
        if (firstFuture != null) {
            return firstFuture;
        }
        return nonNull.stream().max(Instant::compareTo).orElse(null);
    }

    private record Deadline(Instant date, String label) {
    }

    private List<Deadline> parseDeadlines(String json) {
        List<Deadline> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr == null || !arr.isArray()) {
                return out;
            }
            for (JsonNode entry : arr) {
                if (!entry.isObject()) {
                    continue;
                }
                Instant date = ContestTime.parse(entry.hasNonNull("date") ? entry.get("date").asText() : null);
                if (date == null) {
                    continue;
                }
                String label = entry.hasNonNull("label") ? entry.get("label").asText() : null;
                out.add(new Deadline(date, label));
            }
        } catch (Exception ignored) {
            // 坏 JSON 视为无截止节点。
        }
        return out;
    }
}
