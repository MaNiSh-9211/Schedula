package com.schedula.engine;

import io.micrometer.core.instrument.Counter;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Retention (§52): terminal history is deleted after configured ages so operational
 * tables stay small and hot. Bounded-batch deletes; leader-only like the recovery sweep.
 * Archival (export before delete) is deliberately NOT implemented yet — documented gap.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH = 5_000;

    private final JdbcTemplate jdbc;
    private final com.schedula.coordination.Coordinator coordinator;
    private final int terminalJobHours;
    private final int auditHours;
    private final String archiveDir;
    private final Counter deletedJobs;
    private final Counter deletedEvents;
    private final Counter deletedMessages;
    private final Counter deletedAudits;

    public RetentionService(JdbcTemplate jdbc,
                            com.schedula.coordination.Coordinator coordinator,
                            MeterRegistry meters,
                            @Value("${schedula.retention.terminal-job-hours:720}") int terminalJobHours,
                            @Value("${schedula.retention.audit-hours:87600}") int auditHours,
                            @Value("${schedula.retention.archive-dir:}") String archiveDir) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.terminalJobHours = terminalJobHours;
        this.auditHours = auditHours;
        this.archiveDir = archiveDir == null ? "" : archiveDir.trim();
        this.deletedJobs = Counter.builder("schedula_retention_deleted_total")
                .tag("kind", "job").register(meters);
        this.deletedEvents = Counter.builder("schedula_retention_deleted_total")
                .tag("kind", "event").register(meters);
        this.deletedMessages = Counter.builder("schedula_retention_deleted_total")
                .tag("kind", "message").register(meters);
        this.deletedAudits = Counter.builder("schedula_retention_deleted_total")
                .tag("kind", "audit").register(meters);
    }

    public void run() {
        if (!coordinator.isLeader()) {
            return;
        }
        try {
            purgeTerminalHistory();
            purgeDoneMessages();
            purgeAuditTrail();
        } catch (RuntimeException e) {
            log.warn("retention pass failed: {}", e.toString());
        }
    }

    private void purgeTerminalHistory() {
        for (int pass = 0; pass < 40; pass++) {
            List<UUID> doomed = jdbc.queryForList("""
                    SELECT id FROM jobs
                    WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                      AND updated_at < now() - (? * interval '1 hour')
                    LIMIT ?
                    """, UUID.class, terminalJobHours, BATCH);
            if (doomed.isEmpty()) return;

            if (!archiveDir.isEmpty()) archive(doomed);

            int events = jdbc.update("""
                    DELETE FROM job_events WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, terminalJobHours, BATCH);
            int execs = jdbc.update("""
                    DELETE FROM job_executions WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, terminalJobHours, BATCH);
            jdbc.update("""
                    DELETE FROM queue_messages WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, terminalJobHours, BATCH);
            int jobs = deleteIds("""
                    DELETE FROM jobs
                    WHERE id = ANY(?)
                      AND status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                    """, doomed);

            deletedEvents.increment(events);
            deletedJobs.increment(jobs);
            if (jobs == 0) return;
        }
    }

    /**
     * Batch delete helper that binds a UUID list safely via createArrayOf (pgjdbc cannot
     * cast a bound parameter to uuid[] inline).
     */
    private int deleteIds(String sql, List<UUID> ids) {
        final java.sql.Array[] arr = new java.sql.Array[1];
        Integer n = jdbc.execute((java.sql.Connection con) -> {
            arr[0] = con.createArrayOf("uuid", ids.toArray(new UUID[0]));
            try (var ps = con.prepareStatement(sql)) {
                ps.setArray(1, arr[0]);
                return ps.executeUpdate();
            }
        });
        return n == null ? 0 : n;
    }

    /** Export-before-delete (§52): one JSONL file per retention pass. */
    private void archive(List<UUID> doomed) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(archiveDir);
            java.nio.file.Files.createDirectories(dir);
            var file = dir.resolve("retired-jobs-" +
                    java.time.LocalDate.now() + "-" + System.currentTimeMillis() + ".jsonl");
            try (var w = java.nio.file.Files.newBufferedWriter(file)) {
                for (UUID id : doomed) {
                    var job = jdbc.queryForMap(
                            "SELECT * FROM jobs WHERE id = ?", id);
                    w.write(jsonOf(job));
                    w.write("\n");
                    for (var e : jdbc.queryForList(
                            "SELECT * FROM job_events WHERE job_id = ?", id)) {
                        w.write(jsonOf(e));
                        w.write("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("archival export failed; deleting anyway per retention policy: {}", e.toString());
        }
    }

    private String jsonOf(java.util.Map<String, Object> row) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(row);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void purgeDoneMessages() {
        int n;
        do {
            n = jdbc.update("""
                    DELETE FROM queue_messages
                    WHERE status IN ('DONE', 'CANCELLED', 'DEADLETTERED')
                      AND resolved_at IS NOT NULL
                      AND resolved_at < now() - (? * interval '1 hour')
                      AND id IN (
                        SELECT id FROM queue_messages
                        WHERE status IN ('DONE', 'CANCELLED', 'DEADLETTERED')
                          AND resolved_at IS NOT NULL
                          AND resolved_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, terminalJobHours, terminalJobHours, BATCH);
            if (n > 0) deletedMessages.increment(n);
        } while (n >= BATCH);
    }

    private void purgeAuditTrail() {
        int n;
        do {
            n = jdbc.update("""
                    DELETE FROM audit_events
                    WHERE occurred_at < now() - (? * interval '1 hour')
                      AND id IN (
                        SELECT id FROM audit_events
                        WHERE occurred_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, auditHours, auditHours, BATCH);
            if (n > 0) deletedAudits.increment(n);
        } while (n >= BATCH);
    }
}

