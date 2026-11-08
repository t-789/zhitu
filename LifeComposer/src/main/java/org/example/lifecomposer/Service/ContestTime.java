package org.example.lifecomposer.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * 竞赛时间的解析/格式化工具。源数据里可能混有 +08:00、Z、纯日期、字符串 "unknown"，
 * 统一解析成 UTC {@link Instant}，落库时格式化为 ISO-8601 UTC（{@code yyyy-MM-dd'T'HH:mm:ss'Z'}）。
 */
public final class ContestTime {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final ZoneOffset CN_OFFSET = ZoneOffset.ofHours(8);
    private static final Set<String> UNKNOWN = Set.of("unknown", "null", "none", "", "tbd", "n/a");

    private ContestTime() {
    }

    /**
     * 解析源端时间：纯日期按 Asia/Shanghai 当日 23:59:59 处理；
     * 无时区文本按 +08:00；无法解析返回 null。
     */
    public static Instant parse(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || UNKNOWN.contains(text.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!text.contains("T") && text.length() == 10) {
            text = text + "T23:59:59+08:00";
        }
        try {
            return OffsetDateTime.parse(text.replace("Z", "+00:00")).toInstant();
        } catch (DateTimeParseException first) {
            // 无时区裸时间 → 按 Asia/Shanghai。
            try {
                return OffsetDateTime.parse(text + "+08:00").toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** 严格解析已落库的 ISO-8601 UTC 文本，失败返回 null。 */
    public static Instant parseIso(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String format(Instant instant) {
        return instant == null ? null : ISO_INSTANT.format(instant);
    }

    /** 距 now 的天数（向下取整到整数天，未来为正、过去为负）；无 deadline 返回 null。 */
    public static Long daysLeft(Instant deadline, Instant now) {
        if (deadline == null || now == null) {
            return null;
        }
        long seconds = deadline.getEpochSecond() - now.getEpochSecond();
        return (long) Math.floor(seconds / 86400.0);
    }
}
