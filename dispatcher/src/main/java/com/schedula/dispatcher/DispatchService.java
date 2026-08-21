package com.schedula.dispatcher;

import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.JobExecution;
import com.schedula.common.model.QueueMessage;
import com.schedula.persistence.EventStore;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.Fence;
import com.schedula.persistence.JobStore;
import com.schedula.queue.PostgresQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Atomic claim-and-dispatch: queue claim, execution lease grant (fencing token), and the
 * QUEUED -> DISPATCHED transition commit together. A cancelled/paused job resolves the race
 * here: if the job CAS fails, the claimed message is cancelled and no execution exists.
 */
@Service
public class DispatchService {

    public record Claimed(QueueMessage message, JobExecution execution, long fencingToken) {
    }

    private final TransactionTemplate tx;
    private final PostgresQueue queue;
    private final JobStore jobs;
    private final ExecutionStore executions;
    private final EventStore events;
    private final Fence fence;

    public DispatchService(TransactionTemplate tx, JdbcTemplate jdbc, PostgresQueue queue,
                           JobStore jobs, ExecutionStore executions, EventStore events, Fence fence) {
        this.tx = tx;
        this.queue = queue;
        this.jobs = jobs;
        this.executions = executions;
        this.events = events;
        this.fence = fence;
    }

    public List<Claimed> claimAndDispatch(UUID workerId, int batch, long visibilityTimeoutMs) {
        return tx.execute(status -> {
            List<QueueMessage> messages = queue.claim(workerId, batch, visibilityTimeoutMs);
            List<Claimed> out = new ArrayList<>(messages.size());
            for (QueueMessage m : messages) {
                boolean moved = jobs.transition(m.jobId(), Set.of(JobStatus.QUEUED),
                        JobStatus.DISPATCHED, "dispatcher", "claimed by worker " + workerId);
                if (!moved) {
                    queue.cancelClaimed(m.id(), workerId);
                    continue;
                }
                long token = fence.nextToken(Fence.EXECUTION);
                JobExecution exec = executions.create(m.jobId(), m.deliverCount(), token, workerId);
                events.append(m.jobId(), exec.id(), "EXECUTION_LEASE_GRANTED", "dispatcher",
                        "attempt " + m.deliverCount() + " -> worker " + workerId, token);
                out.add(new Claimed(m, exec, token));
            }
            return out;
        });
    }
}
