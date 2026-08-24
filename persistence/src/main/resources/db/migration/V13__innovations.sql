-- Phase 9 innovations: temporal decay priority, cascade firewall, poison detection

-- Cascade Failure Firewall: track dependency health by extracted error host
CREATE TABLE IF NOT EXISTS dependency_health (
    host            TEXT PRIMARY KEY,
    failure_count   INT NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMPTZ,
    quarantined     BOOLEAN NOT NULL DEFAULT FALSE,
    quarantined_at  TIMESTAMPTZ,
    recovered_at    TIMESTAMPTZ
);

-- Poison pill detection: track cross-worker failures per idempotency lineage
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS poison BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS poison_workers TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_jobs_poison ON jobs (id) WHERE poison = TRUE;
