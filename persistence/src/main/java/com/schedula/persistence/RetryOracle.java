package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Adaptive Retry Oracle — learns optimal retry delays from historical outcomes.
 *
 * When a retry succeeds after delay D, the oracle records a success in the delay
 * bucket that D falls into. When it fails again, a failure. Over time, the bucket
 * with the highest success ratio for each (type, error_class, attempt) triple
 * represents the empirically optimal retry window.
 *
 * The WorkerLoop consults the Oracle before falling back to configured backoff.
 * Minimum sample count prevents premature override with noisy data.
 */
@Repository
public class RetryOracle {

    /** Delay buckets: 100ms, 500ms, 1s, 2s, 4s, 8s, 16s, 32s, 60s, 120s, 300s (ms). */
    static final long[] BUCKETS = {
        100, 500, 1_000, 2_000, 4_000, 8_000,
        16_000, 32_000, 60_000, 120_000, 300_000
    };

    /** Minimum samples before the Oracle overrides configured policy. */
    private static final int MIN_SAMPLES = 5;

    private final JdbcTemplate jdbc;

    public RetryOracle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Convenience: record that this attempt failed after the given delay. */
    public void recordFailure(String jobType, String errorClass, int nextAttempt, long delayMs) {
        record(jobType, errorClass, nextAttempt - 1, delayMs, false);
    }

    /**
     * Record the outcome of a retry attempt.
     * @param succeeded true if this attempt completed successfully
     */
    public void record(String jobType, String errorClass, int attempt,
                       long delayMs, boolean succeeded) {
        long bucket = bucketOf(delayMs);
        jdbc.update("""
                INSERT INTO retry_oracle (job_type, error_class, attempt, delay_bucket_ms,
                    succeeded, failed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_type, error_class, attempt, delay_bucket_ms)
                DO UPDATE SET
                    succeeded = retry_oracle.succeeded + EXCLUDED.succeeded,
                    failed = retry_oracle.failed + EXCLUDED.failed,
                    updated_at = now()
                """, jobType, errorClass, attempt, bucket,
                succeeded ? 1 : 0, succeeded ? 0 : 1);
    }

    /**
     * Query the best-known delay for this (type, error_class, next_attempt).
     * Returns empty when insufficient data exists — caller falls back to configured policy.
     */
    public Optional<Long> suggestDelay(String jobType, String errorClass, int nextAttempt) {
        List<Long> candidates = jdbc.query("""
                SELECT delay_bucket_ms,
                       succeeded::numeric / NULLIF(succeeded + failed, 0) AS success_ratio,
                       succeeded + failed AS total_samples
                FROM retry_oracle
                WHERE job_type = ? AND error_class = ? AND attempt = ?
                  AND succeeded + failed >= ?
                ORDER BY success_ratio DESC, total_samples DESC
                LIMIT 1
                """,
                (rs, i) -> rs.getLong("delay_bucket_ms"),
                jobType, errorClass, nextAttempt, MIN_SAMPLES);
        return candidates.stream().findFirst();
    }

    /** Map an actual or candidate delay to its containing bucket. */
    static long bucketOf(long delayMs) {
        for (long b : BUCKETS) {
            if (delayMs <= b) return b;
        }
        return BUCKETS[BUCKETS.length - 1];
    }
}
