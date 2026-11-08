package org.example.lifecomposer.Controller;

import org.example.lifecomposer.Service.ContestQueryService;
import org.example.lifecomposer.Service.ContestStatusCalculator;
import org.example.lifecomposer.dto.ContestDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 实时竞赛查询接口。只读，返回信封；非法参数 400 纯文本，未命中 404 纯文本。 */
@RestController
@RequestMapping("/api/contests")
public class ContestController {

    private static final Set<String> SCOPES = Set.of("china", "international", "campus", "all");
    private static final Set<String> TYPES = Set.of("competition", "hackathon", "campus_notice", "all");
    private static final Set<String> SORTS = Set.of("deadline", "name");

    private final ContestQueryService queryService;

    public ContestController(ContestQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String scope,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "deadline") String sort) {
        String error = validate(scope, status, type, sort);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }
        int effectiveLimit = Math.max(1, Math.min(100, limit));
        int effectiveOffset = Math.max(0, offset);
        return ResponseEntity.ok(queryService.list(norm(scope), norm(status), norm(type), q,
                effectiveLimit, effectiveOffset, sort));
    }

    @GetMapping("/{eventKey}")
    public ResponseEntity<?> detail(@PathVariable String eventKey) {
        ContestDetailResponse detail = queryService.detail(eventKey);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("竞赛不存在: " + eventKey);
        }
        return ResponseEntity.ok(detail);
    }

    private String validate(String scope, String status, String type, String sort) {
        if (scope != null && !SCOPES.contains(scope)) {
            return "scope 参数仅支持 china、international、campus 或 all";
        }
        if (status != null && !"active".equals(status) && !ContestStatusCalculator.STATUS_KEYS.contains(status)) {
            return "status 参数仅支持 active、" + String.join("、", ContestStatusCalculator.STATUS_KEYS);
        }
        if (type != null && !TYPES.contains(type)) {
            return "type 参数仅支持 competition、hackathon、campus_notice 或 all";
        }
        if (sort != null && !SORTS.contains(sort)) {
            return "sort 参数仅支持 deadline 或 name";
        }
        return null;
    }

    private String norm(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
