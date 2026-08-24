package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class AffinityStore {

    private final JdbcTemplate jdbc;

    public AffinityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called after every execution completes to update worker affinity profile. */
    public void record(UUID workerId, String jobType, boolean success, long durationMs) {
        jdbc.update("""
                INSERT INTO worker_affinity (worker_id, job_type, total_runs, total_success,
                    total_duration_ms)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT (worker_id, job_type) DO UPDATE SET
                    total_runs = worker_affinity.total_runs + 1,
                    total_success = worker_affinity.total_success + ?::int,
                    total_duration_ms = worker_affinity.total_duration_ms + ?,
                    updated_at = now()
                """, workerId, jobType,
                success ? 1 : 0, durationMs,
                success ? 1 : 0, durationMs);
    }

    /** Best worker for a given type: highest success rate, then fastest avg duration. */
    public UUID bestWorkerFor(String jobType, int minSamples) {
        var rows = jdbc.query("""
                SELECT wa.worker_id
                FROM worker_affinity wa
                JOIN workers w ON w.id = wa.worker_id AND w.status = 'HEALTHY'
                WHERE wa.job_type = ? AND wa.total_runs >= ?
                ORDER BY wa.total_success::numeric / NULLIF(wa.total_runs, 0) DESC,
                         wa.total_duration_ms::numeric / NULLIF(wa.total_runs, 0) ASC
                LIMIT 1
                """, (rs, i) -> (UUID) rs.getObject("worker_id"), jobType, minSamples);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
