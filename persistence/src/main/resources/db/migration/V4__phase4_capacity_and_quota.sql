-- Phase 4: resource-aware dispatch, concurrency caps, backpressure quotas.
-- Zero/absent requirements and capacities mean "unconstrained" so existing
-- workloads behave identically after upgrade.

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS required_capabilities TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS required_cpu INT NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS required_mem_mb BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_jobs_type ON jobs (job_type);

ALTER TABLE workers ADD COLUMN IF NOT EXISTS cpu_capacity INT NOT NULL DEFAULT 0;
ALTER TABLE workers ADD COLUMN IF NOT EXISTS mem_capacity_mb BIGINT NOT NULL DEFAULT 0;

-- §26: cap concurrent executions per job type (e.g. generate-report <= 20).
CREATE TABLE IF NOT EXISTS job_type_limits (
    job_type       TEXT PRIMARY KEY,
    max_concurrent INT NOT NULL
);

-- §27/§28: per-tenant admission (pending backlog) and dispatch (concurrency) limits.
CREATE TABLE IF NOT EXISTS tenant_quotas (
    tenant_id               UUID PRIMARY KEY REFERENCES tenants(id),
    max_pending_jobs        BIGINT NOT NULL DEFAULT 9223372036854775807,
    max_concurrent_executions INT  NOT NULL DEFAULT 2147483647,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
