package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.ContestEvent;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 实时竞赛事件仓储。建表幂等；查询只回退到原始行，状态重算与过滤统一在
 * {@code ContestQueryService} / {@code ContestStatusCalculator} 完成。
 */
@Repository
public class ContestEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContestEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ContestEvent> ROW_MAPPER = (rs, rowNum) -> {
        ContestEvent e = new ContestEvent();
        e.setId(rs.getLong("id"));
        e.setEventKey(rs.getString("event_key"));
        e.setSource(rs.getString("source"));
        e.setSourceKind(rs.getString("source_kind"));
        e.setSourceId(rs.getString("source_id"));
        e.setScope(rs.getString("scope"));
        e.setEventType(rs.getString("event_type"));
        e.setName(rs.getString("name"));
        e.setOrganizer(rs.getString("organizer"));
        e.setLevel(rs.getString("level"));
        e.setRegion(rs.getString("region"));
        e.setLocation(rs.getString("location"));
        e.setMode(rs.getString("mode"));
        e.setEligibility(rs.getString("eligibility"));
        e.setCategoriesJson(rs.getString("categories_json"));
        e.setTagsJson(rs.getString("tags_json"));
        e.setUniversityTiersJson(rs.getString("university_tiers_json"));
        e.setOfficialUrl(rs.getString("official_url"));
        e.setSourceUrl(rs.getString("source_url"));
        e.setDescription(rs.getString("description"));
        e.setNotes(rs.getString("notes"));
        e.setImageUrl(rs.getString("image_url"));
        e.setRegistrationStart(rs.getString("registration_start"));
        e.setRegistrationDeadline(rs.getString("registration_deadline"));
        e.setCompetitionStart(rs.getString("competition_start"));
        e.setCompetitionEnd(rs.getString("competition_end"));
        e.setAbstractDeadline(rs.getString("abstract_deadline"));
        e.setSubmissionDeadline(rs.getString("submission_deadline"));
        e.setPrimaryDeadline(rs.getString("primary_deadline"));
        e.setDeadlinesJson(rs.getString("deadlines_json"));
        e.setPublishedAt(rs.getString("published_at"));
        e.setStatusHint(rs.getString("status_hint"));
        e.setStatusSnapshot(rs.getString("status_snapshot"));
        e.setConfidence(rs.getString("confidence"));
        e.setVerificationStatus(rs.getString("verification_status"));
        e.setCatalogListed(rs.getInt("catalog_listed"));
        e.setSourceName(rs.getString("source_name"));
        e.setStale(rs.getInt("stale"));
        e.setArchived(rs.getInt("archived"));
        e.setFirstSeenAt(rs.getString("first_seen_at"));
        e.setLastSeenAt(rs.getString("last_seen_at"));
        e.setSourceGeneratedAt(rs.getString("source_generated_at"));
        e.setFetchedAt(rs.getString("fetched_at"));
        e.setRawJson(rs.getString("raw_json"));
        e.setCreatedAt(rs.getString("created_at"));
        e.setUpdatedAt(rs.getString("updated_at"));
        return e;
    };

    public void createTableIfNeeded() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contest_events (
                  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_key             TEXT NOT NULL UNIQUE,
                  source                TEXT NOT NULL,
                  source_kind           TEXT NOT NULL,
                  source_id             TEXT NOT NULL,
                  scope                 TEXT NOT NULL,
                  event_type            TEXT,
                  name                  TEXT NOT NULL,
                  organizer             TEXT,
                  level                 TEXT,
                  region                TEXT,
                  location              TEXT,
                  mode                  TEXT,
                  eligibility           TEXT,
                  categories_json       TEXT,
                  tags_json             TEXT,
                  university_tiers_json TEXT,
                  official_url          TEXT,
                  source_url            TEXT,
                  description           TEXT,
                  notes                 TEXT,
                  image_url             TEXT,
                  registration_start    TEXT,
                  registration_deadline TEXT,
                  competition_start     TEXT,
                  competition_end       TEXT,
                  abstract_deadline     TEXT,
                  submission_deadline   TEXT,
                  primary_deadline      TEXT,
                  deadlines_json        TEXT,
                  published_at          TEXT,
                  status_hint           TEXT,
                  status_snapshot       TEXT,
                  confidence            TEXT,
                  verification_status   TEXT,
                  catalog_listed        INTEGER NOT NULL DEFAULT 0,
                  source_name           TEXT,
                  stale                 INTEGER NOT NULL DEFAULT 0,
                  archived              INTEGER NOT NULL DEFAULT 0,
                  first_seen_at         TEXT,
                  last_seen_at          TEXT,
                  source_generated_at   TEXT,
                  fetched_at            TEXT NOT NULL,
                  raw_json              TEXT,
                  created_at            TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at            TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_contest_scope_status ON contest_events(scope, status_snapshot)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_contest_scope_deadline ON contest_events(scope, registration_deadline)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_contest_updated ON contest_events(updated_at)");
    }

    /** 按 event_key 幂等写入，冲突时更新业务字段、保留 created_at。 */
    public int upsert(ContestEvent e) {
        String sql = """
                INSERT INTO contest_events (
                  event_key, source, source_kind, source_id, scope, event_type, name, organizer, level, region,
                  location, mode, eligibility, categories_json, tags_json, university_tiers_json, official_url,
                  source_url, description, notes, image_url, registration_start, registration_deadline,
                  competition_start, competition_end, abstract_deadline, submission_deadline, primary_deadline,
                  deadlines_json, published_at, status_hint, status_snapshot, confidence, verification_status,
                  catalog_listed, source_name, stale, archived, first_seen_at, last_seen_at, source_generated_at,
                  fetched_at, raw_json, updated_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP
                )
                ON CONFLICT(event_key) DO UPDATE SET
                  source = excluded.source,
                  source_kind = excluded.source_kind,
                  source_id = excluded.source_id,
                  scope = excluded.scope,
                  event_type = excluded.event_type,
                  name = excluded.name,
                  organizer = excluded.organizer,
                  level = excluded.level,
                  region = excluded.region,
                  location = excluded.location,
                  mode = excluded.mode,
                  eligibility = excluded.eligibility,
                  categories_json = excluded.categories_json,
                  tags_json = excluded.tags_json,
                  university_tiers_json = excluded.university_tiers_json,
                  official_url = excluded.official_url,
                  source_url = excluded.source_url,
                  description = excluded.description,
                  notes = excluded.notes,
                  image_url = excluded.image_url,
                  registration_start = excluded.registration_start,
                  registration_deadline = excluded.registration_deadline,
                  competition_start = excluded.competition_start,
                  competition_end = excluded.competition_end,
                  abstract_deadline = excluded.abstract_deadline,
                  submission_deadline = excluded.submission_deadline,
                  primary_deadline = excluded.primary_deadline,
                  deadlines_json = excluded.deadlines_json,
                  published_at = excluded.published_at,
                  status_hint = excluded.status_hint,
                  status_snapshot = excluded.status_snapshot,
                  confidence = excluded.confidence,
                  verification_status = excluded.verification_status,
                  catalog_listed = excluded.catalog_listed,
                  source_name = excluded.source_name,
                  stale = excluded.stale,
                  archived = excluded.archived,
                  first_seen_at = excluded.first_seen_at,
                  last_seen_at = excluded.last_seen_at,
                  source_generated_at = excluded.source_generated_at,
                  fetched_at = excluded.fetched_at,
                  raw_json = excluded.raw_json,
                  updated_at = CURRENT_TIMESTAMP
                """;
        return jdbcTemplate.update(sql,
                e.getEventKey(), e.getSource(), e.getSourceKind(), e.getSourceId(), e.getScope(),
                e.getEventType(), e.getName(), e.getOrganizer(), e.getLevel(), e.getRegion(),
                e.getLocation(), e.getMode(), e.getEligibility(), e.getCategoriesJson(), e.getTagsJson(),
                e.getUniversityTiersJson(), e.getOfficialUrl(), e.getSourceUrl(), e.getDescription(),
                e.getNotes(), e.getImageUrl(), e.getRegistrationStart(), e.getRegistrationDeadline(),
                e.getCompetitionStart(), e.getCompetitionEnd(), e.getAbstractDeadline(),
                e.getSubmissionDeadline(), e.getPrimaryDeadline(), e.getDeadlinesJson(),
                e.getPublishedAt(), e.getStatusHint(), e.getStatusSnapshot(), e.getConfidence(),
                e.getVerificationStatus(), e.getCatalogListed(), e.getSourceName(), e.getStale(),
                e.getArchived(), e.getFirstSeenAt(), e.getLastSeenAt(), e.getSourceGeneratedAt(),
                e.getFetchedAt(), e.getRawJson());
    }

    public ContestEvent findByEventKey(String eventKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM contest_events WHERE event_key = ?", ROW_MAPPER, eventKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 所有未归档事件，供查询服务在内存里重算状态并过滤。 */
    public List<ContestEvent> findActive() {
        return jdbcTemplate.query("SELECT * FROM contest_events WHERE archived = 0", ROW_MAPPER);
    }

    public int countActive() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_events WHERE archived = 0", Integer.class);
        return count == null ? 0 : count;
    }

    /** 最近一次同步写入的时间（ISO），无数据返回 null。 */
    public String findLatestFetchedAt() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(fetched_at) FROM contest_events", String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 当前库里最“新”的源生成时间（ISO），用于 stale 判断。 */
    public String findLatestSourceGeneratedAt() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(source_generated_at) FROM contest_events", String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }
}
