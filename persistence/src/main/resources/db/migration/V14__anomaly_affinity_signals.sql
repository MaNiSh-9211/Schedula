-- Statistical Anomaly Detection baselines (Welford's online algorithm)
CREATE TABLE IF NOT EXISTS anomaly_baselines (
    job_type        TEXT PRIMARY KEY,
    sample_count    BIGINT NOT NULL DEFAULT 0,
    mean_duration_s DOUBLE PRECISION NOT NULL DEFAULT 0,
    m2_duration     DOUBLE PRECISION NOT NULL DEFAULT 0, -- sum of squared deviations
    sigma_multiplier DOUBLE PRECISION NOT NULL DEFAULT 3.0
);

-- Worker affinity: learns which workers excel at which job types
CREATE TABLE IF NOT EXISTS worker_affinity (
    worker_id      UUID NOT NULL REFERENCES workers(id),
    job_type       TEXT NOT NULL,
    total_runs     BIGINT NOT NULL DEFAULT 0,
    total_success  BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (worker_id, job_type)
);

-- Signal deadlines
ALTER TABLE workflow_task_executions ADD COLUMN IF NOT EXISTS signal_deadline TIMESTAMPTZ;
