package com.schedula.app;

import com.schedula.api.JobsController;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.worker.WorkerLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// NOTE: property set AND imports must stay IDENTICAL to ReliabilityIT's so Spring shares
// one cached context (and one Postgres container) across the app-level IT classes.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "logging.level.com.schedula=DEBUG"
})
@org.springframework.context.annotation.Import(ReliabilityIT.TestHandlers.class)
class CapacityLimitsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WorkerLoop worker;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "it-" + id);
        return id;
    }

    private Job submit(String body) {
        ResponseEntity<Job> res = http.postForEntity("/v1/jobs", FullFlowIT.jsonBody(body), Job.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private Job getJob(UUID id) {
        return http.getForEntity("/v1/jobs/" + id, Job.class).getBody();
    }

    @AfterEach
    void cleanupQuotaState() {
        jdbc.update("DELETE FROM tenant_quotas");
        jdbc.update("DELETE FROM job_type_limits");
        jdbc.update("UPDATE workers SET capabilities = '{}', status = 'HEALTHY'");
    }

    @Test
    void capabilityMismatchKeepsJobQueuedMatchRuns() throws Exception {
        jdbc.update("UPDATE workers SET capabilities = '{gpu}' WHERE id = ?", worker.workerId());

        Job tpu = submit("{\"jobType\":\"log\",\"payload\":{}," +
                "\"requiredCapabilities\":[\"tpu\"]}");
        Thread.sleep(2000);
        assertThat(getJob(tpu.id()).status()).as("no tpu-capable worker exists")
                .isIn(JobStatus.SCHEDULED, JobStatus.QUEUED);

        Job gpu = submit("{\"jobType\":\"log\",\"payload\":{}," +
                "\"requiredCapabilities\":[\"gpu\"]}");
        Await.until(() -> getJob(gpu.id()), j -> j.status() == JobStatus.COMPLETED, 15_000);
    }

    @Test
    void jobTypeConcurrencyCapHolds() {
        jdbc.update("INSERT INTO job_type_limits (job_type, max_concurrent) VALUES ('slow', 1)");
        var tenant = JobsController.DEFAULT_TENANT;

        Job[] jobs = new Job[3];
        for (int i = 0; i < 3; i++) {
            jobs[i] = submit("{\"jobType\":\"slow\",\"payload\":{\"ms\":3000},\"tenantId\":\"" + tenant + "\"}");
        }
        int[] maxObserved = {0};
        Await.until(() -> {
            Integer running = jdbc.queryForObject("""
                    SELECT count(*) FROM job_executions e JOIN jobs j ON j.id = e.job_id
                    WHERE e.status = 'RUNNING' AND j.job_type = 'slow'
                    """, Integer.class);
            if (running != null && running > maxObserved[0]) maxObserved[0] = running;
            boolean allDone = true;
            for (Job j : jobs) {
                if (getJob(j.id()).status() != JobStatus.COMPLETED) allDone = false;
            }
            return allDone;
        }, done -> done, 30_000);
        assertThat(maxObserved[0]).as("type cap must hold").isEqualTo(1);
    }

    @Test
    void tenantConcurrencyCapHolds() {
        var tenant = tenant();
        jdbc.update("""
                INSERT INTO tenant_quotas (tenant_id, max_concurrent_executions)
                VALUES (?, 1)
                """, tenant);

        Job[] jobs = new Job[3];
        for (int i = 0; i < 3; i++) {
            jobs[i] = submit("{\"jobType\":\"slow\",\"payload\":{\"ms\":2500},\"tenantId\":\"" + tenant + "\"}");
        }
        int[] maxObserved = {0};
        Await.until(() -> {
            Integer running = jdbc.queryForObject("""
                    SELECT count(*) FROM job_executions e
                    WHERE e.status = 'RUNNING' AND e.job_id IN
                      (SELECT id FROM jobs WHERE tenant_id = ?)
                    """, Integer.class, tenant);
            if (running != null && running > maxObserved[0]) maxObserved[0] = running;
            boolean allDone = true;
            for (Job j : jobs) {
                if (getJob(j.id()).status() != JobStatus.COMPLETED) allDone = false;
            }
            return allDone;
        }, done -> done, 30_000);
        assertThat(maxObserved[0]).as("tenant cap must hold").isEqualTo(1);
    }

    @Test
    void backlogQuotaRejectsWithBackpressureSignal() {
        var tenant = tenant();
        jdbc.update("""
                INSERT INTO tenant_quotas (tenant_id, max_pending_jobs)
                VALUES (?, 1)
                """, tenant);

        // occupies the single pending slot until cancelled
        Job blocker = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":60000}," +
                "\"tenantId\":\"" + tenant + "\"}");
        Await.until(() -> getJob(blocker.id()), j -> j.status() == JobStatus.RUNNING, 15_000);

        ResponseEntity<String> rejected = http.postForEntity("/v1/jobs",
                FullFlowIT.jsonBody("{\"jobType\":\"log\",\"payload\":{},\"tenantId\":\"" + tenant + "\"}"),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();

        jdbc.update("DELETE FROM tenant_quotas WHERE tenant_id = ?", tenant);
        ResponseEntity<Job> accepted = http.postForEntity("/v1/jobs",
                FullFlowIT.jsonBody("{\"jobType\":\"log\",\"payload\":{},\"tenantId\":\"" + tenant + "\"}"),
                Job.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        http.postForEntity("/v1/jobs/" + blocker.id() + "/cancel", null, Job.class);
    }

    @Test
    void drainingWorkerReceivesNoNewWork() throws Exception {
        jdbc.update("UPDATE workers SET status = 'DRAINING' WHERE id = ?", worker.workerId());
        try {
            Job job = submit("{\"jobType\":\"log\",\"payload\":{}}");
            Thread.sleep(2000);
            assertThat(getJob(job.id()).status())
                    .as("draining worker must not receive claims")
                    .isIn(JobStatus.SCHEDULED, JobStatus.QUEUED);
            jdbc.update("UPDATE workers SET status = 'HEALTHY' WHERE id = ?", worker.workerId());
            Await.until(() -> getJob(job.id()), j -> j.status() == JobStatus.COMPLETED, 15_000);
        } finally {
            jdbc.update("UPDATE workers SET status = 'HEALTHY' WHERE id = ?", worker.workerId());
        }
    }
}
