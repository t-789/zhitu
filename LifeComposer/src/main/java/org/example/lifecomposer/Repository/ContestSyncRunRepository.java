package org.example.lifecomposer.Repository;

import org.example.lifecomposer.Entity.ContestSyncRun;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/** 竞赛同步运行记录仓储，支撑 last-good 判断与管理员 sync-status。 */
@Repository
public class ContestSyncRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContestSyncRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ContestSyncRun> ROW_MAPPER = (rs, rowNum) -> {
        ContestSyncRun run = new ContestSyncRun();
        run.setId(rs.getLong("id"));
        run.setStartedAt(rs.getString("started_at"));
        run.setFinishedAt(rs.getString("finished_at"));
        run.setStatus(rs.getString("status"));
        run.setSource(rs.getString("source"));
        run.setHttpStatus(rs.getObject("http_status") == null ? null : rs.getInt("http_status"));
        run.setGeneratedAt(rs.getString("generated_at"));
        run.setItemsSeen(rs.getInt("items_seen"));
        run.setItemsUpserted(rs.getInt("items_upserted"));
        run.setItemsSkipped(rs.getInt("items_skipped"));
        run.setError(rs.getString("error"));
        return run;
    };

    public void createTableIfNeeded() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contest_sync_runs (
                  id             INTEGER PRIMARY KEY AUTOINCREMENT,
                  started_at     TEXT NOT NULL,
                  finished_at    TEXT,
                  status         TEXT NOT NULL,
                  source         TEXT,
                  http_status    INTEGER,
                  generated_at   TEXT,
                  items_seen     INTEGER NOT NULL DEFAULT 0,
                  items_upserted INTEGER NOT NULL DEFAULT 0,
                  items_skipped  INTEGER NOT NULL DEFAULT 0,
                  error          TEXT
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_contest_sync_started ON contest_sync_runs(started_at DESC)");
    }

    /** 插入一条 running 记录并返回自增 id。 */
    public long insertRunning(ContestSyncRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contest_sync_runs (started_at, status, source) VALUES (?, 'running', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, run.getStartedAt());
            ps.setString(2, run.getSource());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 结束时回填结果。 */
    public int finish(long id, ContestSyncRun run) {
        return jdbcTemplate.update("""
                        UPDATE contest_sync_runs
                        SET finished_at = ?, status = ?, source = ?, http_status = ?, generated_at = ?,
                            items_seen = ?, items_upserted = ?, items_skipped = ?, error = ?
                        WHERE id = ?
                        """,
                run.getFinishedAt(), run.getStatus(), run.getSource(), run.getHttpStatus(),
                run.getGeneratedAt(), run.getItemsSeen(), run.getItemsUpserted(),
                run.getItemsSkipped(), run.getError(), id);
    }

    /** 最近一条记录（含 running），无记录返回 null。 */
    public ContestSyncRun findLatest() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM contest_sync_runs ORDER BY started_at DESC LIMIT 1", ROW_MAPPER);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }
}
