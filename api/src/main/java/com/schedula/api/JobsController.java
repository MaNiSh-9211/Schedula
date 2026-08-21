package com.schedula.api;

import com.schedula.common.jobs.IllegalTransitionException;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.JobExecution;
import com.schedula.common.retry.RetryPolicy;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.JobStore;
import com.schedula.queue.PostgresQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/jobs")
public class JobsController {

    public static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public record SubmitRequest(UUID tenantId, @NotBlank String jobType, String payload,
                                Integer priority, Integer maxAttempts, String retryPolicy,
                                Long timeoutMs, Instant scheduledFor) {
    }

    private final JobStore jobs;
    private final ExecutionStore executions;
    private final PostgresQueue queue;
    private final TransactionTemplate tx;
    private final Counter submittedTotal;

    public JobsController(JobStore jobs, ExecutionStore executions, PostgresQueue queue,
                          TransactionTemplate tx, MeterRegistry meters) {
        this.jobs = jobs;
        this.executions = executions;
        this.queue = queue;
        this.tx = tx;
        this.submittedTotal = Counter.builder("schedula_job_submitted_total").register(meters);
    }

    /**
     * Durable-before-ack: the job row commits before 202 returns. Idempotency-Key dedups
     * via a unique constraint; a racing duplicate receives the original job (ADR-002/§88).
     */
    @PostMapping
    ResponseEntity<Job> submit(@RequestBody @Valid SubmitRequest req,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID tenantId = req.tenantId() == null ? DEFAULT_TENANT : req.tenantId();
        RetryPolicy policy = req.retryPolicy() == null && req.maxAttempts() == null
                ? RetryPolicy.DEFAULT
                : RetryPolicy.fromJson(req.retryPolicy() == null ? "{}" : req.retryPolicy());
        Job created = jobs.create(new JobStore.Insert(
                tenantId, req.jobType(), req.priority() == null ? 0 : req.priority(),
                req.payload() == null ? "{}" : req.payload(),
                req.maxAttempts() == null ? policy.maxAttempts() : req.maxAttempts(),
                req.retryPolicy() == null ? "{}" : req.retryPolicy(),
                req.timeoutMs() == null ? 60_000L : req.timeoutMs(),
                req.scheduledFor(), null, idempotencyKey));
        boolean isNew = created.status() == JobStatus.CREATED || created.status() == JobStatus.SCHEDULED;
        if (isNew) {
            submittedTotal.increment();
            return ResponseEntity.created(URI.create("/v1/jobs/" + created.id())).body(created);
        }
        return ResponseEntity.ok().body(created);
    }

    @GetMapping("/{id}")
    Job get(@PathVariable UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
    }

    @GetMapping("/{id}/executions")
    List<JobExecution> executions(@PathVariable UUID id) {
        jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        return executions.findByJob(id);
    }

    @GetMapping
    List<Job> list(@RequestParam(required = false) UUID tenantId,
                   @RequestParam(required = false) String status,
                   @RequestParam(defaultValue = "50") int limit,
                   @RequestParam(defaultValue = "0") int offset) {
        return jobs.listByTenant(tenantId == null ? DEFAULT_TENANT : tenantId, status,
                Math.min(limit, 200), offset);
    }

    /** Queued/scheduled cancellation is synchronous and atomic with message removal. */
    @PostMapping("/{id}/cancel")
    ResponseEntity<Job> cancel(@PathVariable UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        if (job.status().isTerminal()) {
            throw new IllegalTransitionException(job.status(), JobStatus.CANCELLED);
        }
        if (job.status() == JobStatus.RUNNING || job.status() == JobStatus.DISPATCHED) {
            // cooperative cancellation of running work lands in phase 2 (CANCELLING state)
            throw new IllegalTransitionException(job.status(), JobStatus.CANCELLED);
        }
        boolean moved = tx.execute(s -> jobs.transition(id,
                Set.of(JobStatus.SCHEDULED, JobStatus.QUEUED, JobStatus.PAUSED, JobStatus.RETRY_WAIT),
                JobStatus.CANCELLED, "api:cancel", "requested via api"));
        if (Boolean.TRUE.equals(moved)) {
            queue.cancelReadyForJob(id);
            return ResponseEntity.ok(jobs.findById(id).orElseThrow());
        }
        throw new IllegalTransitionException(job.status(), JobStatus.CANCELLED);
    }

    @PostMapping("/{id}/pause")
    ResponseEntity<Job> pause(@PathVariable UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        boolean moved = tx.execute(s -> {
            boolean ok = jobs.transition(id, Set.of(JobStatus.SCHEDULED, JobStatus.QUEUED),
                    JobStatus.PAUSED, "api:pause", "requested via api");
            if (ok) {
                queue.cancelReadyForJob(id);
            }
            return ok;
        });
        if (Boolean.TRUE.equals(moved)) {
            return ResponseEntity.ok(jobs.findById(id).orElseThrow());
        }
        throw new IllegalTransitionException(job.status(), JobStatus.PAUSED);
    }

    @PostMapping("/{id}/resume")
    ResponseEntity<Job> resume(@PathVariable UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        boolean moved = jobs.transition(id, Set.of(JobStatus.PAUSED), JobStatus.SCHEDULED,
                "api:resume", "requested via api");
        if (!moved) {
            throw new IllegalTransitionException(job.status(), JobStatus.SCHEDULED);
        }
        return ResponseEntity.accepted().body(jobs.findById(id).orElseThrow());
    }

    /** Manual retry never mutates history: it creates a fresh job linked by idempotency lineage. */
    @PostMapping("/{id}/retry")
    ResponseEntity<Job> retry(@PathVariable UUID id) {
        Job old = jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        if (!old.status().isTerminal()) {
            throw new IllegalTransitionException(old.status(), JobStatus.QUEUED);
        }
        Job fresh = jobs.create(new JobStore.Insert(
                old.tenantId(), old.jobType(), old.priority(), old.payloadJson(),
                old.maxAttempts(), old.retryPolicyJson(), old.timeoutMs(),
                null, null, old.idempotencyKey() + ":retry:" + UUID.randomUUID()));
        submittedTotal.increment();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(fresh);
    }
}
