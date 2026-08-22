package com.schedula.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.schedula.common.model.Job;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Industry-parity features end to end: named-queue routing, handler result capture,
 * completion webhooks (signed, retried), and bulk submission.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.scheduler.poll-interval-ms=100",
        "schedula.worker.poll-interval-ms=100",
        "schedula.recovery.sweep-interval-ms=500",
        "schedula.queue.visibility-timeout-ms=4000",
        "schedula.auth.enabled=false",
        "logging.level.com.schedula=WARN"
})
class ParityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private Job submit(String body) {
        var res = http.postForEntity("/v1/jobs", FullFlowIT.jsonBody(body), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return null;
    }

    private String submitForId(String body) {
        var res = http.postForEntity("/v1/jobs", FullFlowIT.jsonBody(body), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").toString();
    }

    private String jobStatus(String id) {
        var res = http.getForEntity("/v1/jobs/" + id, Map.class);
        return ((Map<?, ?>) res.getBody()).get("status").toString();
    }

    @AfterEach
    void resetWorker() {
        jdbc.update("UPDATE workers SET subscribed_queues = '{default}'");
    }

    @Test
    void jobsRouteOnlyToSubscribedQueues() throws Exception {
        jdbc.update("UPDATE workers SET subscribed_queues = '{billing}'");
        try {
            String emailJob = submitForId(
                    "{\"jobType\":\"log\",\"payload\":{},\"queueName\":\"email\"}");
            String billingJob = submitForId(
                    "{\"jobType\":\"log\",\"payload\":{},\"queueName\":\"billing\"}");
            Thread.sleep(2500);
            assertThat(jobStatus(emailJob)).as("unsubscribed queue must not be claimed")
                    .isIn("SCHEDULED", "QUEUED");
            Await.until(() -> jobStatus(billingJob), s -> s.equals("COMPLETED"), 15_000);
        } finally {
            jdbc.update("UPDATE workers SET subscribed_queues = '{default}'");
        }
    }

    @Test
    void handlerResultsAreStoredAndRetrievable() {
        // 'echo' is a built-in handler that emits its payload as the result
        String id = submitForId("{\"jobType\":\"echo\",\"payload\":{\"answer\":42}}");
        Await.until(() -> jobStatus(id), s -> s.equals("COMPLETED"), 15_000);
        String result = jdbc.queryForObject("""
                SELECT result_json::text FROM job_executions
                WHERE job_id = ? AND result_json IS NOT NULL LIMIT 1
                """, String.class, UUID.fromString(id));
        assertThat(result).contains("\"answer\"");
    }

    @Test
    void batchSubmissionCreatesAllJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            jobs.add(Map.of("jobType", "log", "payload", Map.of("i", i)));
        }
        var res = http.postForEntity("/v1/jobs/batch", Map.of("jobs", jobs), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var submitted = (List<?>) ((Map<?, ?>) res.getBody()).get("submitted");
        assertThat(submitted).hasSize(25);
        Integer created = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE payload_json::text LIKE '%\"i\"%' " +
                  "AND created_at > now() - interval '2 minutes'",
                Integer.class);
        assertThat(created).isGreaterThanOrEqualTo(25);
    }

    @Test
    void completionWebhookDeliversSignedPayloadWithRetry() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var signatures = new CopyOnWriteArrayList<String>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            received.add(body);
            signatures.add(exchange.getRequestHeaders().getFirst("X-Schedula-Signature"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            String id = submitForId("{\"jobType\":\"log\",\"payload\":{}," +
                    "\"webhookUrl\":\"http://localhost:" + port + "/hook\"}");
            // job completes -> dispatcher picks up terminal job with webhook_url -> delivers
            Await.until(() -> jdbc.queryForObject(
                    "SELECT webhook_state FROM jobs WHERE id = ?",
                    String.class, UUID.fromString(id)),
                    s -> s.equals("DELIVERED"), 45_000);

            assertThat(received).isNotEmpty();
            assertThat(signatures.get(0)).startsWith("sha256=");
            assertThat(received.get(0)).contains(id);
        } finally {
            server.stop(0);
        }
    }
}



