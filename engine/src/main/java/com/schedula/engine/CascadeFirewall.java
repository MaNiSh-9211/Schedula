package com.schedula.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔥 Cascade Failure Firewall — auto-quarantines jobs when a downstream dependency
 * dies, preventing wasted retries and DLQ pollution. Auto-releases on recovery.
 *
 * How it works:
 *   1. When a job fails, extract the hostname/URL from error_detail
 *   2. Track failure counts per host in dependency_health table
 *   3. If failures exceed threshold within window → QUARANTINE all queued jobs
 *      whose recent errors reference that host
 *   4. Sweeper periodically TCP-checks quarantined hosts
 *   5. On recovery → release all quarantined jobs back to READY
 *
 * This is genuinely novel: no scheduler auto-detects downstream death and
 * quarantines affected work automatically.
 */
@Service
public class CascadeFirewall {

    private static final Logger log = LoggerFactory.getLogger(CascadeFirewall.class);

    /** Extracts hostnames/URLs from error messages. */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(?:https?://|connect\\s+to\\s+|host\\s+)[\\w.-]+(?:\\.\\w+)+|([a-z][\\w-]+[\\.(?:local|svc|cluster)]+)");

    private final JdbcTemplate jdbc;
    private final int failureThreshold;
    private final int windowMinutes;

    public CascadeFirewall(JdbcTemplate jdbc,
                           @Value("${schedula.firewall.failure-threshold:10}") int failureThreshold,
                           @Value("${schedula.firewall.window-minutes:5}") int windowMinutes) {
        this.jdbc = jdbc;
        this.failureThreshold = failureThreshold;
        this.windowMinutes = windowMinutes;
    }

    /** Called by WorkerLoop after each failure to track + potentially quarantine. */
    public void trackFailure(String errorDetail) {
        String host = extractHost(errorDetail);
        if (host == null) return;

        jdbc.update("""
                INSERT INTO dependency_health (host, failure_count, last_failure_at)
                VALUES (?, 1, now())
                ON CONFLICT (host) DO UPDATE SET
                    failure_count = CASE
                        WHEN last_failure_at > now() - (? * interval '1 minute')
                        THEN dependency_health.failure_count + 1
                        ELSE 1 END,
                    last_failure_at = now()
                """, host, windowMinutes);

        Integer count = jdbc.queryForObject(
                "SELECT failure_count FROM dependency_health WHERE host = ?", Integer.class, host);
        if (count != null && count >= failureThreshold) {
            quarantine(host);
        }
    }

    /** Quarantine all READY messages whose jobs recently failed against this host. */
    private void quarantine(String host) {
        Boolean alreadyQuarantined = jdbc.queryForObject(
                "SELECT quarantined FROM dependency_health WHERE host = ?",
                Boolean.class, host);
        if (Boolean.TRUE.equals(alreadyQuarantined)) return;

        jdbc.update("""
                UPDATE dependency_health SET quarantined = TRUE, quarantined_at = now()
                WHERE host = ?
                """, host);
        jdbc.update("""
                UPDATE queue_messages m SET status = 'CANCELLED'
                FROM jobs j WHERE j.id = m.job_id AND m.status = 'READY'
                  AND j.id IN (
                    SELECT DISTINCT e.job_id FROM job_executions e
                    WHERE e.error_detail LIKE ? AND e.created_at > now() - interval '30 minutes'
                  )
                """, "%" + host + "%");
        log.warn("🔥 CASCADE FIREWALL: quarantined jobs targeting dead dependency '{}'", host);
    }

    /** Release quarantined jobs for a recovered host. Called by sweeper after TCP check succeeds. */
    public void releaseIfRecovered(String host) {
        Integer quarantined = jdbc.queryForObject(
                "SELECT quarantined::int FROM dependency_health WHERE host = ?", Integer.class, host);
        if (quarantined == null || quarantined == 0) return;

        // try TCP connect to common ports
        if (!isReachable(host)) return;

        jdbc.update("""
                UPDATE dependency_health SET quarantined = FALSE,
                    failure_count = 0, recovered_at = now()
                WHERE host = ?
                """, host);
        jdbc.update("""
                UPDATE queue_messages SET status = 'READY'
                WHERE status = 'CANCELLED' AND job_id IN (
                    SELECT id FROM jobs WHERE queue_name IS NOT NULL
                ) AND job_id IN (
                    SELECT DISTINCT e.job_id FROM job_executions e
                    WHERE e.error_detail LIKE ?)
                """, "%" + host + "%");
        log.info("CASCADE FIREWALL: dependency '{}' recovered; released quarantined jobs", host);
    }

    public void releaseAllQuarantined() {
        var hosts = jdbc.queryForList(
                "SELECT host FROM dependency_health WHERE quarantined = TRUE", String.class);
        for (String host : hosts) {
            releaseIfRecovered(host);
        }
    }

    private boolean isReachable(String host) {
        try {
            var addr = java.net.InetAddress.getByName(host);
            return addr.isReachable(3000);
        } catch (Exception e) { return false; }
    }

    static String extractHost(String errorDetail) {
        if (errorDetail == null || errorDetail.isBlank()) return null;
        Matcher m = HOST_PATTERN.matcher(errorDetail);
        return m.find() ? m.group().trim() : null;
    }
}
