package com.schedula.app;

import com.schedula.api.JobsController;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.engine.RetentionService;
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

// Runs against its own container (retention property differs from sibling ITs).
// The -Xmx768m failsafe fork cap keeps multi-context runs within budget.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "schedula.retention.terminal-job-hours=0",
        "logging.level.com.schedula=WARN",
        "schedula.auth.enabled=false"
})
@org.springframework.context.annotation.Import(ReliabilityIT.TestHandlers.class)
class RetentionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionService retention;

    private Job submit(String body) {
        ResponseEntity<Job> res = http.postForEntity("/v1/jobs", FullFlowIT.jsonBody(body), Job.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private Job getJob(UUID id) {
        return http.getForEntity("/v1/jobs/" + id, Job.class).getBody();
    }

    @Test
    void terminalHistoryIsPurgedFreshJobsKept() {
        Job doomed = submit("{\"jobType\":\"log\",\"payload\":{}}");
        Await.until(() -> getJob(doomed.id()), j -> j.status() == JobStatus.COMPLETED, 15_000);

        // default retention is 720h; backdate far past it (10 years)
        jdbc.update("UPDATE jobs SET updated_at = now() - interval '87600 hours' WHERE id = ?",
                doomed.id());
        jdbc.update("""
                UPDATE job_events SET occurred_at = now() - interval '87600 hours'
                WHERE job_id = ?
                """, doomed.id());

        // non-terminal control job: must survive the purge regardless of age rules
        Job fresh = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":60000}}");
        Await.until(() -> getJob(fresh.id()), j -> j.status() == JobStatus.RUNNING, 15_000);

        retention.run();

        Integer gone = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE id = ?", Integer.class, doomed.id());
        assertThat(gone).as("ancient terminal job must be purged").isZero();
        Integer eventsGone = jdbc.queryForObject(
                "SELECT count(*) FROM job_events WHERE job_id = ?", Integer.class, doomed.id());
        assertThat(eventsGone).isZero();
        Integer msgsGone = jdbc.queryForObject(
                "SELECT count(*) FROM queue_messages WHERE job_id = ?", Integer.class, doomed.id());
        assertThat(msgsGone).isZero();

        assertThat(getJob(fresh.id()).status()).as("live job must survive").isNotNull();

        http.postForEntity("/v1/jobs/" + fresh.id() + "/cancel", null, Job.class);
    }

    @Test
    void weightedTenantsShareTheWorker() {
        // tenant heavy: weight 4, floods 8 slow jobs; tenant light: weight 1, 1 slow job.
        // fair dispatch must START light's job before all of heavy's finish.
        var heavy = UUID.randomUUID();
        var light = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, weight) VALUES (?, 'heavy', 4)", heavy);
        jdbc.update("INSERT INTO tenants (id, name, weight) VALUES (?, 'light', 1)", light);

        for (int i = 0; i < 8; i++) {
            submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":4000},\"tenantId\":\"" + heavy + "\"}");
        }
        long submitNanos = System.nanoTime();
        Job lightJob = submit("{\"jobType\":\"sleep\",\"payload\":{\"ms\":100},\"tenantId\":\"" + light + "\"}");

        // with 8 concurrency and WRR(4:1), light's single job starts within its first cycles
        Await.until(() -> getJob(lightJob.id()), j -> j.status() == JobStatus.COMPLETED, 15_000);
        double waitSeconds = (System.nanoTime() - submitNanos) / 1_000_000_000.0;

        // strict FIFO would put it behind >=5 four-second jobs (>20s); fairness bounds it
        assertThat(waitSeconds).as("weighted fair dispatch bounds small-tenant wait")
                .isLessThan(12.0);
    }
}

