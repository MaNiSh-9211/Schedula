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
    private final Timer schedulerLag;
    private final Counter scheduleTicksTotal;
    private final Counter missedOccurrencesTotal;

    public SchedulerLoop(JobStore jobs, ScheduleStore schedules, PostgresQueue queue,
                         Clock clock, MeterRegistry meters) {
        this.jobs = jobs;
        this.schedules = schedules;
        this.queue = queue;
        this.clock = clock;
        this.schedulerLag = Timer.builder("schedula_scheduler_lag")
                .description("dispatch time minus scheduled_for")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        this.scheduleTicksTotal = Counter.builder("schedula_schedule_ticks_total").register(meters);
        this.missedOccurrencesTotal = Counter.builder("schedula_missed_occurrences_total")
                .description("coalesced occurrences from missed schedule windows")
                .register(meters);
    }

    public void tick() {
        enqueueDueScheduled();
        releaseDueRetries();
        tickSchedules();
    }

    private void enqueueDueScheduled() {
        Instant now = clock.now();
        for (Job job : jobs.findDueScheduled(now, 100)) {
            boolean moved = jobs.transition(job.id(), Set.of(JobStatus.SCHEDULED),
                    JobStatus.QUEUED, "scheduler", "due at " + job.scheduledFor());
            if (!moved) continue;
            queue.enqueue(job.id(), job.tenantId(), null, job.priority(), now);
            if (job.scheduledFor() != null) {
                schedulerLag.record(java.time.Duration.between(job.scheduledFor(), now));
            }
        }
    }

    /** Retry-wait jobs already have their (nacked) message; only the job state flips. */
    private void releaseDueRetries() {
        Instant now = clock.now();
        for (Job job : jobs.findDueRetries(now, 100)) {
            jobs.transition(job.id(), Set.of(JobStatus.RETRY_WAIT), JobStatus.QUEUED,
                    "scheduler", "retry due");
        }
    }

    private void tickSchedules() {
        Instant now = clock.now();
        for (JobSchedule s : schedules.findDue(now, 50)) {
            Advance advance = NextFireCalculator.advance(s, now);
            if (!advance.hasMissed()) continue;
            boolean advanced = schedules.advanceFire(s.id(), s.version(),
                    advance.newNextFireAt(), now);
            if (!advanced) {
                log.debug("schedule {} advance lost race; skipping tick", s.id());
                continue;
            }
            UUID jobId = createOccurrence(s, advance);
            scheduleTicksTotal.increment();
            if (advance.missedCount() > 1) {
                missedOccurrencesTotal.increment(advance.missedCount() - 1L);
            }
            log.info("schedule {} fired: job={} missed={}", s.name(), jobId, advance.missedCount());
        }
    }

    private UUID createOccurrence(JobSchedule s, Advance advance) {
        Job created = jobs.create(new JobStore.Insert(
                s.tenantId(), s.jobType(), 0, s.payloadJson(), null, "{}",
                60_000L, clock.now(), s.id(), s.name() + ":" + advance.newNextFireAt().toEpochMilli()));
        return created.id();
    }
}
