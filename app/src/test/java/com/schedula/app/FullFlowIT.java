package com.schedula.app;

import com.schedula.api.JobsController;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.retry.ErrorClass;
import com.schedula.persistence.ScheduleStore;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// NOTE: property set must stay IDENTICAL to ReliabilityIT's so Spring shares one cached
// context (and one Postgres container) across both classes.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "logging.level.com.schedula=DEBUG",
        "schedula.auth.enabled=false"
})
@Import(FullFlowIT.TestHandlers.class)
class FullFlowIT {

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
            return new TestHandlers();
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScheduleStore schedules;

    private Job submit(String body) {
        ResponseEntity<Job> res = http.postForEntity("/v1/jobs", jsonBody(body), Job.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    static Object jsonBody(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Job getJob(UUID id) {
        ResponseEntity<Job> res = http.getForEntity("/v1/jobs/" + id, Job.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    @Test
    void immediateJobRunsToCompletion() {
        Job job = submit("""
                {"jobType":"log","payload":{"msg":"hello schedula"}}
                """);
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 15_000);
        Integer execs = jdbc.queryForObject(
                "SELECT count(*) FROM job_executions WHERE job_id = ? AND status = 'COMPLETED'",
                Integer.class, job.id());
        assertThat(execs).isEqualTo(1);
    }

    @Test
    void failingTransientJobRetriesThenDies() {
        Job job = submit("""
                {"jobType":"boom","payload":{},"maxAttempts":3,
                 "retryPolicy":{"backoff":"FIXED","initialDelayMs":10}}
                """);
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.DEAD, 30_000);
        Integer attempts = jdbc.queryForObject(
                "SELECT count(*) FROM job_executions WHERE job_id = ?", Integer.class, job.id());
        assertThat(attempts).isEqualTo(3);
        Integer dlq = jdbc.queryForObject(
                "SELECT count(*) FROM queue_messages WHERE job_id = ? AND status = 'DEADLETTERED'",
                Integer.class, job.id());
        assertThat(dlq).isEqualTo(1);
    }

    @Test
    void delayedJobStaysScheduledUntilDue() {
        Job job = submit("{\"jobType\":\"log\",\"payload\":{}," +
                "\"scheduledFor\":\"" + Instant.now().plusSeconds(3) + "\"}");
        assertThat(getJob(job.id()).status()).isEqualTo(JobStatus.SCHEDULED);
        Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 20_000);
    }

    @Test
    void idempotencyKeyDeduplicatesSubmission() {
        String key = "it-" + UUID.randomUUID();
        var req = org.springframework.http.RequestEntity
                .post("/v1/jobs")
                .header("Idempotency-Key", key)
                .body(jsonBody("{\"jobType\":\"log\",\"payload\":{}}"));
        ResponseEntity<Job> first = http.exchange(req, Job.class);
        ResponseEntity<Job> second = http.exchange(req, Job.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
    }

    @Test
    void cancelledQueuedJobNeverExecutes() {
        Job job = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":5000}," +
                "\"scheduledFor\":\"" + Instant.now().plusSeconds(2) + "\"}");
        ResponseEntity<Job> cancel = http.postForEntity("/v1/jobs/" + job.id() + "/cancel", null,
                Job.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody().status()).isEqualTo(JobStatus.CANCELLED);
        try {
            Thread.sleep(4_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(getJob(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        Integer execs = jdbc.queryForObject(
                "SELECT count(*) FROM job_executions WHERE job_id = ?", Integer.class, job.id());
        assertThat(execs).isZero();
    }

    @Test
    void fixedIntervalScheduleProducesOccurrences() {
        schedules.create(new ScheduleStore.Insert(JobsController.DEFAULT_TENANT,
                "it-schedule-" + UUID.randomUUID(), "log", "{}", 500L, null, null, "COALESCE"));
        Await.until(
                () -> jdbc.queryForObject(
                        "SELECT count(*) FROM jobs WHERE schedule_id IS NOT NULL", Integer.class),
                c -> c != null && c >= 2, 20_000);
    }
}

