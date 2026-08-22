package com.schedula.common.schedule;

import com.schedula.common.model.JobSchedule;

import java.time.Instant;
import java.util.List;

/**
 * Pure fixed-interval arithmetic. Cron evaluation lives in
 * com.schedula.persistence.CronSupport because it relies on Spring's CronExpression
 * and timezone-aware ZonedDateTime rules.
 */
public final class NextFireCalculator {

    /**
     * Safety valve: during long downtime a tight interval could owe thousands of
     * occurrences. Beyond this cap counting stops and jumps ahead — degrading toward
     * COALESCE, which is the safe direction (never explosive).
     */
    public static final int MISSED_COUNT_CAP = 10_000;

    public record Advance(int missedCount, Instant newNextFireAt,
                          java.util.List<Instant> dueWindows) {
        public boolean hasMissed() {
            return missedCount > 0;
        }
    }

    private static Advance single(int missed, Instant next) {
        return new Advance(missed, next, List.of(next));
    }

    private NextFireCalculator() {
    }

    public static Advance advance(JobSchedule schedule, Instant now) {
        if (schedule.kind() != JobSchedule.Kind.FIXED_INTERVAL) {
            throw new IllegalArgumentException("unsupported schedule kind: " + schedule.kind());
        }
        if (schedule.intervalMs() == null || schedule.intervalMs() <= 0) {
            throw new IllegalArgumentException("FIXED_INTERVAL requires positive intervalMs");
        }
        long interval = schedule.intervalMs();
        Instant next = schedule.nextFireAt();
        int missed = 0;
        var windows = new java.util.ArrayList<Instant>();
        while (!next.isAfter(now)) {
            missed++;
            windows.add(next);
            if (missed >= MISSED_COUNT_CAP) {
                // cap: degrade toward COALESCE, never explode the scheduler
                if (schedule.missedPolicy() == JobSchedule.MissedPolicy.RUN_ALL) {
                    return new Advance(missed, next, java.util.List.copyOf(windows));
                }
                return single(missed, next);
            }
            next = next.plusMillis(interval);
        }
        return switch (schedule.missedPolicy()) {
            case COALESCE, SKIP_TO_LATEST -> single(missed, next);
            case RUN_ALL -> {
                if (windows.isEmpty()) yield single(0, next);
                Instant last = windows.get(windows.size() - 1);
                yield new Advance(missed, next, java.util.List.copyOf(windows));
            }
        };
    }

    public static Instant firstFire(long intervalMs, Instant from) {
        return from.plusMillis(intervalMs);
    }
}


