package com.schedula.api;

import com.schedula.common.jobs.IllegalTransitionException;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.JobExecution;
import com.schedula.common.retry.RetryPolicy;
import com.schedula.persistence.AuditStore;
import com.schedula.api.auth.RequestTenant;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.JobStore;
import com.schedula.queue.PostgresQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Map;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/jobs")
public class JobsController {

    public static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public record SubmitRequest(UUID tenantId, @NotBlank String jobType,
                                com.fasterxml.jackson.databind.JsonNode payload,
                                Integer priority, Integer maxAttempts,
                                com.fasterxml.jackson.databind.JsonNode retryPolicy,
                                Long timeoutMs, Instant scheduledFor,
                                java.util.List<String> requiredCapabilities,
                                Integer requiredCpu, Long requiredMemMb,
                                String queueName, String webhookUrl) {
    }

    private final JobStore jobs;
    private final ExecutionStore executions;
    private final PostgresQueue queue;
    private final TransactionTemplate tx;
    private final AuditStore audit;
    private final com.schedula.persistence.QuotaStore quota;
    private final Counter submittedTotal;

    public JobsController(JobStore jobs, ExecutionStore executions, PostgresQueue queue,
                          TransactionTemplate tx, AuditStore audit,
                          com.schedula.persistence.QuotaStore quota, MeterRegistry meters) {
        this.jobs = jobs;
        this.executions = executions;
        this.queue = queue;
        this.tx = tx;
        this.audit = audit;
        this.quota = quota;
        this.submittedTotal = Counter.builder("schedula_job_submitted_total").register(meters);
    }

    /**
     * Tenant scope resolution order: admin-requested > authenticated key > requested >
     * default. With auth enabled the filter guarantees a principal, so the final
     * fallback only applies to anonymous (auth-disabled) deployments.
     */
    private UUID resolveTenant(HttpServletRequest http, UUID requested) {
        if (RequestTenant.isAdmin(http)) {
            return requested == null ? DEFAULT_TENANT : requested;
        }
        var fromKey = RequestTenant.tenant(http);
        if (fromKey.isPresent()) {
            return fromKey.get();
        }
        return requested == null ? DEFAULT_TENANT : requested;
    }

