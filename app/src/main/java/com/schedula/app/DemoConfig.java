package com.schedula.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demo mode: seeds sample data and provides a self-referencing webhook receiver
 * so every feature is testable immediately after startup with zero external services.
 *
 * Activate with: --spring.profiles.active=demo
 */
@Configuration
@Profile("demo")
public class DemoConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoConfig.class);

    @Bean
    ApplicationRunner seedDemoData(JdbcTemplate jdbc) {
        return args -> {
            // Built-in webhook receiver endpoint URL (self-referencing)
            String hookUrl = "http://localhost:8080/demo/webhook";

            log.info("=== SEEDING DEMO DATA ===");

            // 1. Demo schedules
            jdbc.update("""
                INSERT INTO job_schedules (id, tenant_id, name, job_type, payload_json,
                    kind, interval_ms, timezone, missed_policy, next_fire_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001', 'demo-heartbeat', 'log',
                    '{"msg":"heartbeat"}'::jsonb, 'FIXED_INTERVAL', 30000, 'UTC', 'COALESCE',
                    now() + interval '30 seconds')
                ON CONFLICT (tenant_id, name) DO NOTHING
                """, UUID.randomUUID());

            // 2. Demo workflow definition
            String wfDef = """
                {"tasks":[
                  {"key":"fetch","jobType":"echo","payload":{"step":"fetch-order","orderId":"DEMO-001"}},
                  {"key":"validate","jobType":"log","payload":{"step":"validate"},"dependsOn":["fetch"]},
                  {"key":"process","jobType":"sleep","payload":{"ms":500},"dependsOn":["validate"],
                     "undo":{"jobType":"log","payload":{"rollback":true}}},
                  {"key":"notify","jobType":"echo","payload":{"notified":true},
                     "dependsOn":["process"],
                     "webhookUrl":"%s"}
                ]}
                """.formatted(hookUrl);
            try {
                var def = new ObjectMapper().readTree(wfDef);
                UUID wfId = UUID.randomUUID();
                UUID verId = UUID.randomUUID();
                jdbc.update("INSERT INTO workflows (id, tenant_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        wfId, UUID.fromString("00000000-0000-0000-0000-000000000001"), "demo-order-flow");
                Integer maxV = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version),0) FROM workflow_versions WHERE workflow_id=?",
                        Integer.class, wfId);
                jdbc.update("""
                        INSERT INTO workflow_versions (id, workflow_id, version, definition)
                        VALUES (?, ?, ?, ?::jsonb)
                        """, verId, wfId, (maxV == null ? 0 : maxV) + 1, def.toString());
                log.info("Seeded workflow 'demo-order-flow' v{}", maxV + 1);
            } catch (Exception e) {
                log.warn("Workflow seed skipped: {}", e.getMessage());
            }

            // 3. Sample jobs in various states
            String[] types = {"echo", "log", "echo", "log"};
            for (int i = 0; i < types.length; i++) {
                jdbc.update("""
                        INSERT INTO jobs (id, tenant_id, job_type, status, payload_json,
                            queue_name, webhook_url)
                        VALUES (?, '00000000-0000-0000-0000-000000000001', ?, 'SCHEDULED',
                            ?::jsonb, 'default', ?)
                        """, UUID.randomUUID(), types[i],
                        "{\"demo\":true,\"index\":" + i + "}",
                        i == 0 ? hookUrl : null);
            }

            log.info("=== DEMO DATA SEEDED. Open http://localhost:8080 ===");
        };
    }

    /** Self-referencing webhook receiver — accepts any POST and returns success. */
    @RestController
    @RequestMapping("/demo")
    public static class DemoWebhookReceiver {

        private final java.util.List<Map<String, Object>> received =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @PostMapping("/webhook")
        public Map<String, Object> receive(@RequestBody Map<String, Object> body) {
            received.add(body);
            return Map.of("received", true, "count", received.size());
        }

        @org.springframework.web.bind.annotation.GetMapping("/webhook/received")
        public List<Map<String, Object>> list() {
            return received;
        }
    }
}

