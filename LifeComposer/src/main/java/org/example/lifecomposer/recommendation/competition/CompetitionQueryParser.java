package org.example.lifecomposer.recommendation.competition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量中文约束解析：把用户一句话里显式的赛事约束提取成结构化参数。
 * 解析结果只是“提示”，显式参数 > query 解析 > 画像默认；解析不到返回空默认。
 */
@Component
public class CompetitionQueryParser {

    /** 每周可投入时间，如“每周 6 小时”“6 hours/week”“6小时”。 */
    private static final Pattern HOURS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?(?:小时|h(?:ours?)?)(?:/周|每周|/week)?");
    private static final Pattern HOURS_LEADING = Pattern.compile("(?:每周|周均)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:小时|h(?:ours?)?)");

    private static final List<String> GRADE_KEYS = List.of(
            "大一", "大二", "大三", "大四", "研一", "研二", "研三", "研究生", "本科生", "博士生");
    private static final List<String> ONLINE = List.of("线上", "线上赛", "远程", "线上比赛");
    private static final List<String> OFFLINE = List.of("线下", "线下赛", "现场", "线下比赛");
    private static final List<String> HYBRID = List.of("线上线下", "混合", "线上线下结合");

    private static final List<String> INTEREST_KEYWORDS = List.of(
            "算法", "程序设计", "蓝桥杯", "ACM", "ICPC", "天梯赛", "编程",
            "数模", "数学建模", "建模",
            "数据", "AI", "人工智能", "大模型", "大数据", "机器学习", "深度学习",
            "网安", "网络安全", "信安", "CTF", "攻防",
            "电子", "嵌入式", "机器人", "机械", "硬件", "物联网",
            "创业", "创新", "挑战杯", "互联网+", "大创", "商业计划",
            "设计", "文创", "工业设计");

    private static final List<String> EXCLUDE_MARKERS = List.of(
            "不要", "不想", "别", "除了", "不参加", "不考虑", "不报", "不想要", "避开");

    private static final List<String> BEGINNER = List.of("零基础", "新手", "入门", "小白", "刚开始", "没经验", "第一次");
    private static final List<String> ONGOING = List.of("正在进行", "还能参加", "还可以报", "进行中", "还能报名", "正在报名");

    public record ParsedQuery(
            Integer deadlineWithinDays,
            String gradeHint,
            String mode,
            String level,
            List<String> interestKeywords,
            List<String> excludeKeywords,
            boolean beginner,
            boolean includeOngoing,
            Double availableHours) {

        public ParsedQuery {
            interestKeywords = interestKeywords == null ? List.of() : List.copyOf(interestKeywords);
            excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
        }
    }

    private static final ParsedQuery EMPTY = new ParsedQuery(
            null, null, null, null, List.of(), List.of(), false, false, null);

