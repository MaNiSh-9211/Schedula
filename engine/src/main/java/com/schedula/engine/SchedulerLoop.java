package com.schedula.engine;

import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.JobSchedule;
import com.schedula.common.schedule.NextFireCalculator;
import com.schedula.common.schedule.NextFireCalculator.Advance;
import com.schedula.common.time.Clock;
import com.schedula.persistence.JobStore;
import com.schedula.persistence.ScheduleStore;
import com.schedula.queue.PostgresQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduler: turns due work into queued work.
 * <p>
 * SCHEDULED -> QUEUED enqueues a message in the same transaction as the transition, so a
 * crash can never produce "eligible job with no message" or vice versa (ADR-001/006).
 * Schedule ticks advance next_fire_at via CAS inside the same tx as occurrence creation,
 * making double-fire impossible and skips crash-impossible.
 */
@Service
public class SchedulerLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final JobStore jobs;
    private final ScheduleStore schedules;
    private final PostgresQueue queue;
    private final Clock clock;
    private final com.schedula.coordination.Coordinator coordinator;
    private final com.schedula.engine.workflow.WorkflowDriver workflowDriver;
    private final Timer schedulerLag;
    private final Counter scheduleTicksTotal;
    private final Counter missedOccurrencesTotal;

    public SchedulerLoop(JobStore jobs, ScheduleStore schedules, PostgresQueue queue,
                         Clock clock, com.schedula.coordination.Coordinator coordinator,
                         com.schedula.engine.workflow.WorkflowDriver workflowDriver,
                         MeterRegistry meters) {
        this.jobs = jobs;
        this.schedules = schedules;
        this.queue = queue;
        this.clock = clock;
        this.coordinator = coordinator;
        this.workflowDriver = workflowDriver;
        this.schedulerLag = Timer.builder("schedula_scheduler_lag")
                .description("dispatch time minus scheduled_for")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        this.scheduleTicksTotal = Counter.builder("schedula_schedule_ticks_total").register(meters);
        this.missedOccurrencesTotal = Counter.builder("schedula_missed_occurrences_total")
                .description("coalesced occurrences from missed schedule windows")
                .register(meters);
    }

    /** Leader-only: followers return immediately (fail-closed coordination, ADR-005). */
    public void tick() {
        if (!coordinator.isLeader()) {
            return;
        }
        long token = coordinator.fencingToken();
        enqueueDueScheduled(token);
        releaseDueRetries(token);
        tickSchedules(token);
    }

    private void enqueueDueScheduled(long leadershipToken) {
        Instant now = clock.now();
        var due = jobs.findDueScheduled(now, 100);
        if (!due.isEmpty()) {
            log.debug("scheduler found {} due scheduled jobs", due.size());
        }
        for (Job job : due) {
            boolean moved = jobs.transition(job.id(), Set.of(JobStatus.SCHEDULED),
                    JobStatus.QUEUED, "scheduler", "due at " + job.scheduledFor(), leadershipToken);
            if (!moved) {
                log.warn("job {} left SCHEDULED before enqueue; skipping", job.id());
                continue;
            }
            queue.enqueue(job.id(), job.queueName(), job.tenantId(), null, job.priority(), now);
            if (job.scheduledFor() != null) {
                schedulerLag.record(java.time.Duration.between(job.scheduledFor(), now));
            }
        }
    }

    /** Retry-wait jobs already have their (nacked) message; only the job state flips. */
    private void releaseDueRetries(long leadershipToken) {
        Instant now = clock.now();
        for (Job job : jobs.findDueRetries(now, 100)) {
            jobs.transition(job.id(), Set.of(JobStatus.RETRY_WAIT), JobStatus.QUEUED,
                    "scheduler", "retry due", leadershipToken);
        }
    }

    private void tickSchedules(long leadershipToken) {
        Instant now = clock.now();
        for (JobSchedule s : schedules.findDue(now, 50)) {
            Advance advance = s.kind() == JobSchedule.Kind.CRON
                    ? com.schedula.persistence.CronSupport.advance(s, now)
                    : NextFireCalculator.advance(s, now);
            if (!advance.hasMissed()) continue;
            boolean advanced = schedules.advanceFire(s.id(), s.version(),
                    advance.newNextFireAt(), now, leadershipToken);
            if (!advanced) {
                log.debug("schedule {} advance lost race; skipping tick", s.id());
                continue;
            }
            // Airflow-style backfill: RUN_ALL materializes every missed window (capped);
            // COALESCE/SKIP produce exactly one occurrence.
            int createdCount = 0;
            UUID lastJobId = null;
            for (Instant window : advance.dueWindows()) {
                try {
                    if (s.targetWorkflow() != null && !s.targetWorkflow().isBlank()) {
                        workflowDriver.start(s.tenantId(), s.targetWorkflow(),
                                "{\"schedule\":\"" + s.name() + "\",\"window\":\""
                                        + window + "\"}");
                        createdCount++;
                        continue;
                    }
                    UUID created = createOccurrence(s, window);
                    createdCount++;
                    lastJobId = created;
                } catch (RuntimeException e) {
                    log.warn("occurrence creation failed for schedule {} window {}: {}",
                            s.name(), window, e.toString());
                }
            }
            scheduleTicksTotal.increment();
            if (advance.missedCount() > 1) {
                missedOccurrencesTotal.increment(advance.missedCount() - 1L);
            }
            log.info("schedule {} fired: occurrences={} missed={} last={}",
                    s.name(), createdCount, advance.missedCount(), lastJobId);
        }
    }

    private java.util.UUID createOccurrence(JobSchedule s, Instant window) {
        Job created = jobs.create(new JobStore.Insert(
                s.tenantId(), s.jobType(), 0, s.payloadJson(), null, "{}",
                60_000L, window, s.id(),
                s.name() + ":w:" + window.toEpochMilli(), null, null, null,
                s.targetWorkflow() == null ? "default" : "default",
                null));
        return created.id();
    }
}




