-- Phase 7/8 hardening batch:
-- 1) schedule names are unique per tenant (idempotent creation, no double fires)
-- 2) submission rate limit per tenant (requests/minute sliding window)

DELETE FROM job_schedules s
USING job_schedules s2
WHERE s.tenant_id = s2.tenant_id AND s.name = s2.name AND s.id > s2.id;

ALTER TABLE job_schedules DROP CONSTRAINT IF EXISTS uq_sched_tenant_name;
ALTER TABLE job_schedules ADD CONSTRAINT uq_sched_tenant_name UNIQUE (tenant_id, name);

ALTER TABLE tenant_quotas ADD COLUMN IF NOT EXISTS max_submit_per_min BIGINT
    NOT NULL DEFAULT 9223372036854775807;
