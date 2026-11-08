package org.example.lifecomposer.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.config.ContestSyncProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 竞赛数据源 HTTP 客户端。镜像 / GitHub 并行拉取并按 generated_at 选新（相同时 GitHub 优先），
 * 两者都失败才用 events 兜底；校内通知独立拉取。只做固定 HTTPS GET，不带账号/Cookie/token，
 * 不跟随重定向，超过体积上限即失败。
 */
@Service
public class ContestSourceClient {

    private static final Logger LOG = LogManager.getLogger(ContestSourceClient.class);
    private static final String USER_AGENT = "LifeComposer-ContestSync/0.1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContestSyncProperties properties;
    private final OkHttpClient httpClient;

    public ContestSourceClient(ContestSyncProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build();
    }

    /** 竞赛快照（选源后的原始根节点）。 */
    public record RawSnapshot(String source, String generatedAt, JsonNode items) {
    }

    /** 拉取竞赛快照：镜像/GitHub 并行拉取选新（同时间 GitHub 优先），events 兜底。全失败抛 IllegalStateException。 */
    public RawSnapshot fetchCompetitionSnapshot() {
        // bug15：并行拉取镜像与 GitHub。
        java.util.concurrent.CompletableFuture<RawSnapshot> mirrorFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> safeFetch("mirror", properties.getMirrorUrl()));
        java.util.concurrent.CompletableFuture<RawSnapshot> githubFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> safeFetch("github", properties.getGithubUrl()));
        RawSnapshot mirror = mirrorFuture.join();
        RawSnapshot github = githubFuture.join();
        if (mirror != null && github != null) {
            Instant mirrorGen = generatedAt(mirror);
            Instant githubGen = generatedAt(github);
            // 镜像更新时用镜像，同时间优先 GitHub。
            return mirrorGen.compareTo(githubGen) > 0 ? mirror : github;
        }
        if (mirror != null) {
            return mirror;
        }
        if (github != null) {
            return github;
        }
        RawSnapshot events = safeFetch("events", properties.getEventsUrl());
        if (events != null) {
            return events;
        }
        throw new IllegalStateException("竞赛数据源均不可用（mirror/github/events）");
    }

    /** 拉取校内通知快照；失败抛 IllegalStateException。 */
    public RawSnapshot fetchNoticeSnapshot() {
        RawSnapshot notices = safeFetch("notices", properties.getNoticesUrl());
        if (notices == null) {
            throw new IllegalStateException("校内通知数据源不可用");
        }
        return notices;
    }

    private RawSnapshot safeFetch(String source, String url) {
        try {
            return fetch(source, url, 0);
        } catch (Exception e) {
            LOG.warn("竞赛数据源 {} 拉取失败: {}", source, e.getMessage());
            return null;
        }
    }

    /** bug15：网络/5xx 重试 1 次，并校验 schema_version。 */
    private RawSnapshot fetch(String source, String url, int attempt) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            if (code >= 500 && attempt < 1) {
                return fetch(source, url, attempt + 1);
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + code);
            }
            if (response.body() == null) {
                throw new IllegalStateException("empty body");
            }
            long contentLength = response.body().contentLength();
            if (contentLength > properties.getMaxBodyBytes()) {
                throw new IllegalStateException("响应体过大 " + contentLength + " bytes");
            }
            String body = response.body().string();
            if (body.length() > properties.getMaxBodyBytes()) {
                throw new IllegalStateException("响应体过大 " + body.length() + " bytes");
            }
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("响应不是 JSON 对象");
            }
            // schema_version 校验：缺失或不兼容视为数据不可用（值可为字符串或数字）。
            if (!root.hasNonNull("schema_version")) {
                throw new IllegalStateException("响应缺少 schema_version");
            }
            JsonNode schemaVersion = root.get("schema_version");
            if (!schemaVersion.isTextual() && !schemaVersion.isNumber()) {
                throw new IllegalStateException("schema_version 不是字符串或数字");
            }
            String generatedAt = root.hasNonNull("generated_at")
                    ? root.get("generated_at").asText() : null;
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                throw new IllegalStateException("响应缺少 items 数组");
            }
            return new RawSnapshot(source, generatedAt, items);
        }
    }

    private static Instant generatedAt(RawSnapshot snapshot) {
        Instant instant = ContestTime.parse(snapshot.generatedAt());
        return instant == null ? Instant.EPOCH : instant;
    }
}
