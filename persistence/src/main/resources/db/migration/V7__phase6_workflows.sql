-- Phase 6: durable workflow engine. Workflows are DAGs of tasks; every task is
-- backed by a REAL platform job, inheriting retries, leases, timeouts and the DLQ.
-- State is fully persisted: any process can die at any point and the driver
-- recomputes from these rows alone (§35/§36).

CREATE TABLE IF NOT EXISTS workflows (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS workflow_versions (
    id          UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id),
    version     INT NOT NULL,
    definition  JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, version)
);

CREATE TABLE IF NOT EXISTS workflow_executions (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions(id),
    status              TEXT NOT NULL,   -- RUNNING|FAILING|COMPENSATING|COMPLETED|FAILED|CANCELLED
    compensated         BOOLEAN NOT NULL DEFAULT FALSE,
    input               JSONB NOT NULL DEFAULT '{}'::jsonb,
    output              JSONB,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wf_exec_running
    ON workflow_executions (status) WHERE status IN ('RUNNING','FAILING','COMPENSATING');

CREATE TABLE IF NOT EXISTS workflow_task_executions (
    id              UUID PRIMARY KEY,
    wf_execution_id UUID NOT NULL REFERENCES workflow_executions(id),
    task_key        TEXT NOT NULL,
    kind            TEXT NOT NULL DEFAULT 'JOB',   -- JOB | WAIT | UNDO
    undo_for        TEXT,
    status          TEXT NOT NULL,   -- BLOCKED|RUNNING|SUCCEEDED|FAILED_PERMANENT|SKIPPED|CANCELLED
    depends_on      TEXT[] NOT NULL DEFAULT '{}',
    job_type        TEXT,
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempt_no      INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    wait_ms         BIGINT,
    job_id          UUID,
    error_class     TEXT,
    error_detail    TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    UNIQUE (wf_execution_id, task_key)
);

CREATE INDEX IF NOT EXISTS idx_wft_by_job ON workflow_task_executions (job_id);
CREATE INDEX IF NOT EXISTS idx_wft_open ON workflow_task_executions (wf_execution_id)
    WHERE status IN ('BLOCKED','RUNNING');

CREATE TABLE IF NOT EXISTS workflow_timers (
    id              UUID PRIMARY KEY,
    wf_execution_id UUID NOT NULL REFERENCES workflow_executions(id),
    task_key        TEXT NOT NULL,
    fires_at        TIMESTAMPTZ NOT NULL,
    state           TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|FIRED|CANCELLED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wf_timers_due
    ON workflow_timers (fires_at) WHERE state = 'ACTIVE';
