-- Phase 6b: workflow signals (external events delivered into running executions)

CREATE TABLE IF NOT EXISTS workflow_signals (
    id              UUID PRIMARY KEY,
    wf_execution_id UUID NOT NULL REFERENCES workflow_executions(id),
    signal_name     TEXT NOT NULL,
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    consumed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wf_signals_pending
    ON workflow_signals (wf_execution_id, signal_name) WHERE consumed = FALSE;
