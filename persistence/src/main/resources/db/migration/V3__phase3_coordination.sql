-- Phase 3: scheduler cluster membership and leader lease (ADR-005).
-- The lease row is the single leadership truth; fencing tokens make any
-- write by an ex-leader inert even while it still believes it leads.

CREATE TABLE IF NOT EXISTS scheduler_nodes (
    node_id      UUID PRIMARY KEY,
    host         TEXT NOT NULL,
    port         INT,
    version      TEXT NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scheduler_nodes_liveness
    ON scheduler_nodes (last_seen_at);

CREATE TABLE IF NOT EXISTS scheduler_leases (
    resource_name TEXT PRIMARY KEY,
    owner_node_id UUID NOT NULL,
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT NOT NULL
);

INSERT INTO fence_counters (resource_name, counter)
VALUES ('SCHEDULER_LEADER', 0)
ON CONFLICT (resource_name) DO NOTHING;
