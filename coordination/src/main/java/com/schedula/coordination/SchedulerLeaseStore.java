package com.schedula.coordination;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL lease row is the single leadership truth (ADR-005).
 * <p>
 * Acquire/renew are single-statement CAS operations; every grant mints a fresh,
 * monotonically increasing fencing token inside the same statement, so tokens are
 * ordered exactly like ownership under PostgreSQL's serial execution of the row update.
 */
@Repository
public class SchedulerLeaseStore {

    public static final String LEASE_RESOURCE = "SCHEDULER_LEADER";
    public static final String FENCE_RESOURCE = "SCHEDULER_LEADER";

    private final JdbcTemplate jdbc;

    public SchedulerLeaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Try to become leader. Succeeds when the lease is missing, expired, or already ours
     * (re-acquisition after a lapse). Each success burns one fence counter increment.
     */
    /**
     * Try to become leader. Succeeds when the lease is missing, expired, or already ours
     * (re-acquisition after a lapse). Two autocommit steps:
     *  1. guarded takeover of an existing row (expired or ours) — row-lock CAS;
     *  2. fresh insert if no row exists (DO NOTHING loses benign creation races).
     * Each success mints a fresh fencing token; failed attempts may burn a counter tick,
     * which is harmless since only token ORDER matters (ADR-004).
     */
    public Optional<Long> tryAcquire(UUID nodeId, long leaseMs) {
        List<Long> tookOver = jdbc.query("""
                        WITH tok AS (
                            UPDATE fence_counters SET counter = counter + 1
                            WHERE resource_name = ?
                            RETURNING counter
                        )
                        UPDATE scheduler_leases sl
                        SET owner_node_id = ?, expires_at = now() + (? * interval '1 millisecond'),
                            acquired_at = now(), fencing_token = tok.counter
                        FROM tok
                        WHERE sl.resource_name = ?
                          AND (sl.owner_node_id = ? OR sl.expires_at < now())
                        RETURNING sl.fencing_token
                        """,
                (rs, i) -> rs.getLong("fencing_token"),
                FENCE_RESOURCE, nodeId, (double) leaseMs, LEASE_RESOURCE, nodeId);
        if (!tookOver.isEmpty()) {
            return tookOver.stream().findFirst();
        }
        List<Long> inserted = jdbc.query("""
                        WITH tok AS (
                            UPDATE fence_counters SET counter = counter + 1
                            WHERE resource_name = ?
                            RETURNING counter
                        )
                        INSERT INTO scheduler_leases (resource_name, owner_node_id, expires_at, fencing_token)
                        SELECT ?, ?, now() + (? * interval '1 millisecond'), tok.counter FROM tok
                        ON CONFLICT (resource_name) DO NOTHING
                        RETURNING fencing_token
                        """,
                (rs, i) -> rs.getLong("fencing_token"),
                FENCE_RESOURCE, LEASE_RESOURCE, nodeId, (double) leaseMs);
        return inserted.stream().findFirst();
    }

    /** Extend our own live lease; empty result means we no longer own leadership. */
    public Optional<Long> renew(UUID nodeId, long currentToken, long leaseMs) {
        List<Long> tokens = jdbc.query("""
                        UPDATE scheduler_leases SET expires_at = now() + (? * interval '1 millisecond')
                        WHERE resource_name = ? AND owner_node_id = ? AND fencing_token = ?
                          AND expires_at > now()
                        RETURNING fencing_token
                        """,
                (rs, i) -> rs.getLong("fencing_token"),
                (double) leaseMs, LEASE_RESOURCE, nodeId, currentToken);
        return tokens.stream().findFirst();
    }

    public record LeaseState(UUID ownerNodeId, long fencingToken) {
    }

    public Optional<LeaseState> current() {
        return jdbc.query("""
                        SELECT owner_node_id, fencing_token FROM scheduler_leases
                        WHERE resource_name = ? AND expires_at > now()
                        """,
                (rs, i) -> {
                    Object o = rs.getObject("owner_node_id");
                    UUID owner = o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
                    return new LeaseState(owner, rs.getLong("fencing_token"));
                },
                LEASE_RESOURCE).stream().findFirst();
    }

    // --- membership -----------------------------------------------------------

    public void registerNode(UUID nodeId, String host, Integer port, String version) {
        jdbc.update("""
                        INSERT INTO scheduler_nodes (node_id, host, port, version)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (node_id) DO UPDATE SET
                            host = EXCLUDED.host, port = EXCLUDED.port,
                            version = EXCLUDED.version, last_seen_at = now()
                        """,
                nodeId, host, port, version);
    }

    public void heartbeatNode(UUID nodeId) {
        jdbc.update("UPDATE scheduler_nodes SET last_seen_at = now() WHERE node_id = ?", nodeId);
    }
}


