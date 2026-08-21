package com.schedula.persistence;

import com.schedula.common.events.EventTypes;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.schedula.persistence.Mappers.JOB;

@Repository
public class JobStore {

    private final JdbcTemplate jdbc;
    private final EventStore events;

    public JobStore(JdbcTemplate jdbc, EventStore events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public record Insert(UUID tenantId, String jobType, int priority, String payloadJson,
                         Integer maxAttempts, String retryPolicyJson, Long timeoutMs,
                         Instant scheduledFor, UUID scheduleId, String idempotencyKey) {
    }

    public Job create(Insert draft) {
        UUID id = com.schedula.common.ids.UuidV7.generate();
        JobStatus initial = draft.scheduledFor() == null ? JobStatus.CREATED : JobStatus.SCHEDULED;
        try {
            jdbc.update("""
                            INSERT INTO jobs (id, tenant_id, job_type, priority, status, payload_json,
                                max_attempts, retry_policy_json, timeout_ms, scheduled_for, schedule_id,
                                idempotency_key)
                            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)
                            """,
                    id, draft.tenantId(), draft.jobType(), draft.priority(), initial.name(),
                    Mappers.canonicalize(draft.payloadJson()), draft.maxAttempts(),
                    Mappers.canonicalize(draft.retryPolicyJson()), draft.timeoutMs(),
                    ts(draft.scheduledFor()), draft.scheduleId(), draft.idempotencyKey());
            events.append(id, null, EventTypes.JOB_CREATED, "api",
                    initial == JobStatus.SCHEDULED ? "scheduled submission" : "immediate submission", null);
            return findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            if (draft.idempotencyKey() != null) {
                return findByTenantAndIdempotencyKey(draft.tenantId(), draft.idempotencyKey()).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    public Optional<Job> findById(UUID jobId) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ?", JOB, jobId).stream().findFirst();
    }

    public Optional<Job> findByTenantAndIdempotencyKey(UUID tenantId, String key) {
        return jdbc.query("SELECT * FROM jobs WHERE tenant_id = ? AND idempotency_key = ?",
                JOB, tenantId, key).stream().findFirst();
    }

    public List<Job> findDueScheduled(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM jobs WHERE status = 'SCHEDULED' AND scheduled_for <= ?
                        ORDER BY scheduled_for LIMIT ?
                        """, JOB, ts(now), limit);
    }

    public List<Job> findDueRetries(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM jobs WHERE status = 'RETRY_WAIT' AND next_attempt_at <= ?
                        ORDER BY next_attempt_at LIMIT ?
                        """, JOB, ts(now), limit);
    }

    /**
     * Guarded transition: the database is the arbiter. Returns false when the job is no
     * longer in an expected state (someone else won the race). Caller must be inside a tx
     * when pairing this with other writes.
     */
    public boolean transition(UUID jobId, Set<JobStatus> expected, JobStatus target,
                              String actor, String reason) {
        List<String> expectedNames = expected.stream().map(Enum::name).toList();
        String placeholders = String.join(",", expectedNames.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(target.name());
        args.add(jobId);
        args.addAll(expectedNames);
        int updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE jobs SET status = ?, version = version + 1, updated_at = now()
                            WHERE id = ? AND status IN (%s)
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """.formatted(placeholders), Integer.class, args.toArray());
        boolean ok = updated != 0;
        if (ok) {
            events.append(jobId, null, eventTypeFor(target), actor, reason, null);
        }
        return ok;
    }

    private static String eventTypeFor(JobStatus target) {
        return switch (target) {
            case QUEUED -> EventTypes.JOB_QUEUED;
            case DISPATCHED -> EventTypes.JOB_DISPATCHED;
            case RUNNING -> EventTypes.JOB_STARTED;
            case COMPLETED -> EventTypes.JOB_COMPLETED;
            case RETRY_WAIT -> EventTypes.JOB_RETRY_SCHEDULED;
            case DEAD -> EventTypes.JOB_DEAD;
            case CANCELLED -> EventTypes.JOB_CANCELLED;
            case PAUSED -> EventTypes.JOB_PAUSED;
            case SCHEDULED -> EventTypes.JOB_RESUMED;
            default -> target.name();
        };
    }

    public boolean markRetryEligible(UUID jobId, Instant nextAttemptAt, String actor, String reason) {
        int updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE jobs SET status = 'RETRY_WAIT', next_attempt_at = ?,
                                version = version + 1, updated_at = now()
                            WHERE id = ? AND status = 'RUNNING'
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """, Integer.class, ts(nextAttemptAt), jobId);
        boolean ok = updated != 0;
        if (ok) {
            events.append(jobId, null, EventTypes.JOB_RETRY_SCHEDULED, actor, reason, null);
        }
        return ok;
    }

    public void incrementAttempts(UUID jobId) {
        jdbc.update("UPDATE jobs SET attempts_made = attempts_made + 1 WHERE id = ?", jobId);
    }

    public List<Job> listByTenant(UUID tenantId, String statusOrNull, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM jobs WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (statusOrNull != null && !statusOrNull.isBlank()) {
            sql.append(" AND status = ?");
            args.add(statusOrNull);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), JOB, args.toArray());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
