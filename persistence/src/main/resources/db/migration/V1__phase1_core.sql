-- Phase 1 core schema: jobs, executions, events, fixed-interval schedules,
-- durable queue, workers, fence counters. See docs/DATA-MODEL.md.

CREATE TABLE tenants (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    status     TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenants (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'default');

CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    job_type        TEXT NOT NULL,
    priority        INT  NOT NULL DEFAULT 0,
    status          TEXT NOT NULL,
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_attempts    INT  NOT NULL DEFAULT 3,
    retry_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    timeout_ms      BIGINT NOT NULL DEFAULT 60000,
    scheduled_for   TIMESTAMPTZ,
    schedule_id     UUID,
    idempotency_key TEXT,
    attempts_made   INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_jobs_tenant_idem
    ON jobs (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_jobs_due_scheduled
    ON jobs (scheduled_for) WHERE status = 'SCHEDULED';

CREATE INDEX idx_jobs_due_retry
    ON jobs (next_attempt_at) WHERE status = 'RETRY_WAIT';

CREATE INDEX idx_jobs_tenant_created
    ON jobs (tenant_id, created_at DESC);

CREATE INDEX idx_jobs_cancelling
    ON jobs (status) WHERE status IN ('CANCELLING');

CREATE TABLE job_executions (
    id             UUID PRIMARY KEY,
    job_id         UUID NOT NULL REFERENCES jobs(id),
    attempt_no     INT  NOT NULL,
    status         TEXT NOT NULL,
    worker_id      UUID,
    fencing_token  BIGINT NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error_class    TEXT,
    error_detail   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, attempt_no)
);

CREATE INDEX idx_exec_running
    ON job_executions (worker_id, status) WHERE status = 'RUNNING';

CREATE TABLE job_events (
    id               BIGSERIAL PRIMARY KEY,
    job_id           UUID NOT NULL,
    job_execution_id UUID,
    event_type       TEXT NOT NULL,
    actor            TEXT NOT NULL,
    reason           TEXT,
    fencing_token    BIGINT,
    payload_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_job ON job_events (job_id, occurred_at);
CREATE INDEX idx_events_type ON job_events (event_type, occurred_at);

CREATE TABLE job_schedules (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    name             TEXT NOT NULL,
    job_type         TEXT NOT NULL,
    payload_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    kind             TEXT NOT NULL,
    interval_ms      BIGINT,
    timezone         TEXT NOT NULL DEFAULT 'UTC',
    missed_policy    TEXT NOT NULL DEFAULT 'COALESCE',
    state            TEXT NOT NULL DEFAULT 'ACTIVE',
    next_fire_at     TIMESTAMPTZ NOT NULL,
    last_enqueued_at TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedules_due
    ON job_schedules (next_fire_at) WHERE state = 'ACTIVE';

CREATE SEQUENCE queue_enqueue_seq START 1;

CREATE TABLE queue_messages (
    id                UUID PRIMARY KEY,
    queue_name        TEXT NOT NULL,
    job_execution_id  UUID,
    job_id            UUID NOT NULL,
    tenant_id         UUID NOT NULL,
    priority          INT NOT NULL DEFAULT 0,
    enqueue_seq       BIGINT NOT NULL DEFAULT nextval('queue_enqueue_seq'),
    status            TEXT NOT NULL DEFAULT 'READY',
    available_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_owner       UUID,
    claim_expires_at  TIMESTAMPTZ,
    deliver_count     INT NOT NULL DEFAULT 0,
    enqueued_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- available_at filtering happens at query time: now() is not IMMUTABLE and
-- therefore not allowed inside an index predicate.
CREATE INDEX idx_queue_ready
    ON queue_messages (queue_name, priority DESC, enqueue_seq)
    WHERE status = 'READY';

CREATE INDEX idx_queue_claimed_expired
    ON queue_messages (claim_expires_at) WHERE status = 'CLAIMED';

CREATE TABLE workers (
    id                 UUID PRIMARY KEY,
    name               TEXT NOT NULL,
    version            TEXT NOT NULL,
    capabilities       TEXT[] NOT NULL DEFAULT '{}',
    max_concurrency    INT NOT NULL DEFAULT 8,
    running_count      INT NOT NULL DEFAULT 0,
    status             TEXT NOT NULL DEFAULT 'HEALTHY',
    last_heartbeat_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workers_liveness ON workers (status, last_heartbeat_at);

CREATE TABLE fence_counters (
    resource_name TEXT PRIMARY KEY,
    counter       BIGINT NOT NULL DEFAULT 0
);

INSERT INTO fence_counters (resource_name, counter) VALUES ('EXECUTION', 0);
