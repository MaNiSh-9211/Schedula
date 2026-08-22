package com.schedula.persistence;

import com.schedula.common.model.JobSchedule;
import com.schedula.common.schedule.NextFireCalculator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ScheduleStore {

    private static final RowMapper<JobSchedule> SCHEDULE = (rs, i) -> new JobSchedule(
            Mappers.uuid(rs, "id"),
            Mappers.uuid(rs, "tenant_id"),
            rs.getString("name"),
            rs.getString("job_type"),
            rs.getString("payload_json"),
            JobSchedule.Kind.valueOf(rs.getString("kind")),
            rs.getObject("interval_ms") == null ? null : rs.getLong("interval_ms"),
            rs.getString("cron_expr"),
            rs.getString("timezone"),
            JobSchedule.MissedPolicy.valueOf(rs.getString("missed_policy")),
            JobSchedule.State.valueOf(rs.getString("state")),
            Mappers.instant(rs, "next_fire_at"),
            Mappers.instant(rs, "last_enqueued_at"),
            rs.getLong("version"),
            1,
            Mappers.instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public ScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Insert(UUID tenantId, String name, String jobType, String payloadJson,
                         Long intervalMs, String cronExpr, String timezone,
                         String missedPolicy) {
    }

    public JobSchedule create(Insert draft) {
        UUID id = com.schedula.common.ids.UuidV7.generate();
        JobSchedule.Kind kind = draft.cronExpr() != null && !draft.cronExpr().isBlank()
                ? JobSchedule.Kind.CRON : JobSchedule.Kind.FIXED_INTERVAL;
        String tz = draft.timezone() == null || draft.timezone().isBlank()
                ? "UTC" : draft.timezone();
        var probe = new JobSchedule(id, draft.tenantId(), draft.name(), draft.jobType(),
                "{}", kind, draft.intervalMs(), draft.cronExpr(), tz,
                JobSchedule.MissedPolicy.COALESCE, JobSchedule.State.ACTIVE,
                Instant.EPOCH, null, 0, 1, Instant.now());
        Instant firstFire = kind == JobSchedule.Kind.CRON
                ? CronSupport.firstFire(probe, Instant.now())
                : NextFireCalculator.firstFire(draft.intervalMs(), Instant.now());
        jdbc.update("""
                        INSERT INTO job_schedules (id, tenant_id, name, job_type, payload_json,
                            kind, interval_ms, cron_expr, timezone, missed_policy, next_fire_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                        """,
                id, draft.tenantId(), draft.name(), draft.jobType(),
                Mappers.canonicalize(draft.payloadJson()), kind.name(),
                draft.intervalMs(), draft.cronExpr(), tz, draft.missedPolicy(),
                Timestamp.from(firstFire));
        return findById(id).orElseThrow();
    }

    public Optional<JobSchedule> findById(UUID id) {
        return jdbc.query("SELECT * FROM job_schedules WHERE id = ?", SCHEDULE, id).stream().findFirst();
    }

    public List<JobSchedule> findDue(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM job_schedules
                        WHERE state = 'ACTIVE' AND next_fire_at <= ?
                        ORDER BY next_fire_at LIMIT ?
                        """, SCHEDULE, Timestamp.from(now), limit);
    }

    /** CAS advance: only wins if version unchanged; prevents double-advance races. */
    public boolean advanceFire(UUID id, long expectedVersion, Instant newNextFireAt,
                               Instant lastEnqueuedAt) {
        return advanceFire(id, expectedVersion, newNextFireAt, lastEnqueuedAt, null);
    }

    /** Leadership-fenced variant: stale schedulers cannot advance schedules. */
    public boolean advanceFire(UUID id, long expectedVersion, Instant newNextFireAt,
                               Instant lastEnqueuedAt, Long leadershipFencingToken) {
        StringBuilder sql = new StringBuilder("""
                WITH moved AS (
                    UPDATE job_schedules SET next_fire_at = ?, last_enqueued_at = ?,
                        version = version + 1
                    WHERE id = ? AND version = ?
                """);
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
        var args = new java.util.ArrayList<Object>();
        args.add(Timestamp.from(newNextFireAt));
        args.add(Timestamp.from(lastEnqueuedAt));
        args.add(id);
        args.add(expectedVersion);
        if (leadershipFencingToken != null) {
            args.add(leadershipFencingToken);
        }
        Integer updated = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return updated != 0;
    }

    public boolean setState(UUID id, JobSchedule.State state) {
        return jdbc.update("UPDATE job_schedules SET state = ? WHERE id = ?", state.name(), id) == 1;
    }
}
