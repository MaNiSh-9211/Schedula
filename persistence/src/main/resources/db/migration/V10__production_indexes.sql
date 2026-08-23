-- Phase 8 hardening: missing indexes, cleanup support

CREATE INDEX IF NOT EXISTS idx_queue_messages_tenant
    ON queue_messages (tenant_id) WHERE status = 'READY';

CREATE INDEX IF NOT EXISTS idx_jobs_webhook_state
    ON jobs (webhook_state, updated_at) WHERE webhook_state = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_workflow_exec_tenant
    ON workflow_executions (tenant_id, status);
