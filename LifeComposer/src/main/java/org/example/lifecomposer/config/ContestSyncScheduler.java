package org.example.lifecomposer.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.Service.ContestSyncService;
import org.example.lifecomposer.Service.ContestTime;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 竞赛数据定时同步。cron 用 Asia/Shanghai；启动时若最近一次成功同步超过
 * {@code startupMaxAgeHours} 小时则补拉一次。开关关闭时不执行。
 */
@Component
public class ContestSyncScheduler {

    private static final Logger LOG = LogManager.getLogger(ContestSyncScheduler.class);

    private final ContestSyncService syncService;
    private final ContestEventRepository eventRepository;
    private final ContestSyncProperties properties;

    public ContestSyncScheduler(ContestSyncService syncService,
                                ContestEventRepository eventRepository,
                                ContestSyncProperties properties) {
        this.syncService = syncService;
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "${lifecomposer.contest-sync.sync-cron:0 20 6,12,18,23 * * *}",
            zone = "Asia/Shanghai")
    public void scheduledSync() {
        runOnce();
    }

    /** 供测试/手动调用，避开 cron。 */
    public ContestSyncService.SyncResult runOnce() {
        try {
            return syncService.sync();
        } catch (RuntimeException e) {
            LOG.warn("AUDIT event=contest_sync_failed error={}", e.toString());
            return new ContestSyncService.SyncResult(false, false);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant last = ContestTime.parseIso(eventRepository.findLatestFetchedAt());
        if (last == null || ageHours(last) > properties.getStartupMaxAgeHours()) {
            LOG.info("启动补拉竞赛数据：上次成功同步距今超过 {} 小时", properties.getStartupMaxAgeHours());
            runOnce();
        }
    }

    private long ageHours(Instant last) {
        return (Instant.now().getEpochSecond() - last.getEpochSecond()) / 3600L;
    }
}
