package com.schedula.engine;

import com.schedula.coordination.Coordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * Completion webhooks (industry parity). Terminal jobs carrying webhook_url are
 * delivered at-least-once with HMAC-signed payloads and capped retries:
 *   state NONE   -> job just reached a terminal state
 *   PENDING      -> awaiting delivery / retrying (updated_at acts as backoff marker)
 *   DELIVERED    -> 2xx received
 *   FAILED       -> retries exhausted (visible in DLQ-style ops queries)
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final Coordinator coordinator;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Counter delivered;
    private final Counter failedFinal;

    public WebhookDispatcher(JdbcTemplate jdbc, Coordinator coordinator, MeterRegistry meters,
                             @Value("${schedula.webhook.secret:schedula-dev-secret}") String secret) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.secret = secret == null ? "" : secret;
        this.delivered = Counter.builder("schedula_webhooks_delivered_total").register(meters);
        this.failedFinal = Counter.builder("schedula_webhooks_failed_final_total").register(meters);
    }

    public void tick() {
        boolean leader = coordinator.isLeader();
        if (!leader) return;
        System.out.println("[webhook-debug] isLeader=true, querying...");
        try {
            List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, tenant_id, job_type, status,
                       webhook_url, webhook_attempts
                FROM jobs
                WHERE webhook_url IS NOT NULL AND (
                       (webhook_state = 'NONE'
                          AND status IN ('COMPLETED','DEAD','CANCELLED','FAILED_TERMINAL'))
                    OR (webhook_state = 'PENDING'
                          AND updated_at < now() - interval '10 seconds'))
                  AND updated_at < now() - interval '5 seconds'
                LIMIT 25
                """);
            System.out.println("[webhook-debug] due=" + due.size());
            for (Map<String, Object> row : due) {
                UUID jobId = (UUID) row.get("id");
                String url = (String) row.get("webhook_url");
                int attempts = ((Number) row.get("webhook_attempts")).intValue();
                String body = payload(row);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Schedula-Event", "job." + row.get("status"))
                        .header("X-Schedula-Signature", "sha256=" + hmac(body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                int code = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
                System.out.println("[webhook-debug] delivered to " + url + " -> " + code);
                if (code >= 200 && code < 300) {
                    jdbc.update("UPDATE jobs SET webhook_state='DELIVERED', updated_at=now() WHERE id=?", jobId);
                    delivered.increment();
                } else if (attempts + 1 >= MAX_ATTEMPTS) {
                    jdbc.update("""
                            UPDATE jobs SET webhook_state='FAILED', webhook_attempts=webhook_attempts+1,
                                updated_at=now() WHERE id=?""", jobId);
                    failedFinal.increment();
                } else {
                    jdbc.update("""
                            UPDATE jobs SET webhook_state='PENDING', webhook_attempts=webhook_attempts+1,
                                updated_at=now() WHERE id=?""", jobId);
                }
            }
        } catch (Exception e) {
            System.out.println("[webhook-debug] ERROR: " + e);
            log.warn("webhook dispatch error: {}", e.toString());
        }
    }

    private String payload(Map<String, Object> row) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "event", "job." + row.get("status"),
                    "jobId", String.valueOf(row.get("id")),
                    "tenantId", String.valueOf(row.get("tenant_id")),
                    "jobType", String.valueOf(row.get("job_type")),
                    "result", ""));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}



