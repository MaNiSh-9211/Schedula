package com.schedula.app;

import com.schedula.api.JobsController;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.retry.ErrorClass;
import com.schedula.dispatcher.DispatchService;
import com.schedula.engine.RecoveryService;
import com.schedula.persistence.AuditStore;
import com.schedula.persistence.EffectLedger;
import com.schedula.worker.ClassifiedException;
import com.schedula.worker.HandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 reliability behaviors: cooperative cancellation, lease renewal keeping long
 * jobs alive across visibility timeouts, expired-claim recovery, DLQ admin ops, audits,
 * and the effect ledger dedupe primitive.
 */
// NOTE: property set must stay IDENTICAL to FullFlowIT's so Spring shares one cached
// context (and one Postgres container) across both classes.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "logging.level.com.schedula=DEBUG"
})
@Import(ReliabilityIT.TestHandlers.class)
class ReliabilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class TestHandlers {
        @Bean
        TestHandlers probe(HandlerRegistry registry) {
            registry.register("boom", ctx -> {
                throw new ClassifiedException(ErrorClass.TRANSIENT, "simulated transient failure");
            });
            registry.register("boomOnce", ctx -> {
                if (ctx.attempt() == 1) {
                    throw new ClassifiedException(ErrorClass.TRANSIENT, "first attempt fails");
                }
            });
            return new TestHandlers();
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EffectLedger effects;

    @Autowired
    DispatchService dispatcher;

    @Autowired
    RecoveryService recovery;

    @Autowired
    com.schedula.coordination.Coordinator coordinator;

    private Job submit(String body) {
        ResponseEntity<Job> res = http.postForEntity("/v1/jobs", FullFlowIT.jsonBody(body), Job.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private Job getJob(UUID id) {
        return http.getForEntity("/v1/jobs/" + id, Job.class).getBody();
    }

    @Test
    void cancelRunningJobCooperatively() {
        Job job = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":15000}}");
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.RUNNING, 15_000);

        ResponseEntity<Job> cancel =
                http.postForEntity("/v1/jobs/" + job.id() + "/cancel", null, Job.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(cancel.getBody().status()).isEqualTo(JobStatus.CANCELLING);

        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.CANCELLED, 10_000);

        String execStatus = jdbc.queryForObject("""
                SELECT e.status FROM job_executions e WHERE e.job_id = ?
                """, String.class, job.id());
        assertThat(execStatus).isEqualTo("CANCELLED");
    }

    @Test
    void leaseRenewalKeepsLongJobAlivePastVisibilityTimeout() {
        // visibility window is 4s in this context; an 8s job survives ONLY if renewals work
        Job job = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":8000}}");
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 25_000);
        Integer execs = jdbc.queryForObject(
                "SELECT count(*) FROM job_executions WHERE job_id = ?", Integer.class, job.id());
        assertThat(execs).as("renewal must prevent redelivery").isEqualTo(1);
    }

    @Test
    void retryAfterTransientFailureEventuallySucceeds() {
        Job job = submit("{\"jobType\":\"boomOnce\",\"payload\":{},\"maxAttempts\":3," +
                "\"retryPolicy\":{\"backoff\":\"FIXED\",\"initialDelayMs\":50}}");
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 20_000);
        Integer attempts = jdbc.queryForObject(
                "SELECT count(*) FROM job_executions WHERE job_id = ?", Integer.class, job.id());
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void expiredClaimIsAbandonedAndRedelivered() {
        // recovery is a leader duty; wait for THIS context's coordinator to win the lease
        Await.until(coordinator::isLeader, b -> b, 15_000);
        // drive the pipeline manually with roles-independent beans
        Job job = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":100}}");
        // wait until claimed by the live worker, then simulate a dead claim by expiring it
        Await.until(() -> getJob(job.id()),
                j -> j.status() == JobStatus.RUNNING || j.status() == JobStatus.COMPLETED, 15_000);
        if (getJob(job.id()).status() == JobStatus.COMPLETED) {
            return; // raced too fast; recovery path covered by QueueClaimIT
        }
        jdbc.update("UPDATE queue_messages SET claim_expires_at = now() - interval '1 second' " +
                "WHERE job_id = ?", job.id());
        recovery.recover();
        String status = jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?",
                String.class, job.id());
        String oldExec = jdbc.queryForObject("""
                SELECT status FROM job_executions WHERE job_id = ? ORDER BY attempt_no DESC LIMIT 1
                """, String.class, job.id());
        Integer readyMsgs = jdbc.queryForObject("""
                SELECT count(*) FROM queue_messages WHERE job_id = ? AND status = 'READY'
                """, Integer.class, job.id());
        assertThat(status).isEqualTo(JobStatus.QUEUED.name());
        assertThat(oldExec).isEqualTo("ABANDONED");
        assertThat(readyMsgs).isEqualTo(1);
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 20_000);
    }

    @Test
    void dlqListRetryDeleteWithAudits() {
        Job dead = submit("{\"jobType\":\"boom\",\"payload\":{},\"maxAttempts\":1}");
        Await.until(() -> getJob(dead.id()), j -> j.status() == JobStatus.DEAD, 20_000);

        ResponseEntity<List> list = http.getForEntity("/v1/dlq", List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) list.getBody()).isNotEmpty();

        UUID messageId = jdbc.queryForObject("""
                SELECT m.id FROM queue_messages m WHERE m.job_id = ? AND m.status = 'DEADLETTERED'
                """, UUID.class, dead.id());

        ResponseEntity<Job> retried =
                http.postForEntity("/v1/dlq/" + messageId + "/retry", null, Job.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retried.getBody().id()).isNotEqualTo(dead.id());

        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'DLQ_RETRY'", Integer.class);
        assertThat(audited).isGreaterThanOrEqualTo(1);

        // second letter: exercise delete
        Job dead2 = submit("{\"jobType\":\"boom\",\"payload\":{},\"maxAttempts\":1}");
        Await.until(() -> getJob(dead2.id()), j -> j.status() == JobStatus.DEAD, 20_000);
        UUID messageId2 = jdbc.queryForObject("""
                SELECT m.id FROM queue_messages m WHERE m.job_id = ? AND m.status = 'DEADLETTERED'
                  AND m.resolved_at IS NULL
                """, UUID.class, dead2.id());
        var del = org.springframework.http.RequestEntity
                .delete("/v1/dlq/" + messageId2).build();
        ResponseEntity<Void> deleted = http.exchange(del, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Integer auditDeletes = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'DLQ_DELETE'", Integer.class);
        assertThat(auditDeletes).isGreaterThanOrEqualTo(1);
    }

    @Test
    void effectLedgerRunsEffectExactlyOnce() {
        var tenant = JobsController.DEFAULT_TENANT;
        var job = UUID.randomUUID();
        var first = effects.once(tenant, job, "send-email", () -> "{\"ok\":true}");
        var second = effects.once(tenant, job, "send-email", () -> {
            throw new AssertionError("effect must not run twice");
        });
        assertThat(first.firstRun()).isTrue();
        assertThat(second.firstRun()).isFalse();
        assertThat(second.resultJson()).contains("ok");
    }
}
