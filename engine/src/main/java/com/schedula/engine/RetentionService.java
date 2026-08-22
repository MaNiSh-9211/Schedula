package com.schedula.engine;

import io.micrometer.core.instrument.Counter;
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
    private final Counter deletedJobs;
    private final Counter deletedEvents;
    private final Counter deletedMessages;
    private final Counter deletedAudits;

    public RetentionService(JdbcTemplate jdbc,
                            com.schedula.coordination.Coordinator coordinator,
                            MeterRegistry meters,
                            @Value("${schedula.retention.terminal-job-hours:720}") int terminalJobHours,
                            @Value("${schedula.retention.audit-hours:87600}") int auditHours) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.terminalJobHours = terminalJobHours;
        this.auditHours = auditHours;
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
            int events = jdbc.update("""
                    DELETE FROM job_events WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?
                    )""", terminalJobHours, BATCH);
            int execs = jdbc.update("""
                    DELETE FROM job_executions WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?
                    )""", terminalJobHours, BATCH);
            jdbc.update("""
                    DELETE FROM queue_messages WHERE job_id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                          AND id IN (SELECT id FROM jobs
                                     WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                                       AND updated_at < now() - (? * interval '1 hour')
                                     LIMIT ?)
                    )""", terminalJobHours, terminalJobHours, BATCH);
            int jobs = jdbc.update("""
                    DELETE FROM jobs
                    WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                      AND updated_at < now() - (? * interval '1 hour')
                      AND id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('COMPLETED','FAILED_TERMINAL','DEAD','CANCELLED','REJECTED')
                          AND updated_at < now() - (? * interval '1 hour')
                        LIMIT ?)
                    """, terminalJobHours, terminalJobHours, BATCH);

            deletedEvents.increment(events);
            deletedJobs.increment(jobs);
            if (jobs == 0 && events == 0 && execs == 0) return;
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
