package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Append-only administrative audit trail (SECURITY.md §4). No update/delete paths exist. */
@Repository
public class AuditStore {

    private final JdbcTemplate jdbc;

    public AuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String actor, UUID tenantId, String action, String targetType,
                       String targetId, String detailJson) {
        jdbc.update("""
                        INSERT INTO audit_events (actor, tenant_id, action, target_type, target_id, detail)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """,
                actor, tenantId, action, targetType, targetId,
                detailJson == null ? "{}" : detailJson);
    }
}
