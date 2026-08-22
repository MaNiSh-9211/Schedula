-- Industry-parity batch: named queues, results, webhooks, workflow-triggering
-- schedules, rate-limit bookkeeping.

-- 1) named queues: workers subscribe; submissions route explicitly
ALTER TABLE workers ADD COLUMN IF NOT EXISTS subscribed_queues TEXT[] NOT NULL DEFAULT '{default}';
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS queue_name TEXT NOT NULL DEFAULT 'default';
UPDATE jobs SET queue_name = 'default' WHERE queue_name IS NULL;
CREATE INDEX IF NOT EXISTS idx_jobs_queue ON jobs (queue_name);

-- 2) handler-emitted results, kept per execution
ALTER TABLE job_executions ADD COLUMN IF NOT EXISTS result_json TEXT;

-- 3) completion webhooks (signed, at-least-once with capped retries)
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS webhook_state TEXT NOT NULL DEFAULT 'NONE'; -- NONE|PENDING|DELIVERED|FAILED
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS webhook_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_jobs_webhook_pending
    ON jobs (updated_at) WHERE webhook_state = 'PENDING';

-- 4) schedules may trigger workflows instead of plain jobs
ALTER TABLE job_schedules ADD COLUMN IF NOT EXISTS target_workflow TEXT;
