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
                         Instant scheduledFor, UUID scheduleId, String idempotencyKey,
                         java.util.List<String> requiredCapabilities, Integer requiredCpu,
                         Long requiredMemMb, String queueName, String webhookUrl) {
        public Insert {
            if (requiredCapabilities == null) requiredCapabilities = java.util.List.of();
            if (requiredCpu == null) requiredCpu = 0;
            if (requiredMemMb == null) requiredMemMb = 0L;
            if (queueName == null || queueName.isBlank()) queueName = "default";
        }
    }

    public record CreationResult(Job job, boolean fresh) {
    }

    public CreationResult createReturningFreshness(Insert draft) {
        UUID id = com.schedula.common.ids.UuidV7.generate();
        // immediate jobs are born SCHEDULED with scheduled_for=now so the standard due
        // path (SCHEDULED -> QUEUED) is the only eligibility mechanism to reason about
        Instant scheduledFor = draft.scheduledFor() == null ? Instant.now() : draft.scheduledFor();
        JobStatus initial = JobStatus.SCHEDULED;
        int maxAttempts = draft.maxAttempts() == null
                ? com.schedula.common.retry.RetryPolicy.DEFAULT.maxAttempts() : draft.maxAttempts();
        long timeoutMs = draft.timeoutMs() == null ? 60_000L : draft.timeoutMs();
        try {
            jdbc.update("""
                            INSERT INTO jobs (id, tenant_id, job_type, priority, status, payload_json,
                                max_attempts, retry_policy_json, timeout_ms, scheduled_for, schedule_id,
                                idempotency_key, required_capabilities, required_cpu, required_mem_mb,
                                queue_name, webhook_url)
                            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    id, draft.tenantId(), draft.jobType(), draft.priority(), initial.name(),
                    Mappers.canonicalize(draft.payloadJson()), maxAttempts,
                    Mappers.canonicalize(draft.retryPolicyJson()), timeoutMs,
                    ts(scheduledFor), draft.scheduleId(), draft.idempotencyKey(),
                    draft.requiredCapabilities().toArray(new String[0]),
                    draft.requiredCpu(), draft.requiredMemMb(),
                    draft.queueName(), draft.webhookUrl());
            events.append(id, null, EventTypes.JOB_CREATED, "api",
                    "submission", null);
            return new CreationResult(findById(id).orElseThrow(), true);
        } catch (DuplicateKeyException e) {
            if (draft.idempotencyKey() != null) {
                Job existing = findByTenantAndIdempotencyKey(draft.tenantId(), draft.idempotencyKey())
                        .orElseThrow(() -> e);
                return new CreationResult(existing, false);
            }
            throw e;
        }
    }

    public Job create(Insert draft) {
        return createReturningFreshness(draft).job();
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
        return transition(jobId, expected, target, actor, reason, null);
    }

    /**
     * Guarded transition with optional leadership fencing: when fencingToken is present,
     * the write only lands while that token is still the live lease (ADR-004). Stale
     * leaders therefore cannot mutate state even mid-tick.
     */
    public boolean transition(UUID jobId, Set<JobStatus> expected, JobStatus target,
                              String actor, String reason, Long leadershipFencingToken) {
        List<String> expectedNames = expected.stream().map(Enum::name).toList();
        String placeholders = String.join(",", expectedNames.stream().map(s -> "?").toList());
        StringBuilder sql = new StringBuilder("""
                WITH moved AS (
                    UPDATE jobs SET status = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN (%s)
                """.formatted(placeholders));
        if (leadershipFencingToken != null) {
            sql.append("""
                    
                      AND EXISTS (SELECT 1 FROM scheduler_leases l
                                  WHERE l.resource_name = 'SCHEDULER_LEADER'
                                    AND l.fencing_token = ?
                                    AND l.expires_at > now())
                    """);
        }
        sql.append("""
                    
                    RETURNING id
                )
                SELECT count(*) FROM moved
                """);
        List<Object> args = new ArrayList<>();
        args.add(target.name());
        args.add(jobId);
        args.addAll(expectedNames);
        if (leadershipFencingToken != null) {
            args.add(leadershipFencingToken);
        }
        int updated = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        boolean ok = updated != 0;
        if (ok) {
            events.append(jobId, null, eventTypeFor(target), actor, reason, null);
        }
        return ok;
    }

    /**
     * Terminal transition whose authority comes from the paired EXECUTION's fencing
     * token rather than convention: the update only lands if the current token holder
     * recorded that execution terminal in the same statement window.
     */
    public boolean transitionAfterExecution(UUID jobId, Set<JobStatus> expected,
                                            JobStatus target, UUID execId, long fencingToken,
                                            String actor, String reason) {
        List<String> expectedNames = expected.stream().map(Enum::name).toList();
        String placeholders = String.join(",", expectedNames.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(target.name());
        args.add(jobId);
        args.addAll(expectedNames);
        args.add(execId);
        args.add(fencingToken);
        int updated = jdbc.queryForObject("""
                WITH moved AS (
                    UPDATE jobs SET status = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN (%s)
                      AND EXISTS (SELECT 1 FROM job_executions e
                                  WHERE e.id = ? AND e.fencing_token = ?
                                    AND e.status IN ('COMPLETED','FAILED','CANCELLED'))
                    RETURNING id)
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

    /** Keyset pagination: stable under inserts, unlike offset paging. */
    public List<Job> listBefore(UUID tenantId, String statusOrNull, Instant before,
                                int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM jobs WHERE tenant_id = ? AND created_at < ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(Timestamp.from(before));
        if (statusOrNull != null && !statusOrNull.isBlank()) {
            sql.append(" AND status = ?");
            args.add(statusOrNull);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JOB, args.toArray());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
