package com.schedula.engine;

import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.QueueMessage;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.JobStore;
import com.schedula.persistence.WorkerStore;
import com.schedula.queue.PostgresQueue;
import com.schedula.queue.PostgresQueue.Reclaimed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Visibility-timeout recovery. A claim that expired without an ack means the worker may be
 * dead OR merely slow/partitioned; either way the message is reclaimable and fencing tokens
 * make any late write from the old claimer inert (ADR-003/004).
 *
 * Also owns liveness bookkeeping: workers silent past thresholds are marked UNHEALTHY/DEAD,
 * and jobs whose CANCELLING request outlived their lease are closed as CANCELLED.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final PostgresQueue queue;
    private final JobStore jobs;
    private final ExecutionStore executions;
    private final WorkerStore workers;
    private final com.schedula.coordination.Coordinator coordinator;
    private final int maxDeliveries;
    private final long unhealthyAfterMs;
    private final long deadAfterMs;

    public RecoveryService(PostgresQueue queue, JobStore jobs, ExecutionStore executions,
                           WorkerStore workers,
                           com.schedula.coordination.Coordinator coordinator,
                           @Value("${schedula.queue.max-deliveries:5}") int maxDeliveries,
                           @Value("${schedula.recovery.unhealthy-after-ms:15000}") long unhealthyAfterMs,
                           @Value("${schedula.recovery.dead-after-ms:60000}") long deadAfterMs) {
        this.queue = queue;
        this.jobs = jobs;
        this.executions = executions;
        this.workers = workers;
        this.coordinator = coordinator;
        this.maxDeliveries = maxDeliveries;
        this.unhealthyAfterMs = unhealthyAfterMs;
        this.deadAfterMs = deadAfterMs;
    }

    public void recover() {
        if (!coordinator.isLeader()) {
            return;
        }
        long token = coordinator.fencingToken();
        reclaimExpiredClaims(token);
        markSilentWorkers();
    }

    private void reclaimExpiredClaims(long leadershipToken) {
        List<Reclaimed> reclaimed = queue.reclaimExpired(maxDeliveries);
        for (Reclaimed r : reclaimed) {
            QueueMessage m = r.message();
            Job job = jobs.findById(m.jobId()).orElse(null);
            boolean cancelRequested = job != null && job.status() == JobStatus.CANCELLING;
            if (m.jobExecutionId() != null) {
                executions.abandon(m.jobExecutionId());
            }
            if (cancelRequested) {
                // never redeliberately deliver work the operator asked to cancel
                jobs.transition(m.jobId(), Set.of(JobStatus.CANCELLING), JobStatus.CANCELLED,
                        "sweeper", "lease expired during cancellation", leadershipToken);
                queue.cancelReadyForJob(m.jobId());
                log.info("job {} closed CANCELLED after lease expiry during cancellation", m.jobId());
                continue;
            }
            if (r.deadlettered()) {
                jobs.transition(m.jobId(), Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                        JobStatus.DEAD, "sweeper", "max deliveries exceeded", leadershipToken);
                log.warn("job {} deadlettered after {} deliveries", m.jobId(), m.deliverCount());
            } else {
                jobs.transition(m.jobId(), Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                        JobStatus.QUEUED, "sweeper", "claim expired; redelivering", leadershipToken);
                log.info("job {} requeued after expired claim (delivery {})",
                        m.jobId(), m.deliverCount());
            }
        }
    }

    private void markSilentWorkers() {
        int unhealthy = workers.markUnhealthyPast(unhealthyAfterMs);
        int dead = workers.markDeadPast(deadAfterMs);
        if (unhealthy > 0 || dead > 0) {
            log.warn("failure detector: {} worker(s) UNHEALTHY, {} worker(s) DEAD",
                    unhealthy, dead);
        }
    }
}
