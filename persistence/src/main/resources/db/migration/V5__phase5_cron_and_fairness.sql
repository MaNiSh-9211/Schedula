-- Phase 5: cron schedules with timezone-aware evaluation.
-- kind='CRON' rows use cron_expr; interval_ms stays NULL. next_fire_at is
-- always a UTC instant, recomputed lazily near fire time so tzdb updates and
-- DST transitions self-correct without far-future materialization.

ALTER TABLE job_schedules ADD COLUMN IF NOT EXISTS cron_expr TEXT;

-- tenant weights for weighted fair dispatch (default 1)
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS weight INT NOT NULL DEFAULT 1;
