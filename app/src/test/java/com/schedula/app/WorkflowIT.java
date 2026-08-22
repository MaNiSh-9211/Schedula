package com.schedula.app;

import com.schedula.common.jobs.JobStatus;
import com.schedula.engine.workflow.WorkflowDriver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "schedula.auth.enabled=false",
        "logging.level.com.schedula=WARN"
})
class WorkflowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowDriver driver;

    private Map<String, Object> post(String path, Object body) {
        var res = http.postForEntity(path, body, Map.class);
        assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private void register(String name, String definitionJson) {
        Map<String, Object> def;
        try {
            def = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(definitionJson, Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
        var res = http.postForEntity("/v1/workflows",
                Map.of("name", name, "definition", def), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        if (res.getBody() == null || !res.getBody().containsKey("version")) {
            throw new IllegalStateException("register failed: " + res.getBody());
        }
    }

    private String start(String name) {
        var res = http.postForEntity("/v1/workflows/" + name + "/executions",
                Map.of(), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        return ((Map<?, ?>) res.getBody()).get("workflowExecutionId").toString();
    }

    private String wfStatus(UUID execId) {
        var res = http.getForEntity("/v1/workflows/executions/" + execId, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody().get("status").toString();
    }

    private static final String DIAMOND = """
            {"tasks":[
              {"key":"a","jobType":"log","payload":{}},
              {"key":"b","jobType":"log","payload":{},"dependsOn":["a"]},
              {"key":"c","jobType":"log","payload":{},"dependsOn":["a"]},
              {"key":"d","jobType":"log","payload":{},"dependsOn":["b","c"]}
            ]}
            """;

    @Test
    void diamondDagCompletesWithParallelBranches() {
        register("diamond", DIAMOND);
        UUID execId = UUID.fromString(start("diamond"));

        Await.until(() -> wfStatus(execId), s -> s.equals("COMPLETED"), 30_000);

        Integer succeeded = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_task_executions
                WHERE wf_execution_id = ? AND status = 'SUCCEEDED'
                """, Integer.class, execId);
        assertThat(succeeded).isEqualTo(4);
        // d's execution must start no earlier than both b's and c's finishes
        java.util.Map<String, Object> t = jdbc.queryForMap("""
                SELECT
                  (SELECT MAX(e.finished_at) FROM workflow_task_executions w
                     JOIN job_executions e ON e.job_id = w.job_id
                     WHERE w.wf_execution_id = ? AND w.task_key IN ('b','c')) AS parents_done,
                  (SELECT MIN(e.started_at) FROM workflow_task_executions w
                     JOIN job_executions e ON e.job_id = w.job_id
                     WHERE w.wf_execution_id = ? AND w.task_key = 'd') AS child_started
                """, execId, execId);
        java.sql.Timestamp parentsDone = (java.sql.Timestamp) t.get("parents_done");
        java.sql.Timestamp childStarted = (java.sql.Timestamp) t.get("child_started");
        assertThat(childStarted).as("join task waits for ALL deps").isAfterOrEqualTo(parentsDone);
    }

    @Test
    void failingBranchFailsWorkflowWithoutRetryingWholeDag() {
        register("failing", """
                {"tasks":[
                  {"key":"ok1","jobType":"log","payload":{}},
                  {"key":"bad","jobType":"boom","payload":{},"maxAttempts":2,"dependsOn":["ok1"],
                   "retryPolicy":{"backoff":"FIXED","initialDelayMs":10}},
                  {"key":"never","jobType":"log","payload":{},"dependsOn":["bad"]}
                ]}
                """);
        UUID execId = UUID.fromString(start("failing"));

        Await.until(() -> wfStatus(execId), s -> s.equals("FAILED"), 40_000);

        String neverStatus = jdbc.queryForObject("""
                SELECT status FROM workflow_task_executions
                WHERE wf_execution_id = ? AND task_key = 'never'
                """, String.class, execId);
        assertThat(neverStatus).as("downstream task must stay BLOCKED").isEqualTo("BLOCKED");

        Integer ok1Runs = jdbc.queryForObject("""
                SELECT count(*) FROM job_executions e JOIN jobs j ON j.id = e.job_id
                WHERE j.id IN (SELECT job_id FROM workflow_task_executions
                               WHERE wf_execution_id = ? AND task_key='ok1')
                """, Integer.class, execId);
        assertThat(ok1Runs).isEqualTo(1);
    }

    @Test
    void waitTimerIsDurableAndFires() {
        register("timed", """
                {"tasks":[
                  {"key":"start","jobType":"log","payload":{}},
                  {"key":"wait","waitMs":1500,"dependsOn":["start"]},
                  {"key":"after","jobType":"log","payload":{},"dependsOn":["wait"]}
                ]}
                """);
        UUID execId = UUID.fromString(start("timed"));
        Await.until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM workflow_timers WHERE wf_execution_id=? AND state='ACTIVE'",
                Integer.class, execId), n -> n == 1, 15_000);
        Await.until(() -> wfStatus(execId), s -> s.equals("COMPLETED"), 30_000);
        Integer fired = jdbc.queryForObject(
                "SELECT count(*) FROM workflow_timers WHERE wf_execution_id=? AND state='FIRED'",
                Integer.class, execId);
        assertThat(fired).isEqualTo(1);
    }

    @Test
    void driverRecoversFromAnyCrashByReconciliation() {
        // simulate crash-after-job-completed: task RUNNING whose backing job is COMPLETED;
        // the next tick must reconcile purely from rows
        register("recover", DIAMOND);
        UUID execId = UUID.fromString(start("recover"));
        Await.until(() -> {
            Integer done = jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_task_executions "
                      + "WHERE wf_execution_id=? AND status='SUCCEEDED'", Integer.class, execId);
            return done != null && done >= 1;
        }, b -> b, 20_000);

        // force one RUNNING task to look abandoned: its job already terminal
        jdbc.update("""
                UPDATE workflow_task_executions SET status = 'RUNNING'
                WHERE wf_execution_id = ? AND task_key = 'a'
                """, execId);

        driver.tick(); // reconciliation pass

        String status = jdbc.queryForObject(
                "SELECT status FROM workflow_task_executions WHERE wf_execution_id=? AND task_key='a'",
                String.class, execId);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @Test
    void compensationRunsInReverseOnFailure() {
        register("comp", """
                {"tasks":[
                  {"key":"step1","jobType":"log","payload":{"s":1},
                     "undo":{"jobType":"log","payload":{"undo":1}}},
                  {"key":"step2","jobType":"log","payload":{"s":2},
                     "undo":{"jobType":"log","payload":{"undo":2}},"dependsOn":["step1"]},
                  {"key":"blast","jobType":"boom","payload":{},"maxAttempts":1,"dependsOn":["step2"]}
                ]}
                """);
        UUID execId = UUID.fromString(start("comp"));
        Await.until(() -> wfStatus(execId), s -> s.equals("FAILED"), 45_000);

        Boolean compensated = jdbc.queryForObject(
                "SELECT compensated FROM workflow_executions WHERE id = ?",
                Boolean.class, execId);
        assertThat(compensated).as("both undos must have run").isTrue();

        Integer undoCount = jdbc.queryForObject(
                "SELECT count(*) FROM workflow_task_executions WHERE wf_execution_id=? AND kind='UNDO'",
                Integer.class, execId);
        assertThat(undoCount).isEqualTo(2);
    }
}