    /**
     * Durable-before-ack: the job row commits before 202 returns. Idempotency-Key dedups
     * via a unique constraint; a racing duplicate receives the original job (ADR-002/§88).
     */
    @PostMapping
    ResponseEntity<Job> submit(@RequestBody @Valid SubmitRequest req,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               HttpServletRequest http) {
        UUID tenantId = resolveTenant(http, req.tenantId());
        String retryPolicyJson = req.retryPolicy() == null ? "{}" : req.retryPolicy().toString();
        RetryPolicy policy = RetryPolicy.fromJson(retryPolicyJson);
        quota.admissionCheck(tenantId);
        var result = jobs.createReturningFreshness(new JobStore.Insert(
                tenantId, req.jobType(), req.priority() == null ? 0 : req.priority(),
                req.payload() == null ? "{}" : req.payload().toString(),
                req.maxAttempts() == null ? policy.maxAttempts() : req.maxAttempts(),
                retryPolicyJson,
                req.timeoutMs() == null ? 60_000L : req.timeoutMs(),
                req.scheduledFor(), null, idempotencyKey,
                req.requiredCapabilities(), req.requiredCpu(), req.requiredMemMb(),
                req.queueName(), req.webhookUrl()));
        if (result.fresh()) {
            submittedTotal.increment();
            return ResponseEntity.created(URI.create("/v1/jobs/" + result.job().id())).body(result.job());
        }
        return ResponseEntity.ok().body(result.job());
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

    @GetMapping("/{id}/events")
    List<com.schedula.common.model.JobEvent> events(@PathVariable UUID id,
                                                    com.schedula.persistence.EventStore eventStore) {
        jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        return eventStore.listByJob(id);
    }

    @GetMapping
    ResponseEntity<List<Job>> list(@RequestParam(required = false) UUID tenantId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(required = false) Instant before,
                                   HttpServletRequest http) {
        UUID scope = resolveTenant(http, tenantId);
        List<Job> page;
        if (before != null) {
            page = jobs.listBefore(scope, status, before, Math.min(limit, 200));
        } else {
            page = jobs.listByTenant(scope, status, Math.min(limit, 200), offset);
        }
        var builder = ResponseEntity.ok();
        if (page.size() >= Math.min(limit, 200) && !page.isEmpty()) {
            // cursor for the next older page (keyset, stable under inserts)
            builder.header("X-Next-Cursor", page.get(page.size() - 1).createdAt().toString());
        }
        return builder.body(page);
    }

    /**
     * Bulk submission (cloud-queue parity): up to 500 jobs in one transaction.
     * Per-job idempotency keys still dedupe inside the batch.
     */
    public record BatchRequest(List<SubmitRequest> jobs) {
    }

    @PostMapping("/batch")
    ResponseEntity<?> batch(@RequestBody BatchRequest req, HttpServletRequest http) {
        if (req.jobs() == null || req.jobs().isEmpty()) {
            throw new IllegalArgumentException("jobs array required");
        }
        if (req.jobs().size() > 500) {
            throw new IllegalArgumentException("batch capped at 500");
        }
        List<Map<String, Object>> results = tx.execute(s -> req.jobs().stream().map(r -> {
            UUID tenantId = resolveTenant(http, r.tenantId());
            String retryPolicyJson = r.retryPolicy() == null ? "{}" : r.retryPolicy().toString();
            RetryPolicy policy = RetryPolicy.fromJson(retryPolicyJson);
            quota.admissionCheck(tenantId);
            var result = jobs.createReturningFreshness(new JobStore.Insert(
                    tenantId, r.jobType(), r.priority() == null ? 0 : r.priority(),
                    r.payload() == null ? "{}" : r.payload().toString(),
                    r.maxAttempts() == null ? policy.maxAttempts() : r.maxAttempts(),
                    retryPolicyJson,
                    r.timeoutMs() == null ? 60_000L : r.timeoutMs(),
                    r.scheduledFor(), null, null,
                    r.requiredCapabilities(), r.requiredCpu(), r.requiredMemMb(),
                    r.queueName(), r.webhookUrl()));
            return Map.<String, Object>of(
                    "id", result.job().id().toString(),
                    "fresh", result.fresh());
        }).toList());
        submittedTotal.increment(results.stream().filter(m -> Boolean.TRUE.equals(m.get("fresh"))).count());
        return ResponseEntity.accepted().body(Map.of("submitted", results));
    }

    /**
     * Queued/scheduled cancellation is synchronous and atomic with message removal.
     * Running work enters CANCELLING: the worker learns about it on its next lease renewal
     * and the handler is expected to exit cooperatively via its CancellationToken.
     */
    @PostMapping("/{id}/cancel")
    ResponseEntity<Job> cancel(@PathVariable UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
        if (job.status().isTerminal()) {
            throw new IllegalTransitionException(job.status(), JobStatus.CANCELLED);
        }
        if (job.status() == JobStatus.RUNNING || job.status() == JobStatus.DISPATCHED) {
            boolean cancelling = jobs.transition(id, Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                    JobStatus.CANCELLING, "api:cancel", "cooperative cancellation requested");
            if (!cancelling) {
                throw new IllegalTransitionException(job.status(), JobStatus.CANCELLING);
            }
            audit.append("api", job.tenantId(), "JOB_CANCEL_REQUESTED", "job", id.toString(), null);
            return ResponseEntity.accepted().body(jobs.findById(id).orElseThrow());
        }
        boolean moved = tx.execute(s -> jobs.transition(id,
                Set.of(JobStatus.SCHEDULED, JobStatus.QUEUED, JobStatus.PAUSED, JobStatus.RETRY_WAIT),
                JobStatus.CANCELLED, "api:cancel", "requested via api"));
        if (Boolean.TRUE.equals(moved)) {
            queue.cancelReadyForJob(id);
            audit.append("api", job.tenantId(), "JOB_CANCELLED", "job", id.toString(), null);
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
                null, null, old.idempotencyKey() + ":retry:" + UUID.randomUUID(),
                old.requiredCapabilities(), old.requiredCpu(), old.requiredMemMb(), null, null));
        submittedTotal.increment();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(fresh);
    }
}



