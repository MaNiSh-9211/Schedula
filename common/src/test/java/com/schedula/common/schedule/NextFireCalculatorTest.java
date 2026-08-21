package com.schedula.common.schedule;

import com.schedula.common.model.JobSchedule;
import com.schedula.common.schedule.NextFireCalculator.Advance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NextFireCalculatorTest {

    private JobSchedule schedule(long intervalMs, Instant nextFire, JobSchedule.MissedPolicy policy) {
        return new JobSchedule(UUID.randomUUID(), UUID.randomUUID(), "s", "log", "{}",
                JobSchedule.Kind.FIXED_INTERVAL, intervalMs, "UTC", policy,
                JobSchedule.State.ACTIVE, nextFire, null, 0, Instant.now());
    }

    @Test
    void noMissWhenNextFireInFuture() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Advance a = NextFireCalculator.advance(schedule(60_000, now.plusSeconds(30), JobSchedule.MissedPolicy.COALESCE), now);
        assertThat(a.hasMissed()).isFalse();
        assertThat(a.newNextFireAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void coalesceCountsMissedAndJumpsPastNow() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = t0.plusSeconds(210);
        Advance a = NextFireCalculator.advance(schedule(60_000, t0, JobSchedule.MissedPolicy.COALESCE), now);
        assertThat(a.missedCount()).isEqualTo(4);
        assertThat(a.newNextFireAt()).isEqualTo(t0.plusSeconds(240));
    }

    @Test
    void exactBoundaryCountsAsDue() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Advance a = NextFireCalculator.advance(schedule(60_000, t0, JobSchedule.MissedPolicy.COALESCE), t0);
        assertThat(a.missedCount()).isEqualTo(1);
        assertThat(a.newNextFireAt()).isEqualTo(t0.plusSeconds(60));
    }

    @Test
    void runAllNotSupportedYet() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() ->
                NextFireCalculator.advance(schedule(60_000, t0, JobSchedule.MissedPolicy.RUN_ALL), t0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidIntervalRejected() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() ->
                NextFireCalculator.advance(schedule(0, t0, JobSchedule.MissedPolicy.COALESCE), t0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
