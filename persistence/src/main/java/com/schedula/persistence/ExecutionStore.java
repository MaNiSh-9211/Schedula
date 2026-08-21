package com.schedula.persistence;

import com.schedula.common.events.EventTypes;
import com.schedula.common.jobs.ExecStatus;
import com.schedula.common.model.JobEvent;
import com.schedula.common.model.JobExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.schedula.persistence.Mappers.EXECUTION;

@Repository
public class ExecutionStore {

    private final JdbcTemplate jdbc;
    private final EventStore events;

    public ExecutionStore(JdbcTemplate jdbc, EventStore events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public JobExecution create(UUID jobId, int attemptNo, long fencingToken, UUID workerId) {
        UUID execId = com.schedula.common.ids.UuidV7.generate();
        jdbc.update("""
                        INSERT INTO job_executions (id, job_id, attempt_no, status, worker_id, fencing_token)
                        VALUES (?, ?, ?, 'PENDING', ?, ?)
                        """,
                execId, jobId, attemptNo, workerId, fencingToken);
        return findById(execId).orElseThrow();
    }

    public Optional<JobExecution> findById(UUID execId) {
        return jdbc.query("SELECT * FROM job_executions WHERE id = ?", EXECUTION, execId).stream().findFirst();
    }

    public List<JobExecution> findByJob(UUID jobId) {
        return jdbc.query("SELECT * FROM job_executions WHERE job_id = ? ORDER BY attempt_no", EXECUTION, jobId);
    }

    /** PENDING -> RUNNING; guarded by fencing token so only the current owner starts work. */
    public boolean start(UUID execId, UUID workerId, long fencingToken) {
        int updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE job_executions
                            SET status = 'RUNNING', started_at = now()
                            WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'PENDING'
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """, Integer.class, execId, workerId, fencingToken);
        return updated != 0;
    }

    /**
     * RUNNING -> terminal. Guarded by fencing token: a stale worker's late outcome matches
     * zero rows and is discarded (ADR-004).
     */
    public boolean finish(UUID execId, ExecStatus terminal, String errorClass, String errorDetail,
                          long fencingToken) {
        if (terminal != ExecStatus.COMPLETED && terminal != ExecStatus.FAILED
                && terminal != ExecStatus.ABANDONED && terminal != ExecStatus.CANCELLED) {
            throw new IllegalArgumentException("not a terminal execution status: " + terminal);
        }
        int updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE job_executions
                            SET status = ?, finished_at = now(), error_class = ?, error_detail = ?
                            WHERE id = ? AND fencing_token = ? AND status = 'RUNNING'
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """, Integer.class,
                terminal.name(), errorClass, errorDetail, execId, fencingToken);
        if (updated != 0 && terminal == ExecStatus.FAILED) {
            Optional<JobExecution> ex = findById(execId);
            ex.ifPresent(e -> events.append(e.jobId(), execId, EventTypes.JOB_FAILED,
                    "worker", errorClass + ": " + errorDetail, fencingToken));
        }
        return updated != 0;
    }

    public List<JobExecution> findOrphanedRunning() {
        return jdbc.query("SELECT * FROM job_executions WHERE status = 'RUNNING'", EXECUTION);
    }

    /** Recovery path: mark an orphaned RUNNING execution as ABANDONED (lease lost). */
    public boolean abandon(UUID execId) {
        int updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE job_executions SET status = 'ABANDONED', finished_at = now(),
                                error_class = 'LEASE_EXPIRED'
                            WHERE id = ? AND status IN ('PENDING','RUNNING')
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """, Integer.class, execId);
        return updated != 0;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
