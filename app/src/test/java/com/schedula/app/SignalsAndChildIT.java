package com.schedula.app;

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
@org.springframework.context.annotation.Import(ReliabilityIT.TestHandlers.class)
class SignalsAndChildIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private void register(String name, String def) {
        Map<String, Object> parsed;
        try { parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(def, Map.class); }
        catch (Exception e) { throw new RuntimeException(e); }
        var res = http.postForEntity("/v1/workflows", Map.of("name", name, "definition", parsed), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String start(String name) {
        var res = http.postForEntity("/v1/workflows/" + name + "/executions", Map.of(), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        return res.getBody().get("workflowExecutionId").toString();
    }

    private String startDebug(String name) {
        var res = http.postForEntity("/v1/workflows/" + name + "/executions", Map.of(), Map.class);
        System.out.println("[debug] start(" + name + ") status=" + res.getStatusCode() + " body=" + res.getBody());
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        return res.getBody().get("workflowExecutionId").toString();
    }

    private String wfStatus(String id) {
        var res = http.getForEntity("/v1/workflows/executions/" + id, Map.class);
        return ((Map<?, ?>) res.getBody()).get("status").toString();
    }

    @Test
    void signalUnblocksWaitingTask() {
        register("sig-flow", """
                {"tasks":[
                  {"key":"pre","jobType":"log","payload":{}},
                  {"key":"wait-for-signal","signal":"approve","dependsOn":["pre"]},
                  {"key":"after","jobType":"log","payload":{},"dependsOn":["wait-for-signal"]}
                ]}
                """);
        String execId = startDebug("sig-flow");

        // give driver time to create tasks and reach the signal gate
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // deliver the signal via API
        var res = http.postForEntity("/v1/workflows/executions/" + execId + "/signals",
                Map.of("signal", "approve", "payload", Map.of("approvedBy", "admin")), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);

        Await.until(() -> wfStatus(execId), s -> s.equals("COMPLETED"), 20_000);
    }

    @Test
    void childWorkflowSpawnsAndCompletes() {
        register("child-inner", """
                {"tasks":[{"key":"inner-task","jobType":"log","payload":{"inner":true}}]}
                """);
        register("parent", """
                {"tasks":[
                  {"key":"before","jobType":"log","payload":{}},
                  {"key":"spawn-child","childWorkflow":{"name":"child-inner"},"dependsOn":["before"]},
                  {"key":"after-child","jobType":"log","payload":{},"dependsOn":["spawn-child"]}
                ]}
                """);
        String execId = startDebug("parent");

        Await.until(() -> wfStatus(execId), s -> s.equals("COMPLETED"), 30_000);

        // child execution should have been created and completed
        Integer childCount = jdbc.queryForObject(
                "SELECT count(*) FROM workflow_executions WHERE status='COMPLETED' " +
                  "AND id != ?::uuid", Integer.class, UUID.fromString(execId));
        assertThat(childCount).isGreaterThanOrEqualTo(1);
    }
}

