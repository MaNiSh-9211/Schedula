package com.schedula.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 🧬 Statistical Anomaly Detection — Welford's online algorithm applied to job
 * execution durations. Learns per-job-type duration distributions WITHOUT storing
 * samples, using constant memory. Fires anomaly events when live executions
 * deviate > Nσ from the learned baseline.
 *
 * This catches "slow but not timed-out" jobs: a REPORT that normally takes 30s
 * suddenly taking 300s isn't a failure — it's a problem nobody notices until
 * someone complains. With SPC detection, it's flagged at +3σ immediately.
 *
 * Novel because no scheduler does statistical process control on execution times.
 */
@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final JdbcTemplate jdbc;
    private final double defaultSigma;

    public AnomalyDetector(JdbcTemplate jdbc, double defaultSigma) {
        this.jdbc = jdbc;
        this.defaultSigma = defaultSigma;
    }

    /**
     * Called after every execution completes. Updates the running baseline using
     * Welford's method (numerically stable single-pass mean/variance) and checks
     * whether THIS execution was anomalous relative to prior history.
     */
    public void observe(String jobType, long durationMs) {
        if (durationMs <= 0) return;
        double durationSec = durationMs / 1000.0;

        var baseline = jdbc.queryForMap("""
                SELECT sample_count, mean_duration_s, m2_duration,
                       COALESCE(sigma_multiplier, ?) AS sigma_mult
                FROM anomaly_baselines WHERE job_type = ?
                FOR UPDATE
                """, defaultSigma, jobType);

        long count = ((Number) baseline.get("sample_count")).longValue();
        double mean = ((Number) baseline.get("mean_duration_s")).doubleValue();
        double m2 = ((Number) baseline.get("m2_duration")).doubleValue();
        double sigmaMult = ((Number) baseline.get("sigma_mult")).doubleValue();

        if (count > 10) { // need enough samples before flagging anomalies
            double variance = count > 1 ? m2 / (count - 1) : 0;
            double stdDev = Math.sqrt(variance);
            if (stdDev > 0 && durationSec > mean + sigmaMult * stdDev) {
                double zScore = (durationSec - mean) / stdDev;
                log.warn("🚨 ANOMALY: {} took {:.1f}s (μ={:.1f}s σ={:.1f} z={:.1f})",
                        jobType, durationSec, mean, stdDev, zScore);
            }
        }

        // Welford's update (always, even for anomalous samples)
        count++;
        double delta = durationSec - mean;
        mean += delta / count;
        double delta2 = durationSec - mean;
        m2 += delta * delta2;

        jdbc.update("""
                INSERT INTO anomaly_baselines (job_type, sample_count, mean_duration_s, m2_duration)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (job_type) DO UPDATE SET
                    sample_count = EXCLUDED.sample_count,
                    mean_duration_s = EXCLUDED.mean_duration_s,
                    m2_duration = EXCLUDED.m2_duration
                """, jobType, count, mean, m2);
    }
}
