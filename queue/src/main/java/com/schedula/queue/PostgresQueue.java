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
    public void enqueue(UUID jobId, UUID tenantId, UUID executionId, int priority, Instant availableAt) {
        jdbc.update("""
                        INSERT INTO queue_messages (id, queue_name, job_execution_id, job_id, tenant_id,
                            priority, status, available_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'READY', ?)
                        """,
                com.schedula.common.ids.UuidV7.generate(), DEFAULT_QUEUE, executionId, jobId,
                tenantId, priority, Timestamp.from(availableAt));
    }

    /**
     * Atomic batch claim. FOR UPDATE SKIP LOCKED means concurrent workers never block each
     * other; each message goes to exactly one claimer per delivery round.
     */
    public List<QueueMessage> claim(UUID workerId, int batch, long visibilityTimeoutMs) {
        List<QueueMessage> claimed = jdbc.query("""
                        UPDATE queue_messages m
                        SET status = 'CLAIMED', claim_owner = ?, claim_expires_at = now() + (? * interval '1 millisecond'),
                            deliver_count = deliver_count + 1
                        WHERE m.id IN (
                            SELECT id FROM queue_messages
                            WHERE queue_name = ? AND status = 'READY' AND available_at <= now()
                            ORDER BY priority DESC, enqueue_seq
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING m.*
                        """,
                MESSAGE, workerId, (double) visibilityTimeoutMs, DEFAULT_QUEUE, batch);
        claimsTotal.increment(claimed.size());
        return claimed;
    }

    public boolean ack(UUID messageId, UUID claimOwner) {
        return jdbc.update("""
                        UPDATE queue_messages SET status = 'DONE'
                        WHERE id = ? AND claim_owner = ? AND status = 'CLAIMED'
                        """, messageId, claimOwner) == 1;
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

    public long depth(String queueName) {
        Long d = jdbc.queryForObject(
                "SELECT count(*) FROM queue_messages WHERE queue_name = ? AND status = 'READY'",
                Long.class, queueName);
        return d == null ? 0 : d;
    }
}
