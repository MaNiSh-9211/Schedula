package com.schedula.common.schedule;

import com.schedula.common.model.JobSchedule;

import java.time.Instant;

public final class NextFireCalculator {

    public record Advance(int missedCount, Instant newNextFireAt) {
        public boolean hasMissed() {
            return missedCount > 0;
        }
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
        while (!next.isAfter(now)) {
            missed++;
            next = next.plusMillis(interval);
        }
        return switch (schedule.missedPolicy()) {
            case COALESCE, SKIP_TO_LATEST -> new Advance(missed, next);
            case RUN_ALL -> throw new UnsupportedOperationException(
                    "RUN_ALL catch-up is not supported yet; use COALESCE or SKIP_TO_LATEST");
        };
    }

    public static Instant firstFire(long intervalMs, Instant from) {
        return from.plusMillis(intervalMs);
    }
}
