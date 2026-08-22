package com.schedula.persistence;

import com.schedula.common.model.JobSchedule;
import com.schedula.common.schedule.NextFireCalculator;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Cron evaluation in the schedule's timezone (Phase 5). DST gaps and overlaps are
 * resolved by java.time zone rules via ZonedDateTime arithmetic inside CronExpression.
 * Long-downtime catch-up is capped (see NextFireCalculator.MISSED_COUNT_CAP) so tight
 * crons degrade toward COALESCE instead of exploding the scheduler after an outage.
 */
public final class CronSupport {

    private CronSupport() {
    }

    public static NextFireCalculator.Advance advance(JobSchedule schedule, Instant now) {
        if (schedule.cronExpr() == null || schedule.cronExpr().isBlank()) {
            throw new IllegalArgumentException("CRON requires cronExpr");
        }
        ZoneId zone = ZoneId.of(schedule.timezone() == null ? "UTC" : schedule.timezone());
        CronExpression expr = CronExpression.parse(schedule.cronExpr());

        ZonedDateTime fire = schedule.nextFireAt().atZone(zone);
        int missed = 0;
        while (!fire.toInstant().isAfter(now)) {
            missed++;
            ZonedDateTime next = expr.next(fire);
            if (next == null) {
                return new NextFireCalculator.Advance(missed, now.plusSeconds(86_400));
            }
            fire = next;
            if (missed >= NextFireCalculator.MISSED_COUNT_CAP) {
                ZonedDateTime jump = expr.next(now.atZone(zone));
                return new NextFireCalculator.Advance(missed,
                        jump == null ? now.plusSeconds(86_400) : jump.toInstant());
            }
        }
        return new NextFireCalculator.Advance(missed, fire.toInstant());
    }

    public static Instant firstFire(JobSchedule schedule, Instant from) {
        ZoneId zone = ZoneId.of(schedule.timezone() == null ? "UTC" : schedule.timezone());
        ZonedDateTime next = CronExpression.parse(schedule.cronExpr()).next(from.atZone(zone));
        return next == null ? from.plusSeconds(86_400) : next.toInstant();
    }
}
