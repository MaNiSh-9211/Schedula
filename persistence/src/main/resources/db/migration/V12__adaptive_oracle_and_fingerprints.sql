-- Adaptive Retry Oracle: learns optimal retry delays from historical outcomes.
-- Replaces blind exponential backoff with evidence-based delay selection.
CREATE TABLE IF NOT EXISTS retry_oracle (
    job_type        TEXT NOT NULL,
    error_class     TEXT NOT NULL,
    attempt         INT NOT NULL,
    delay_bucket_ms BIGINT NOT NULL,
    succeeded       INT NOT NULL DEFAULT 0,
    failed          INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_type, error_class, attempt, delay_bucket_ms)
);

-- Job Fingerprint Cards: auto-computed per-type execution profile
CREATE OR REPLACE VIEW job_fingerprints AS
SELECT
    j.job_type,
    COUNT(*)                                                              AS total_24h,
    COUNT(*) FILTER (WHERE e.status = 'COMPLETED')                        AS completed_24h,
    COUNT(*) FILTER (WHERE e.status IN ('FAILED','ABANDONED'))            AS failed_24h,
    ROUND(
        COUNT(*) FILTER (WHERE e.status = 'COMPLETED')::numeric
        / NULLIF(COUNT(*), 0) * 100, 1)                                   AS success_rate_pct,
    percentile_cont(0.5) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (e.finished_at - e.started_at))
    )                                                                     AS p50_duration_s,
    percentile_cont(0.95) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (e.finished_at - e.started_at))
    )                                                                     AS p95_duration_s,
    percentile_cont(0.99) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (e.finished_at - e.started_at))
    )                                                                     AS p99_duration_s,
    AVG(j.attempts_made)                                                  AS avg_attempts,
    MODE() WITHIN GROUP (ORDER BY e.error_class)                          AS most_common_error
FROM job_executions e
JOIN jobs j ON j.id = e.job_id
WHERE e.created_at > now() - interval '24 hours'
  AND e.status IN ('COMPLETED','FAILED','ABANDONED')
GROUP BY j.job_type;
