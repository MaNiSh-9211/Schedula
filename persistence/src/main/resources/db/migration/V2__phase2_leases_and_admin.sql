-- Phase 2: execution leases, cooperative cancellation support, DLQ admin,
-- effect ledger for idempotent handlers, immutable audit trail.

ALTER TABLE job_executions ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_exec_lease_expired
    ON job_executions (lease_expires_at) WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE queue_messages ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_queue_dlq
    ON queue_messages (enqueued_at DESC) WHERE status = 'DEADLETTERED';

CREATE TABLE IF NOT EXISTS effect_records (
    tenant_id   UUID NOT NULL,
    job_id      UUID NOT NULL,
    effect_key  TEXT NOT NULL,
    result_json JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, job_id, effect_key)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id          BIGSERIAL PRIMARY KEY,
    actor       TEXT NOT NULL,
    tenant_id   UUID,
    action      TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id   TEXT,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_events (occurred_at DESC);
