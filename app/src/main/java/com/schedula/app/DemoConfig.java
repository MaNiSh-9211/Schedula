package com.schedula.app;

import com.schedula.persistence.JobStore;
import com.schedula.persistence.ScheduleStore;
import com.schedula.persistence.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

@Configuration
@Profile("demo")
public class DemoConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoConfig.class);
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";

    @Bean
    ApplicationRunner seedDemoData(
            JdbcTemplate jdbc,
            JobStore jobStore,
            ScheduleStore scheduleStore,
            WorkflowStore workflowStore
    ) {
        return args -> {
            log.info("=== SEEDING DEMO DATA ===");

            // Seed sample jobs
            insertJob(jdbc, "log", "{\"msg\":\"demo immediate job\"}", null, 3, "{}", "default", null);
            insertJob(jdbc, "log", "{\"msg\":\"demo delayed 30s\"}", 
                java.time.Instant.now().plusSeconds(30), 3, "{}", "default", null);
            insertJob(jdbc, "echo", "{\"demo\":\"echo test\"}", null, 3, "{}", "default", null);
            insertJob(jdbc, "sleep", "{\"ms\":2000}", null, 1, "{}", "default", null);
            insertJob(jdbc, "fail-test", "{\"fail\":true}", null, 1, "{}", "default", null);

            // Seed workflow definition
            insertWorkflow(jdbc, "demo-order-flow", """
                {"tasks":[
                  {"key":"fetch","jobType":"log","payload":{"step":"fetch-order","orderId":"DEMO-001"}},
                  {"key":"validate","jobType":"log","payload":{"step":"validate"},"dependsOn":["fetch"]},
                  {"key":"process","jobType":"sleep","payload":{"ms":500},"dependsOn":["validate"]},
                  {"key":"notify","jobType":"echo","payload":{"notified":true},"dependsOn":["process"]}
                ]}
                """);

            // Seed schedule
            insertSchedule(jdbc, "demo-heartbeat", "log", "{\"msg\":\"heartbeat\"}", 30000L);

            log.info("=== DEMO DATA SEEDED ===");
        };
    }

    private void insertJob(JdbcTemplate jdbc, String jobType, String payload,
                           java.time.Instant scheduledFor, int maxAttempts,
                           String retryPolicy, String queueName, String webhookUrl) {
        try {
            jdbc.update("""
                INSERT INTO jobs (id, tenant_id, job_type, status, payload_json,
                    max_attempts, retry_policy_json, timeout_ms, scheduled_for,
                    queue_name, webhook_url)
                VALUES (?, ?::uuid, ?, 'SCHEDULED', ?::jsonb, ?, ?::jsonb, 60000, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(), DEFAULT_TENANT, jobType, payload,
                maxAttempts, retryPolicy,
                scheduledFor == null ? null : java.sql.Timestamp.from(scheduledFor),
                queueName, webhookUrl);
        } catch (Exception e) {
            log.debug("Job seed skipped: {}", e.getMessage());
        }
    }

    private void insertWorkflow(JdbcTemplate jdbc, String name, String definition) {
        try {
            UUID wfId = UUID.randomUUID();
            UUID verId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflows (id, tenant_id, name) VALUES (?, ?::uuid, ?)
                ON CONFLICT (tenant_id, name) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, wfId, UUID.fromString(DEFAULT_TENANT), name);
            jdbc.update("""
                INSERT INTO workflow_versions (id, workflow_id, version, definition)
                VALUES (?, ?, 1, ?::jsonb)
                ON CONFLICT (workflow_id, version) DO NOTHING
                """, verId, wfId, definition);
            log.info("Seeded workflow '{}' v1", name);
        } catch (Exception e) {
            log.debug("Workflow seed skipped: {}", e.getMessage());
        }
    }

    private void insertSchedule(JdbcTemplate jdbc, String name, String jobType,
                                String payload, long intervalMs) {
        try {
            jdbc.update("""
                INSERT INTO job_schedules (id, tenant_id, name, job_type, payload_json,
                    kind, interval_ms, timezone, missed_policy, next_fire_at)
                VALUES (?, ?::uuid, ?, ?, ?::jsonb, 'FIXED_INTERVAL', ?, 'UTC', 'COALESCE',
                    now() + (? * interval '1 millisecond'))
                ON CONFLICT (tenant_id, name) DO NOTHING
                """,
                UUID.randomUUID(), UUID.fromString(DEFAULT_TENANT), name, jobType,
                payload, intervalMs, intervalMs);
        } catch (Exception e) {
            log.debug("Schedule seed skipped: {}", e.getMessage());
        }
    }
}
