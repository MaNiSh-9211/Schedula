package com.schedula.queue;

import com.schedula.common.events.EventTypes;
import com.schedula.common.model.QueueMessage;
import com.schedula.persistence.EventStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresQueue {

    public static final String DEFAULT_QUEUE = "default";

    private static final RowMapper<QueueMessage> MESSAGE = (rs, i) -> new QueueMessage(
            MappersQ.uuid(rs, "id"),
            rs.getString("queue_name"),
            MappersQ.uuid(rs, "job_execution_id"),
            MappersQ.uuid(rs, "job_id"),
            MappersQ.uuid(rs, "tenant_id"),
            rs.getInt("priority"),
            rs.getLong("enqueue_seq"),
            QueueMessage.Status.valueOf(rs.getString("status")),
            MappersQ.instant(rs, "available_at"),
            MappersQ.uuid(rs, "claim_owner"),
            MappersQ.instant(rs, "claim_expires_at"),
            rs.getInt("deliver_count"),
            MappersQ.instant(rs, "enqueued_at"));

    private final JdbcTemplate jdbc;
    private final EventStore events;
    private final Counter claimsTotal;
    private final Counter expiredRequeuedTotal;
    private final Counter deadletteredTotal;

    public PostgresQueue(JdbcTemplate jdbc, EventStore events, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.events = events;
        this.claimsTotal = Counter.builder("schedula_queue_claims_total")
                .description("queue messages claimed by workers").register(meters);
        this.expiredRequeuedTotal = Counter.builder("schedula_queue_requeued_expired_total")
                .description("claims reclaimed after visibility timeout").register(meters);
        this.deadletteredTotal = Counter.builder("schedula_queue_deadlettered_total")
                .description("messages moved to DLQ").register(meters);
    }

    /** Enqueue must run inside the same transaction as the state change that warrants it. */
    public void enqueue(UUID jobId, String queueName, UUID tenantId, UUID executionId, int priority, Instant availableAt) {
        jdbc.update("""
                        INSERT INTO queue_messages (id, queue_name, job_execution_id, job_id, tenant_id,
                            priority, status, available_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'READY', ?)
                        """,
                com.schedula.common.ids.UuidV7.generate(), queueName == null || queueName.isBlank() ? DEFAULT_QUEUE : queueName, executionId, jobId,
                tenantId, priority, Timestamp.from(availableAt));
    }

    public record ClaimFilter(java.util.List<String> queues,
                              java.util.List<String> excludeJobTypes,
                              java.util.List<UUID> excludeTenants,
                              int minFreeCpu, long minFreeMemMb) {
        public static ClaimFilter of(java.util.List<String> queues) {
            return new ClaimFilter(queues, java.util.List.of(), java.util.List.of(), 0, 0);
        }
        public static ClaimFilter unrestricted() {
            return new ClaimFilter(java.util.List.of("default"), java.util.List.of(), java.util.List.of(), 0, 0);
        }
    }

    public List<QueueMessage> claim(UUID workerId, int batch, long visibilityTimeoutMs,
                                    ClaimFilter filter) {
        boolean unrestricted = filter.excludeJobTypes().isEmpty()
                && filter.excludeTenants().isEmpty()
                && filter.minFreeCpu() <= 0 && filter.minFreeMemMb() <= 0;
        if (unrestricted) {
            return claimUnrestricted(workerId, batch, visibilityTimeoutMs, filter);
        }
        return claimConstrained(workerId, batch, visibilityTimeoutMs, filter);
    }

    private List<QueueMessage> claimUnrestricted(UUID workerId, int batch, long visibilityTimeoutMs) {
        return claimUnrestricted(workerId, batch, visibilityTimeoutMs, ClaimFilter.unrestricted());
    }

    private List<QueueMessage> claimUnrestricted(UUID workerId, int batch, long visibilityTimeoutMs,
                                                 ClaimFilter filter) {
        List<QueueMessage> claimed = jdbc.query("""
                UPDATE queue_messages m
                SET status = 'CLAIMED', claim_owner = ?, claim_expires_at = now() + (? * interval '1 millisecond'),
                    deliver_count = deliver_count + 1
                WHERE m.id IN (
                    SELECT m2.id FROM queue_messages m2
                    LEFT JOIN jobs j ON j.id = m2.job_id
                    WHERE m2.queue_name IN (SELECT unnest(subscribed_queues) FROM workers WHERE id = ?) AND m2.status = 'READY' AND m2.available_at <= now()
                      AND COALESCE(j.required_capabilities, '{}'::text[])
                            <@ (SELECT capabilities FROM workers WHERE id = ?)
                      AND COALESCE(j.required_cpu, 0) <= 0
                      AND COALESCE(j.required_mem_mb, 0) <= 0
                      AND EXISTS (SELECT 1 FROM workers w
                                  WHERE w.id = ? AND w.status = 'HEALTHY')
                    ORDER BY m2.priority DESC, m2.enqueue_seq
                    LIMIT ?
                    FOR UPDATE OF m2 SKIP LOCKED
                )
                RETURNING m.*
                """, MESSAGE, workerId, (double) visibilityTimeoutMs,
                workerId, workerId,
                workerId, batch);
        claimsTotal.increment(claimed.size());
        return claimed;
    }

    /**
     * Filtered path: candidates are locked first (SKIP LOCKED keeps other workers moving),
     * then admitted one-by-one in WEIGHTED ROUND ROBIN across tenants (long-run service
     * share proportional to tenants.weight, small tenants never starve behind large ones),
     * against per-type/tenant concurrency caps (counting committed RUNNING executions plus
     * messages accepted earlier in this same batch) and this worker's free resource floor.
     * Candidates rejected here stay READY after the transaction releases their locks.
     */
    private List<QueueMessage> claimConstrained(UUID workerId, int batch, long visibilityTimeoutMs,
                                                ClaimFilter filter) {
        List<QueueMessage> candidates = jdbc.query("""
                SELECT m2.* FROM queue_messages m2
                LEFT JOIN jobs j ON j.id = m2.job_id
                WHERE m2.queue_name IN (SELECT unnest(subscribed_queues) FROM workers WHERE id = ?) AND m2.status = 'READY' AND m2.available_at <= now()
                  AND COALESCE(j.required_capabilities, '{}'::text[])
                        <@ (SELECT capabilities FROM workers WHERE id = ?)
                  AND EXISTS (SELECT 1 FROM workers w WHERE w.id = ? AND w.status = 'HEALTHY')
                ORDER BY m2.priority DESC, m2.enqueue_seq
                LIMIT ?
                FOR UPDATE OF m2 SKIP LOCKED
                """, MESSAGE, workerId, workerId, workerId, batch * 4);

        // group per tenant, preserving priority order within each tenant
        var byTenant = new java.util.LinkedHashMap<UUID, java.util.ArrayDeque<QueueMessage>>();
        for (QueueMessage m : candidates) {
            byTenant.computeIfAbsent(m.tenantId(), k -> new java.util.ArrayDeque<>()).add(m);
        }
        var weights = tenantWeights(byTenant.keySet());
        var cycle = buildWeightedCycle(byTenant.keySet(), weights);

        List<QueueMessage> accepted = new java.util.ArrayList<>(batch);
        int freeCpu = filter.minFreeCpu();
        long freeMem = filter.minFreeMemMb();
        var typeCounts = new java.util.HashMap<String, Integer>();
        var tenantCounts = new java.util.HashMap<UUID, Integer>();
        int cursor = 0;

        while (accepted.size() < batch && !byTenant.isEmpty() && !cycle.isEmpty()) {
            if (cursor >= cycle.size()) cursor = 0;
            UUID tenant = cycle.get(cursor);
            var queue = byTenant.get(tenant);
            if (queue == null || queue.isEmpty()) {
                byTenant.remove(tenant);
                cycle.remove(cursor);
                continue;
            }
            QueueMessage candidate = queue.poll();

            String type = jobTypeOf(candidate);
            String typeKey = type == null ? "" : type;
            int runningForType = type.isEmpty() ? 0 : runningCountByType(type);
            int runningForTenant = runningCountByTenant(tenant);

            var need = type.isEmpty() ? new Reqs(0, 0) : requirementsOf(candidate.jobId());

            boolean overCap = overTypeCap(typeKey, runningForType + typeCounts.getOrDefault(typeKey, 0))
                    || overTenantCap(tenant, runningForTenant + tenantCounts.getOrDefault(tenant, 0));
            boolean overResources = need.cpu() > freeCpu || need.memMb() > freeMem;

            if (overCap) {
                // whole type/tenant is saturated this instant; drop its remaining candidates
                byTenant.remove(tenant);
                cycle.removeAll(java.util.Collections.nCopies(1, tenant));
                continue;
            }
            if (overResources) {
                // this worker lacks headroom for THIS message; try its next one later round
                queue.addFirst(candidate);
                cursor++;
                continue;
            }

            List<QueueMessage> one = jdbc.query("""
                    UPDATE queue_messages SET status = 'CLAIMED', claim_owner = ?,
                        claim_expires_at = now() + (? * interval '1 millisecond'),
                        deliver_count = deliver_count + 1
                    WHERE id = ? AND status = 'READY'
                    RETURNING *
                    """, MESSAGE, workerId, (double) visibilityTimeoutMs, candidate.id());
            if (one.isEmpty()) continue; // lost a race; candidate gone

            accepted.add(one.get(0));
            claimsTotal.increment();
            typeCounts.merge(typeKey, 1, Integer::sum);
            tenantCounts.merge(tenant, 1, Integer::sum);
            freeCpu -= need.cpu();
            freeMem -= need.memMb();
            cursor++;
        }
        return accepted;
    }

    private java.util.Map<UUID, Integer> tenantWeights(java.util.Set<UUID> tenants) {
        var weights = new java.util.HashMap<UUID, Integer>();
        for (UUID id : tenants) {
            List<Integer> w = jdbc.query("SELECT weight FROM tenants WHERE id = ?",
                    (rs, i) -> rs.getInt("weight"), id);
            weights.put(id, Math.max(1, w.isEmpty() ? 1 : w.get(0)));
        }
        return weights;
    }

    /** Weighted round-robin visit order: a tenant with weight w appears w times per cycle. */
    private static java.util.List<UUID> buildWeightedCycle(java.util.Set<UUID> tenants,
                                                           java.util.Map<UUID, Integer> weights) {
        int max = 1;
        for (int w : weights.values()) max = Math.max(max, w);
        var cycle = new java.util.ArrayList<UUID>();
        for (int phase = max; phase >= 1; phase--) {
            for (UUID t : tenants) {
                if (weights.getOrDefault(t, 1) >= phase) cycle.add(t);
            }
        }
        return cycle;
    }

    private record Reqs(int cpu, long memMb) {
    }

    private String jobTypeOf(QueueMessage m) {
        List<String> t = jdbc.query("SELECT job_type FROM jobs WHERE id = ?",
                (rs, i) -> rs.getString("job_type"), m.jobId());
        return t.isEmpty() ? "" : t.get(0);
    }

    private Reqs requirementsOf(UUID jobId) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT required_cpu, required_mem_mb FROM jobs WHERE id = ?", jobId);
            return new Reqs(
                    ((Number) row.get("required_cpu")).intValue(),
                    ((Number) row.get("required_mem_mb")).longValue());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return new Reqs(0, 0);
        }
    }

    private int runningCountByType(String type) {
        Integer c = jdbc.queryForObject("""
                SELECT count(*) FROM job_executions e JOIN jobs j ON j.id = e.job_id
                WHERE e.status = 'RUNNING' AND j.job_type = ?
                """, Integer.class, type);
        return c == null ? 0 : c;
    }

    private int runningCountByTenant(UUID tenantId) {
        Integer c = jdbc.queryForObject("""
                SELECT count(*) FROM job_executions e WHERE e.status = 'RUNNING'
                  AND e.job_id IN (SELECT id FROM jobs WHERE tenant_id = ?)
                """, Integer.class, tenantId);
        return c == null ? 0 : c;
    }

    private boolean overTypeCap(String type, int projected) {
        List<Integer> caps = jdbc.query(
                "SELECT max_concurrent FROM job_type_limits WHERE job_type = ?",
                (rs, i) -> rs.getInt("max_concurrent"), type);
        return !caps.isEmpty() && projected >= caps.get(0);
    }

    private boolean overTenantCap(UUID tenantId, int projected) {
        List<Integer> caps = jdbc.query(
                "SELECT max_concurrent_executions FROM tenant_quotas WHERE tenant_id = ?",
                (rs, i) -> rs.getInt("max_concurrent_executions"), tenantId);
        return !caps.isEmpty() && projected >= caps.get(0);
    }

    public List<QueueMessage> claim(UUID workerId, int batch, long visibilityTimeoutMs) {
        return claim(workerId, batch, visibilityTimeoutMs, ClaimFilter.unrestricted());
    }

    public boolean ack(UUID messageId, UUID claimOwner) {
        return jdbc.update("""
                        UPDATE queue_messages SET status = 'DONE'
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, messageId, claimOwner) == 1;
    }

    /** Extend an active claim (visibility timeout renewal); false if ownership was lost. */
    public boolean extendClaim(UUID messageId, UUID claimOwner, long extendMs) {
        return jdbc.update("""
                        UPDATE queue_messages SET claim_expires_at = now() + (? * interval '1 millisecond')
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, (double) extendMs, messageId, claimOwner) == 1;
    }

    public boolean nack(UUID messageId, UUID claimOwner, long delayMs) {
        return jdbc.update("""
                        UPDATE queue_messages SET status = 'READY', claim_owner = NULL,
                            claim_expires_at = NULL, available_at = now() + (? * interval '1 millisecond')
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, (double) delayMs, messageId, claimOwner) == 1;
    }

    public boolean deadletter(UUID messageId, UUID claimOwner, String reason) {
        boolean ok = jdbc.update("""
                        UPDATE queue_messages SET status = 'DEADLETTERED', claim_owner = NULL,
                            claim_expires_at = NULL
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, messageId, claimOwner) == 1;
        if (ok) {
            deadletteredTotal.increment();
        }
        return ok;
    }

    public record Reclaimed(QueueMessage message, boolean deadlettered) {
    }

    /**
     * Visibility-timeout sweep: expired claims either redeliver or, once maxDeliveries is
     * reached, go to the DLQ. Returns affected messages so the caller can reconcile job state.
     */
    public List<Reclaimed> reclaimExpired(int maxDeliveries) {
        List<QueueMessage> dead = jdbc.query("""
                        UPDATE queue_messages SET status = 'DEADLETTERED', claim_owner = NULL,
                            claim_expires_at = NULL
                        WHERE status = 'CLAIMED' AND claim_expires_at < now() AND deliver_count >= ?
                        RETURNING *
                        """, MESSAGE, maxDeliveries);
        List<QueueMessage> requeued = jdbc.query("""
                        UPDATE queue_messages SET status = 'READY', claim_owner = NULL,
                            claim_expires_at = NULL, available_at = now()
                        WHERE status = 'CLAIMED' AND claim_expires_at < now() AND deliver_count < ?
                        RETURNING *
                        """, MESSAGE, maxDeliveries);
        for (QueueMessage m : dead) {
            deadletteredTotal.increment();
            events.append(m.jobId(), m.jobExecutionId(), EventTypes.MESSAGE_DEADLETTERED,
                    "sweeper", "max deliveries exceeded (" + m.deliverCount() + ")", null);
        }
        for (QueueMessage m : requeued) {
            expiredRequeuedTotal.increment();
            events.append(m.jobId(), m.jobExecutionId(), EventTypes.CLAIM_EXPIRED_REQUEUED,
                    "sweeper", "delivery " + m.deliverCount(), null);
        }
        List<Reclaimed> out = new java.util.ArrayList<>(dead.size() + requeued.size());
        dead.forEach(m -> out.add(new Reclaimed(m, true)));
        requeued.forEach(m -> out.add(new Reclaimed(m, false)));
        return out;
    }

    /** Cancel path for queued jobs; runs in the caller's transaction next to the job CAS. */
    public int cancelReadyForJob(UUID jobId) {
        return jdbc.update("""
                        UPDATE queue_messages SET status = 'CANCELLED'
                        WHERE job_id = ? AND status = 'READY'
                        """, jobId);
    }

    /** Dispatcher-side race resolution: job was cancelled/paused after we claimed its message. */
    public boolean cancelClaimed(UUID messageId, UUID claimOwner) {
        return jdbc.update("""
                        UPDATE queue_messages SET status = 'CANCELLED', claim_owner = NULL,
                            claim_expires_at = NULL
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, messageId, claimOwner) == 1;
    }

    /** Links the execution created at dispatch time so recovery can find it later. */
    public void attachExecution(UUID messageId, UUID executionId) {
        jdbc.update("UPDATE queue_messages SET job_execution_id = ? WHERE id = ?",
                executionId, messageId);
    }

    public long depth(String queueName) {
        Long d = jdbc.queryForObject(
                "SELECT count(*) FROM queue_messages WHERE queue_name = ? AND status = 'READY'",
                Long.class, queueName);
        return d == null ? 0 : d;
    }

    // --- DLQ administration -------------------------------------------------------

    public record DeadLetter(UUID messageId, UUID jobId, UUID tenantId, String jobType,
                             int deliverCount, Instant enqueuedAt, Instant resolvedAt,
                             String errorClass, String errorDetail) {
    }

    public java.util.List<DeadLetter> listDeadLetters(String jobTypeOrNull, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id AS message_id, m.job_id, m.tenant_id, j.job_type,
                       m.deliver_count, m.enqueued_at, m.resolved_at,
                       e.error_class, e.error_detail
                FROM queue_messages m
                JOIN jobs j ON j.id = m.job_id
                LEFT JOIN job_executions e ON e.id = (
                    SELECT id FROM job_executions WHERE job_id = m.job_id
                    ORDER BY attempt_no DESC LIMIT 1)
                WHERE m.status = 'DEADLETTERED'
                """);
        var args = new java.util.ArrayList<Object>();
        if (jobTypeOrNull != null && !jobTypeOrNull.isBlank()) {
            sql.append(" AND j.job_type = ?");
            args.add(jobTypeOrNull);
        }
        sql.append(" ORDER BY m.enqueued_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> new DeadLetter(
                MappersQ.uuid(rs, "message_id"),
                MappersQ.uuid(rs, "job_id"),
                MappersQ.uuid(rs, "tenant_id"),
                rs.getString("job_type"),
                rs.getInt("deliver_count"),
                MappersQ.instant(rs, "enqueued_at"),
                MappersQ.instant(rs, "resolved_at"),
                rs.getString("error_class"),
                rs.getString("error_detail")), args.toArray());
    }

    public boolean resolveDeadLetter(java.util.UUID messageId) {
        return jdbc.update("""
                        UPDATE queue_messages SET resolved_at = now()
                        WHERE id = ? AND status = 'DEADLETTERED' AND resolved_at IS NULL
                        """, messageId) == 1;
    }

    public DeadLetter findDeadLetter(java.util.UUID messageId) {
        return listDeadLetters(null, 200, 0).stream()
                .filter(d -> d.messageId().equals(messageId))
                .findFirst().orElse(null);
    }

    public int deleteDeadLetter(java.util.UUID messageId) {
        return jdbc.update("DELETE FROM queue_messages WHERE id = ? AND status = 'DEADLETTERED'",
                messageId);
    }
}










