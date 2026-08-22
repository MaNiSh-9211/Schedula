package com.schedula.persistence;

import com.schedula.common.model.JobSchedule;
import com.schedula.common.schedule.NextFireCalculator.Advance;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CronScheduleTest {

    private JobSchedule cron(String expr, String zone, Instant nextFire,
                             JobSchedule.MissedPolicy policy) {
        return new JobSchedule(UUID.randomUUID(), UUID.randomUUID(), "cron", "log", "{}",
                JobSchedule.Kind.CRON, null, expr, zone, policy,
                JobSchedule.State.ACTIVE, nextFire, null, 0, 1, null, Instant.now());
    }

    private static Instant at(String localDateTime, String zone) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zone)).toInstant();
    }

    @Test
    void dailyNoonUtcFiresWhenDue() {
        // due at 2026-06-01T12:00Z, evaluated one minute earlier
        Instant now = at("2026-06-01T11:59", "UTC");
        Instant next = at("2026-06-01T12:00", "UTC");
        Advance a = CronSupport.advance(cron("0 0 12 * * *", "UTC", next,
                JobSchedule.MissedPolicy.COALESCE), now);
        assertThat(a.hasMissed()).isFalse();
    }

    @Test
    void coalesceCountsSkippedDailyRuns() {
        Instant last = at("2026-06-01T12:00", "UTC");
        Instant now = at("2026-06-04T09:00", "UTC"); // 3 noons passed
        Advance a = CronSupport.advance(cron("0 0 12 * * *", "UTC", last,
                JobSchedule.MissedPolicy.COALESCE), now);
        assertThat(a.missedCount()).isEqualTo(3);
        assertThat(a.newNextFireAt()).isEqualTo(at("2026-06-04T12:00", "UTC"));
    }

    @Test
    void springForwardGapHourIsResolvedByZoneRules() {
        // Europe/Berlin, 2026-03-29: 02:00 -> 03:00 CET->CEST. A daily 02:30 cron has NO
        // 02:30 on that day; CronExpression must land on the next valid instant.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant before = at("2026-03-28T02:30", "Europe/Berlin");
        Advance a = CronSupport.advance(
                cron("0 30 2 * * *", "Europe/Berlin", before, JobSchedule.MissedPolicy.COALESCE),
                at("2026-03-30T01:00", "Europe/Berlin"));
        // first fire after the gap must be 2026-03-30 02:30 CEST (+02:00)
        ZonedDateTime expected = LocalDateTime.parse("2026-03-30T02:30").atZone(berlin);
        assertThat(a.missedCount()).isEqualTo(1); // 29th's occurrence coalesced into the run
        assertThat(a.newNextFireAt()).isEqualTo(expected.toInstant());
    }

    @Test
    void fallBackAmbiguousHourCountsBothWalls() {
        // Europe/Berlin, 2026-10-25: 03:00 -> 02:00 CEST->CET. A per-hour cron fires for
        // BOTH wall-clock passes of 02:xx; zone rules decide the instants.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant start = at("2026-10-25T01:30", "Europe/Berlin"); // before fallback
        CronExpression expr = CronExpression.parse("0 30 2 * * *");
        ZonedDateTime first = expr.next(start.atZone(berlin));
        ZonedDateTime second = expr.next(first);
        // both occurrences are wall 02:30 but distinct instants, one CEST one CET
        assertThat(first.toInstant()).isNotEqualTo(second.toInstant());
        assertThat(first.getOffset().getId()).isEqualTo("+02:00");
        assertThat(second.getOffset().getId()).isEqualTo("+01:00");

        // and the calculator recognizes the second wall-clock pass as a DISTINCT instant:
        // just before it fires, exactly one occurrence (the first pass) is due
        Advance a = CronSupport.advance(
                cron("0 30 2 * * *", "Europe/Berlin", first.toInstant(),
                        JobSchedule.MissedPolicy.COALESCE),
                second.toInstant().minusMillis(1));
        assertThat(a.missedCount()).isEqualTo(1);
        assertThat(a.newNextFireAt()).isEqualTo(second.toInstant());
    }

    @Test
    void tightCronDuringLongDowntimeIsCappedNotExplosive() {
        Instant last = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = last.plusSeconds(86_400 * 30); // 30 days of downtime
        Advance a = CronSupport.advance(cron("* * * * * *", "UTC", last,
                JobSchedule.MissedPolicy.RUN_ALL), now);
        assertThat(a.missedCount()).isEqualTo(com.schedula.common.schedule.NextFireCalculator.MISSED_COUNT_CAP);
        assertThat(a.newNextFireAt()).isAfter(now);
    }

    @Test
    void firstFireForCronRespectsTimezone() {
        var s = cron("0 0 9 * * *", "Asia/Kolkata", Instant.EPOCH, JobSchedule.MissedPolicy.COALESCE);
        Instant first = CronSupport.firstFire(s, Instant.parse("2026-06-01T00:00:00Z"));
        ZonedDateTime zdt = first.atZone(ZoneId.of("Asia/Kolkata"));
        assertThat(zdt.getHour()).isEqualTo(9);
    }
}



