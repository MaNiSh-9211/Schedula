package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class WorkerStore {

    private final JdbcTemplate jdbc;

    public WorkerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void register(UUID workerId, String name, String appVersion, int maxConcurrency,
                         java.util.List<String> capabilities, int cpuCapacity, long memCapacityMb) {
        jdbc.update("""
                        INSERT INTO workers (id, name, version, max_concurrency, capabilities,
                            cpu_capacity, mem_capacity_mb, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'HEALTHY')
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name,
                            version = EXCLUDED.version,
                            max_concurrency = EXCLUDED.max_concurrency,
                            capabilities = EXCLUDED.capabilities,
                            cpu_capacity = EXCLUDED.cpu_capacity,
                            mem_capacity_mb = EXCLUDED.mem_capacity_mb,
                            status = 'HEALTHY',
                            last_heartbeat_at = now()
                        """,
                workerId, name, appVersion, maxConcurrency,
                capabilities == null ? new String[0] : capabilities.toArray(new String[0]),
                cpuCapacity, memCapacityMb);
    }

    public void heartbeat(UUID workerId) {
        jdbc.update("UPDATE workers SET last_heartbeat_at = now() WHERE id = ?", workerId);
    }

    public void setDraining(UUID workerId) {
        jdbc.update("UPDATE workers SET status = 'DRAINING' WHERE id = ?", workerId);
    }

    public void deregister(UUID workerId) {
        jdbc.update("DELETE FROM workers WHERE id = ?", workerId);
    }

    /** Capacity admission: increment only while under max_concurrency. Returns false when full. */
    public boolean tryAcquireSlot(UUID workerId) {
        Integer updated = jdbc.queryForObject("""
                        WITH moved AS (
                            UPDATE workers SET running_count = running_count + 1
                            WHERE id = ? AND running_count < max_concurrency
                            RETURNING id
                        )
                        SELECT count(*) FROM moved
                        """, Integer.class, workerId);
        return updated != 0;
    }

    public void releaseSlot(UUID workerId) {
        jdbc.update("UPDATE workers SET running_count = GREATEST(running_count - 1, 0) WHERE id = ?",
                workerId);
    }

    public int runningCount(UUID workerId) {
        Integer c = jdbc.queryForObject("SELECT running_count FROM workers WHERE id = ?",
                Integer.class, workerId);
        return c == null ? 0 : c;
    }

    /** Failure detector (advisory): silence past thresholds degrades status, never leases. */
    public int markUnhealthyPast(long silentMs) {
        return jdbc.update("""
                        UPDATE workers SET status = 'UNHEALTHY'
                        WHERE status = 'HEALTHY'
                          AND last_heartbeat_at < now() - (? * interval '1 millisecond')
                        """, (double) silentMs);
    }

    public int markDeadPast(long silentMs) {
        return jdbc.update("""
                        UPDATE workers SET status = 'DEAD'
                        WHERE status IN ('HEALTHY', 'UNHEALTHY', 'DRAINING')
                          AND last_heartbeat_at < now() - (? * interval '1 millisecond')
                        """, (double) silentMs);
    }
}
