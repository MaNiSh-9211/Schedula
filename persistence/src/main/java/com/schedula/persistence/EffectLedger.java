package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deduplication primitive for external side effects (ADR-002). Claim-then-run ordering:
 * the effect row is inserted first; a crash between claim and effect means the effect is
 * SKIPPED on redelivery (at-most-once for that effect) — never duplicated. Handlers whose
 * effects must not be lost should use run-first semantics instead and accept duplicates.
 */
@Repository
public class EffectLedger {

    private final JdbcTemplate jdbc;

    public EffectLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Once(boolean firstRun, String resultJson) {
    }

    public Once once(UUID tenantId, UUID jobId, String effectKey, Supplier<String> effect) {
        int claimed = jdbc.update("""
                        INSERT INTO effect_records (tenant_id, job_id, effect_key, result_json)
                        VALUES (?, ?, ?, NULL)
                        ON CONFLICT (tenant_id, job_id, effect_key) DO NOTHING
                        """,
                tenantId, jobId, effectKey);
        if (claimed == 0) {
            String prior = jdbc.queryForObject(
                    "SELECT result_json::text FROM effect_records WHERE tenant_id = ? AND job_id = ? AND effect_key = ?",
                    String.class, tenantId, jobId, effectKey);
            return new Once(false, prior);
        }
        String result = effect.get();
        jdbc.update("""
                        UPDATE effect_records SET result_json = ?::jsonb
                        WHERE tenant_id = ? AND job_id = ? AND effect_key = ?
                        """, result, tenantId, jobId, effectKey);
        return new Once(true, result);
    }
}
