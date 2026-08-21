package com.schedula.engine;

import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.QueueMessage;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.JobStore;
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
 * Runs periodically and once at startup (crash recovery for this node's previous life).
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final PostgresQueue queue;
    private final JobStore jobs;
    private final ExecutionStore executions;
    private final int maxDeliveries;

    public RecoveryService(PostgresQueue queue, JobStore jobs, ExecutionStore executions,
                           @Value("${schedula.queue.max-deliveries:5}") int maxDeliveries) {
        this.queue = queue;
        this.jobs = jobs;
        this.executions = executions;
        this.maxDeliveries = maxDeliveries;
    }

    public void recover() {
        List<Reclaimed> reclaimed = queue.reclaimExpired(maxDeliveries);
        for (Reclaimed r : reclaimed) {
            QueueMessage m = r.message();
            if (m.jobExecutionId() != null) {
                executions.abandon(m.jobExecutionId());
            }
            if (r.deadlettered()) {
                jobs.transition(m.jobId(), Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                        JobStatus.DEAD, "sweeper", "max deliveries exceeded");
                log.warn("job {} deadlettered after {} deliveries", m.jobId(), m.deliverCount());
            } else {
                jobs.transition(m.jobId(), Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                        JobStatus.QUEUED, "sweeper", "claim expired; redelivering");
                log.info("job {} requeued after expired claim (delivery {})",
                        m.jobId(), m.deliverCount());
            }
        }
    }
}