    public ParsedQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return EMPTY;
        }
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        return new ParsedQuery(
                deadlineWithinDays(lower),
                gradeHint(lower),
                mode(lower),
                level(lower),
                interestKeywords(q, lower),
                excludeKeywords(q),
                containsAny(lower, BEGINNER),
                containsAny(lower, ONGOING),
                availableHours(q, lower));
    }

    private Integer deadlineWithinDays(String lower) {
        if (containsAny(lower, "下个月", "下月", "最近30天", "最近三十天", "一个月内", "未来一个月", "30天内")) {
            return 30;
        }
        if (containsAny(lower, "本月", "这个月", "最近两周", "两周内", "半个月", "最近半个月")) {
            return 14;
        }
        if (containsAny(lower, "最近一周", "一周内", "本周", "这周", "7天内")) {
            return 7;
        }
        if (containsAny(lower, "最近三天", "三天内", "3天内")) {
            return 3;
        }
        return null;
    }

    private String gradeHint(String lower) {
        // 返回精确年级文本（大一~研三/研究生/本科生/博士生），供难度计算按年级细分；
        // 粗粒度 graduate/undergraduate 由 ScoringService.effectiveGrade 再归一化。
        for (String key : GRADE_KEYS) {
            if (lower.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private String mode(String lower) {
        // bug4：否定感知。"不要线下/不想线下"→online，"不要线上"→offline。
        boolean negOnline = isNegated(lower, ONLINE);
        boolean negOffline = isNegated(lower, OFFLINE);
        if (negOffline && !negOnline) {
            return "online";
        }
        if (negOnline && !negOffline) {
            return "offline";
        }
        if (containsAny(lower, HYBRID)) {
            return "hybrid";
        }
        if (containsAny(lower, ONLINE)) {
            return "online";
        }
        if (containsAny(lower, OFFLINE)) {
            return "offline";
        }
        return null;
    }

    /** 某组模式词是否被"不要/不想/别"等否定标记修饰。 */
    private boolean isNegated(String lower, List<String> words) {
        for (String word : words) {
            int idx = lower.indexOf(word.toLowerCase(Locale.ROOT));
            if (idx < 0) {
                continue;
            }
            // 检查词前 6 个字符内是否有否定标记。
            int from = Math.max(0, idx - 6);
            String before = lower.substring(from, idx);
            for (String marker : EXCLUDE_MARKERS) {
                if (before.contains(marker.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String level(String lower) {
        if (containsAny(lower, "国家级", "国赛", "全国")) {
            return "national";
        }
        if (containsAny(lower, "省级", "省赛")) {
            return "provincial";
        }
        if (containsAny(lower, "校级", "校赛", "校内")) {
            return "campus";
        }
        if (containsAny(lower, "国际", "海外", "全球")) {
            return "international";
        }
        return null;
    }

    private List<String> interestKeywords(String q, String lower) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (String keyword : INTEREST_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT)) && !isNegated(lower, List.of(keyword))) {
                hits.add(keyword);
            }
        }
        return new ArrayList<>(hits);
    }

    private List<String> excludeKeywords(String q) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        String lower = q.toLowerCase(Locale.ROOT);
        for (String marker : EXCLUDE_MARKERS) {
            int idx = lower.indexOf(marker.toLowerCase(Locale.ROOT));
            while (idx >= 0) {
                String rest = q.substring(idx + marker.length());
                String word = firstCategoryWord(rest);
                if (word != null) {
                    hits.add(word);
                }
                idx = lower.indexOf(marker.toLowerCase(Locale.ROOT), idx + 1);
            }
        }
        return new ArrayList<>(hits);
    }

    /** 取排除标记后面的第一个类别词，用于 later 的类别过滤。 */
    private String firstCategoryWord(String rest) {
        if (rest == null || rest.isBlank()) {
            return null;
        }
        String trimmed = rest.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        // bug5：用 contains 而非 startsWith，"打数模"应识别出"数模"。
        String best = null;
        for (String keyword : INTEREST_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))
                    && (best == null || keyword.length() > best.length())) {
                best = keyword;
            }
        }
        if (best != null) {
            return best;
        }
        // 取第一个标点前的片段作为关键词。
        int cut = trimmed.length();
        for (char stop : new char[]{'，', ',', '。', ' ', '的', '和', '跟', '与', '赛', '比'}) {
            int p = trimmed.indexOf(stop);
            if (p >= 0 && p < cut) {
                cut = p;
            }
        }
        if (cut == 0) {
            return null;
        }
        String word = trimmed.substring(0, Math.min(cut, 6)).trim();
        return word.isEmpty() ? null : word;
    }

    private Double availableHours(String q, String lower) {
        Matcher leading = HOURS_LEADING.matcher(lower);
        if (leading.find()) {
            return parseHours(leading.group(1));
        }
        Matcher m = HOURS.matcher(lower);
        if (m.find()) {
            return parseHours(m.group(1));
        }
        return null;
    }

    private Double parseHours(String text) {
        try {
            double value = Double.parseDouble(text);
            return (value > 0 && value <= 168) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean containsAny(String lower, String... needles) {
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String lower, List<String> needles) {
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
