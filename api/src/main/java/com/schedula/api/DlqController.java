package com.schedula.api;

import com.schedula.common.jobs.IllegalTransitionException;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.api.auth.RequestTenant;
import com.schedula.persistence.AuditStore;
import com.schedula.persistence.JobStore;
import com.schedula.queue.PostgresQueue;
import com.schedula.queue.PostgresQueue.DeadLetter;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/dlq")
public class DlqController {

    private final PostgresQueue queue;
    private final JobStore jobs;
    private final AuditStore audit;
    private final AuditStore auditStore;
    private final Counter dlqRetries;

    public DlqController(PostgresQueue queue, JobStore jobs, AuditStore audit, MeterRegistry meters) {
        this.queue = queue;
        this.jobs = jobs;
        this.audit = audit;
        this.auditStore = audit;
        this.dlqRetries = Counter.builder("schedula_dlq_retry_total").register(meters);
    }

    @GetMapping
    List<DeadLetter> list(@RequestParam(required = false) String jobType,
                          @RequestParam(defaultValue = "50") int limit,
                          @RequestParam(defaultValue = "0") int offset) {
        return queue.listDeadLetters(jobType, Math.min(limit, 200), offset);
    }

    /** Manual DLQ retry: creates a fresh job (history untouched), marks the letter resolved. */
    /** Bulk retry: replays all dead letters matching the filter. */
    @PostMapping("/retry-bulk")
    ResponseEntity<?> retryBulk(@RequestBody Map<String, String> filter, HttpServletRequest http) {
        if (!RequestTenant.isAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of("detail", "admin key required"));
        }
        String jobType = filter.get("jobType");
        var letters = queue.listDeadLetters(jobType, 500, 0).stream()
                .filter(l -> l.resolvedAt() == null)
                .toList();
        int retried = 0;
        for (var letter : letters) {
            Job original = jobs.findById(letter.jobId()).orElse(null);
            if (original == null) continue;
            jobs.create(new JobStore.Insert(
                    original.tenantId(), original.jobType(), original.priority(),
                    original.payloadJson(), original.maxAttempts(), original.retryPolicyJson(),
                    original.timeoutMs(), null, null,
                    original.idempotencyKey() + ":bulk:" + UUID.randomUUID(),
                    original.requiredCapabilities(), original.requiredCpu(), original.requiredMemMb(),
                    original.queueName(), null));
            queue.resolveDeadLetter(letter.messageId());
            retried++;
        }
        auditStore.append("api:admin", null, "DLQ_BULK_RETRY", "queue_message", null,
                "{\"count\":" + retried + "}");
        return ResponseEntity.ok(Map.of("retried", retried));
    }

    @DeleteMapping("/delete-bulk")
    ResponseEntity<?> deleteBulk(@RequestBody Map<String, String> filter, HttpServletRequest http) {
        if (!RequestTenant.isAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of("detail", "admin key required"));
        }
        String jobType = filter.get("jobType");
        var letters = queue.listDeadLetters(jobType, 500, 0).stream()
                .filter(l -> l.resolvedAt() == null)
                .toList();
        int deleted = 0;
        for (var letter : letters) {
            deleted += queue.deleteDeadLetter(letter.messageId());
        }
        auditStore.append("api:admin", null, "DLQ_BULK_DELETE", "queue_message", null,
                "{\"count\":" + deleted + "}");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{messageId}/retry")
    ResponseEntity<Job> retry(@PathVariable UUID messageId) {
        DeadLetter letter = queue.findDeadLetter(messageId);
        if (letter == null) {
            throw new NotFoundException("dead letter", messageId);
        }
        Job original = jobs.findById(letter.jobId())
                .orElseThrow(() -> new NotFoundException("job", letter.jobId()));
        Job fresh = jobs.create(new JobStore.Insert(
                original.tenantId(), original.jobType(), original.priority(),
                original.payloadJson(), original.maxAttempts(), original.retryPolicyJson(),
                original.timeoutMs(), null, null,
                original.idempotencyKey() + ":dlq-retry:" + UUID.randomUUID(),
                original.requiredCapabilities(), original.requiredCpu(), original.requiredMemMb(),
                original.queueName(), null));
        if (!queue.resolveDeadLetter(messageId)) {
            throw new IllegalTransitionException(JobStatus.DEAD, JobStatus.QUEUED);
        }
        audit.append("api", original.tenantId(), "DLQ_RETRY", "queue_message",
                messageId.toString(), "{\"newJobId\":\"" + fresh.id() + "\"}");
        dlqRetries.increment();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(fresh);
    }

    @DeleteMapping("/{messageId}")
    ResponseEntity<Void> delete(@PathVariable UUID messageId) {
        DeadLetter letter = queue.findDeadLetter(messageId);
        if (letter == null) {
            throw new NotFoundException("dead letter", messageId);
        }
        int deleted = queue.deleteDeadLetter(messageId);
        if (deleted == 1) {
            audit.append("api", letter.tenantId(), "DLQ_DELETE", "queue_message",
                    messageId.toString(), null);
        }
        return ResponseEntity.noContent().build();
    }
}


