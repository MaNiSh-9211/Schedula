package com.schedula.engine;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Queue Pressure Trend Predictor — watches queue depth over time and emits a
 * trend metric so operators (or an autoscaler) can react BEFORE the backlog
 * becomes a problem, not after.
 *
 * Keeps a ring buffer of recent samples, computes a linear regression slope,
 * and publishes `schedula_queue_depth_trend` (messages/second) plus
 * `schedula_queue_depth_predicted_5m` (projected depth in 5 minutes).
 */
@Service
public class PressurePredictor {

    private static final Logger log = LoggerFactory.getLogger(PressurePredictor.class);
    private static final int WINDOW_SIZE = 60; // ~5 min at 5s intervals

    private final JdbcTemplate jdbc;
    private final Deque<Sample> window = new ArrayDeque<>(WINDOW_SIZE);
    private final double predictedThreshold;
    private volatile double trendPerSec;
    private volatile double predicted5m;

    record Sample(long timestampMs, long depth) {}

    public PressurePredictor(JdbcTemplate jdbc, MeterRegistry meters,
                             @Value("${schedula.predictor.threshold:5000}") double threshold) {
        this.jdbc = jdbc;
        this.predictedThreshold = threshold;
        meters.gauge("schedula_queue_depth_trend", this, PressurePredictor::getTrend);
        meters.gauge("schedula_queue_depth_predicted_5m", this, PressurePredictor::getPredicted);
    }

    /** Called periodically by the sweeper. Samples current depth and computes trend. */
    public void sample() {
        try {
            Long d = jdbc.queryForObject(
                    "SELECT count(*) FROM queue_messages WHERE status = 'READY'", Long.class);
            if (d == null) return;
            long now = System.currentTimeMillis();
            window.addLast(new Sample(now, d));
            while (window.size() > WINDOW_SIZE) window.removeFirst();
            computeTrend();
        } catch (RuntimeException ignored) { }
    }

    private void computeTrend() {
        if (window.size() < 3) { trendPerSec = 0; predicted5m = 0; return; }
        // simple linear regression: slope = Σ((x-x̄)(y-ȳ)) / Σ((x-x̄)²)
        double n = window.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (Sample s : window) {
            sumX += s.timestampMs;
            sumY += s.depth;
            sumXY += s.timestampMs * s.depth;
            sumX2 += (double) s.timestampMs * s.timestampMs;
        }
        double meanX = sumX / n, meanY = sumY / n;
        double denom = sumX2 - n * meanX * meanX;
        if (Math.abs(denom) < 1e-9) { trendPerSec = 0; predicted5m = meanY; return; }
        double slope = (sumXY - n * meanX * meanY) / denom;
        trendPerSec = slope * 1000.0; // per second
        long latestTs = window.peekLast().timestampMs;
        double elapsedSec = (System.currentTimeMillis() - latestTs) / 1000.0 + 300.0; // 5 min ahead
        predicted5m = Math.max(0, meanY + trendPerSec * elapsedSec);
        if (predicted5m > predictedThreshold) {
            log.warn("PRESSURE PREDICTION: queue depth trending toward {:.0f} in 5 minutes " +
                     "(threshold={})", predicted5m, predictedThreshold);
        }
    }

    private double getTrend() { return trendPerSec; }
    private double getPredicted() { return predicted5m; }
}
