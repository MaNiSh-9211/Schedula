-- Predictive Health Scoring: learn from history to predict future success
CREATE TABLE IF NOT EXISTS health_profiles (
    job_type           TEXT NOT NULL,
    hour_of_day        INT NOT NULL,
    total_submissions  BIGINT NOT NULL DEFAULT 0,
    total_completions  BIGINT NOT NULL DEFAULT 0,
    total_failures     BIGINT NOT NULL DEFAULT 0,
    avg_duration_s     DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_type, hour_of_day)
);

CREATE TABLE IF NOT EXISTS execution_timeline (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID NOT NULL,
    phase           TEXT NOT NULL,     -- SUBMIT|ENQUEUE|CLAIM|START|RESULT|ACK|DLQ|CANCEL|RETRY|EXPIRE
    actor           TEXT NOT NULL,
    decision        TEXT NOT NULL,     -- what the system decided and WHY
    context         JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_timeline_job ON execution_timeline (job_id, occurred_at);
