package com.schedula.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Admission (§27) and dispatch (§26) limits. Enforcement points:
 * - admission: API submit rejects with 429 when a tenant's non-terminal backlog
 *   exceeds max_pending_jobs — backpressure is explicit, never silent queueing;
 * - dispatch: claim excludes job types / tenants already at their concurrency cap.
 * Counts are approximate under races by design; caps are load-shaping, not invariants.
 */
@Repository
public class QuotaStore {

    public static class TenantOverQuota extends RuntimeException {
        public final long limit;
        public final long current;

        public TenantOverQuota(UUID tenantId, long current, long limit) {
            super("tenant " + tenantId + " pending jobs " + current + " exceeds quota " + limit);
            this.current = current;
            this.limit = limit;
        }
    }

    private final JdbcTemplate jdbc;
    private final Counter throttled;

    public QuotaStore(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.throttled = Counter.builder("schedula_tenant_throttled_total")
                .description("submissions rejected by tenant backlog quota")
                .register(meters);
    }

    /** @throws TenantOverQuota when the caller should receive 429. */
    public void admissionCheck(UUID tenantId) {
        checkBacklogQuota(tenantId);
        checkRateLimit(tenantId);
    }

    private void checkBacklogQuota(UUID tenantId) {
        List<Long> limits = jdbc.queryForList(
                "SELECT max_pending_jobs FROM tenant_quotas WHERE tenant_id = ?",
                Long.class, tenantId);
        if (limits.isEmpty()) {
            return; // no quota configured => unconstrained
        }
        long limit = limits.get(0);
        List<Long> pendingList = jdbc.queryForList("""
                        SELECT count(*) FROM jobs
                        WHERE tenant_id = ? AND status IN
                          ('SCHEDULED', 'QUEUED', 'RETRY_WAIT', 'DISPATCHED', 'RUNNING')
                        """, Long.class, tenantId);
        long pending = pendingList.isEmpty() ? 0 : pendingList.get(0);
        if (pending >= limit) {
            throttled.increment();
            throw new TenantOverQuota(tenantId, pending, limit);
        }
    }

    /**
     * Submission-rate limit (§28): sliding 60s window over created_at. Single-node
     * accurate via the DB clock; multi-node exactness is bounded by poll timing —
     * acceptable for load-shaping (documented in MULTI-TENANCY.md).
     */
    private void checkRateLimit(UUID tenantId) {
        List<Long> limits = jdbc.queryForList(
                "SELECT max_submit_per_min FROM tenant_quotas WHERE tenant_id = ?",
                Long.class, tenantId);
        if (limits.isEmpty()) return;
        long perMinute = limits.get(0);
        if (perMinute >= Long.MAX_VALUE / 2) return;
        List<Long> recent = jdbc.queryForList("""
                SELECT count(*) FROM jobs
                WHERE tenant_id = ? AND created_at > now() - interval '60 seconds'
                """, Long.class, tenantId);
        if (!recent.isEmpty() && recent.get(0) >= perMinute) {
            throttled.increment();
            throw new TenantOverQuota(tenantId, recent.get(0), perMinute);
        }
    }

    /** Job types that HAVE a concurrency limit configured (enforced during claiming). */
    public List<String> typesWithLimits() {
        return jdbc.queryForList("SELECT job_type FROM job_type_limits", String.class);
    }

    /** Tenants that HAVE quotas configured (enforced during claiming). */
    public List<UUID> tenantsWithQuotas() {
        List<Object[]> rows = jdbc.query(
                "SELECT tenant_id FROM tenant_quotas",
                (rs, i) -> new Object[]{rs.getObject("tenant_id")});
        return rows.stream()
                .map(r -> r[0] instanceof UUID u ? u : UUID.fromString(String.valueOf(r[0])))
                .toList();
    }

    /** Free capacity for a worker: configured capacity minus its RUNNING jobs' requirements. */
    public record FreeResources(int cpu, long memMb) {
    }

    public FreeResources freeResources(UUID workerId) {
        return jdbc.query("""
                        SELECT w.cpu_capacity - COALESCE(SUM(j.required_cpu), 0) AS free_cpu,
                               w.mem_capacity_mb - COALESCE(SUM(j.required_mem_mb), 0) AS free_mem
                        FROM workers w
                        LEFT JOIN job_executions e ON e.worker_id = w.id AND e.status = 'RUNNING'
                        LEFT JOIN jobs j ON j.id = e.job_id
                        WHERE w.id = ?
                        GROUP BY w.cpu_capacity, w.mem_capacity_mb
                        """,
                (rs, i) -> new FreeResources(rs.getInt("free_cpu"), rs.getLong("free_mem")),
                workerId).stream().findFirst().orElse(new FreeResources(0, 0));
    }

    public void setTypeLimit(String jobType, int maxConcurrent) {
        jdbc.update("""
                        INSERT INTO job_type_limits (job_type, max_concurrent) VALUES (?, ?)
                        ON CONFLICT (job_type) DO UPDATE SET max_concurrent = EXCLUDED.max_concurrent
                        """, jobType, maxConcurrent);
    }

    public void setTenantQuotas(UUID tenantId, Long maxPendingJobs, Integer maxConcurrent) {
        jdbc.update("""
                        INSERT INTO tenant_quotas (tenant_id, max_pending_jobs, max_concurrent_executions)
                        VALUES (?, COALESCE(?, 9223372036854775807), COALESCE(?, 2147483647))
                        ON CONFLICT (tenant_id) DO UPDATE SET
                            max_pending_jobs = COALESCE(EXCLUDED.max_pending_jobs, tenant_quotas.max_pending_jobs),
                            max_concurrent_executions = COALESCE(EXCLUDED.max_concurrent_executions, tenant_quotas.max_concurrent_executions),
                            updated_at = now()
                        """, tenantId, maxPendingJobs, maxConcurrent);
    }

    public void clearTenantQuota(UUID tenantId) {
        jdbc.update("DELETE FROM tenant_quotas WHERE tenant_id = ?", tenantId);
    }

    public void clearTypeLimit(String jobType) {
        jdbc.update("DELETE FROM job_type_limits WHERE job_type = ?", jobType);
    }
}
