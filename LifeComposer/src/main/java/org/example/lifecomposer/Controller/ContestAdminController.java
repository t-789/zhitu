package org.example.lifecomposer.Controller;

import org.example.lifecomposer.Entity.ContestSyncRun;
import org.example.lifecomposer.Repository.ContestEventRepository;
import org.example.lifecomposer.Repository.ContestSyncRunRepository;
import org.example.lifecomposer.Service.ContestSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理员竞赛同步运维接口（/api/admin/** 已在过滤器链 admin-only）。 */
@RestController
@RequestMapping("/api/admin/contests")
public class ContestAdminController {

    private final ContestSyncService syncService;
    private final ContestSyncRunRepository syncRunRepository;
    private final ContestEventRepository eventRepository;

    public ContestAdminController(ContestSyncService syncService,
                                  ContestSyncRunRepository syncRunRepository,
                                  ContestEventRepository eventRepository) {
        this.syncService = syncService;
        this.syncRunRepository = syncRunRepository;
        this.eventRepository = eventRepository;
    }

    /** 最近一次同步记录 + 当前库内事件数。 */
    @GetMapping("/sync-status")
    public ResponseEntity<?> syncStatus() {
        ContestSyncRun latest = syncRunRepository.findLatest();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastRun", latest == null ? null : summary(latest));
        body.put("activeEvents", eventRepository.countActive());
        body.put("latestFetchedAt", eventRepository.findLatestFetchedAt());
        body.put("latestSourceGeneratedAt", eventRepository.findLatestSourceGeneratedAt());
        return ResponseEntity.ok(body);
    }

    /** 手动触发一轮同步（状态变更请求，受 CSRF 保护，admin-only）。 */
    @PostMapping("/sync")
    public ResponseEntity<?> triggerSync() {
        ContestSyncService.SyncResult result = syncService.sync();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("competitionsOk", result.competitionsOk());
        body.put("noticesOk", result.noticesOk());
        body.put("ok", result.allOk());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> summary(ContestSyncRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("startedAt", run.getStartedAt());
        map.put("finishedAt", run.getFinishedAt());
        map.put("status", run.getStatus());
        map.put("source", run.getSource());
        map.put("httpStatus", run.getHttpStatus());
        map.put("generatedAt", run.getGeneratedAt());
        map.put("itemsSeen", run.getItemsSeen());
        map.put("itemsUpserted", run.getItemsUpserted());
        map.put("itemsSkipped", run.getItemsSkipped());
        map.put("error", run.getError());
        return map;
    }
}
