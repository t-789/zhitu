package org.example.lifecomposer.Service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Entity.ContestEvent;
import org.example.lifecomposer.Entity.ContestSyncRun;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.Repository.ContestSyncRunRepository;
import org.example.lifecomposer.config.ContestSyncProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 竞赛数据同步编排。镜像/GitHub 选新拉竞赛，校内通知独立拉；逐条归一化 + 状态快照 + upsert。
 * 拉取失败只记录 failed，不覆盖库内已有数据（last-good）。
 */
@Service
public class ContestSyncService {

    private static final Logger LOG = LogManager.getLogger(ContestSyncService.class);

    private final ContestSourceClient sourceClient;
    private final ContestNormalizer normalizer;
    private final ContestStatusCalculator statusCalculator;
    private final ContestEventRepository eventRepository;
    private final ContestSyncRunRepository runRepository;
    private final ContestSyncProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ContestSyncService(ContestSourceClient sourceClient, ContestNormalizer normalizer,
                              ContestStatusCalculator statusCalculator, ContestEventRepository eventRepository,
                              ContestSyncRunRepository runRepository, ContestSyncProperties properties) {
        this.sourceClient = sourceClient;
        this.normalizer = normalizer;
        this.statusCalculator = statusCalculator;
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.properties = properties;
    }

    /** 一次同步结果：分别记录竞赛与校内通知是否成功。 */
    public record SyncResult(boolean competitionsOk, boolean noticesOk) {
        public boolean allOk() {
            return competitionsOk && noticesOk;
        }
    }

    /** 执行一轮同步；关闭开关或已在运行则直接返回 false。 */
    public SyncResult sync() {
        if (!properties.isEnabled()) {
            return new SyncResult(false, false);
        }
        if (!running.compareAndSet(false, true)) {
            LOG.info("竞赛同步已在运行，跳过本次");
            return new SyncResult(false, false);
        }
        try {
            boolean competitionsOk = syncCompetitions();
            boolean noticesOk = syncNotices();
            return new SyncResult(competitionsOk, noticesOk);
        } finally {
            running.set(false);
        }
    }

    private boolean syncCompetitions() {
        Instant now = Instant.now();
        String fetchedAt = ContestTime.format(now);
        ContestSourceClient.RawSnapshot snapshot;
        try {
            snapshot = sourceClient.fetchCompetitionSnapshot();
        } catch (Exception e) {
            LOG.warn("竞赛快照拉取失败: {}", e.getMessage());
            recordRun("failed", null, null, null, 0, 0, 0, e.getMessage(), now);
            return false;
        }

        Instant generatedAt = ContestTime.parse(snapshot.generatedAt());
        if (generatedAt == null) {
            generatedAt = now;
        }
        String generatedAtIso = ContestTime.format(generatedAt);

        int seen = 0;
        int skipped = 0;
        List<ContestEvent> events = new ArrayList<>();
        if (snapshot.items() != null && snapshot.items().isArray()) {
            for (JsonNode item : snapshot.items()) {
                if (item == null || !item.isObject()) {
                    skipped++;
                    continue;
                }
                seen++;
                ContestEvent e = normalizer.normalizeCompetition(item, generatedAtIso, fetchedAt);
                if (e == null) {
                    skipped++;
                    continue;
                }
                e.setStatusSnapshot(statusCalculator.compute(e, now).status());
                events.add(e);
            }
        }

        int upserted = 0;
        for (ContestEvent e : events) {
            eventRepository.upsert(e);
            upserted++;
        }
        recordRun("success", snapshot.source(), 200, generatedAtIso, seen, upserted, skipped, null, now);
        LOG.info("竞赛同步完成：源={}，seen={}，upserted={}，skipped={}", snapshot.source(), seen, upserted, skipped);
        return true;
    }

    private boolean syncNotices() {
        Instant now = Instant.now();
        String fetchedAt = ContestTime.format(now);
        ContestSourceClient.RawSnapshot snapshot;
        try {
            snapshot = sourceClient.fetchNoticeSnapshot();
        } catch (Exception e) {
            LOG.warn("校内通知拉取失败: {}", e.getMessage());
            recordRun("failed", "notices", null, null, 0, 0, 0, e.getMessage(), now);
            return false;
        }

        Instant generatedAt = ContestTime.parse(snapshot.generatedAt());
        if (generatedAt == null) {
            generatedAt = now;
        }
        String generatedAtIso = ContestTime.format(generatedAt);

        int seen = 0;
        int skipped = 0;
        List<ContestEvent> events = new ArrayList<>();
        if (snapshot.items() != null && snapshot.items().isArray()) {
            for (JsonNode item : snapshot.items()) {
                if (item == null || !item.isObject()) {
                    skipped++;
                    continue;
                }
                seen++;
                ContestEvent e = normalizer.normalizeNotice(item, generatedAtIso, fetchedAt);
                if (e == null) {
                    skipped++;
                    continue;
                }
                e.setStatusSnapshot(statusCalculator.compute(e, now).status());
                events.add(e);
            }
        }

        int upserted = 0;
        for (ContestEvent e : events) {
            eventRepository.upsert(e);
            upserted++;
        }
        recordRun("success", "notices", 200, generatedAtIso, seen, upserted, skipped, null, now);
        LOG.info("校内通知同步完成：seen={}，upserted={}，skipped={}", seen, upserted, skipped);
        return true;
    }

    private void recordRun(String status, String source, Integer httpStatus, String generatedAt,
                           int seen, int upserted, int skipped, String error, Instant now) {
        ContestSyncRun run = new ContestSyncRun();
        run.setStartedAt(ContestTime.format(now));
        run.setSource(source);
        long id = runRepository.insertRunning(run);
        run.setFinishedAt(ContestTime.format(Instant.now()));
        run.setStatus(status);
        run.setHttpStatus(httpStatus);
        run.setGeneratedAt(generatedAt);
        run.setItemsSeen(seen);
        run.setItemsUpserted(upserted);
        run.setItemsSkipped(skipped);
        run.setError(error);
        runRepository.finish(id, run);
    }
}
