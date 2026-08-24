package com.schedula.api;

import com.schedula.coordination.Coordinator;
import com.schedula.coordination.SchedulerLeaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only fleet views for operators and the CLI. */
@RestController
@RequestMapping("/v1")
public class FleetController {

    private final JdbcTemplate jdbc;
    private final Coordinator coordinator;
    private final SchedulerLeaseStore leases;

    public FleetController(JdbcTemplate jdbc, Coordinator coordinator, SchedulerLeaseStore leases) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.leases = leases;
    }

    @GetMapping("/fingerprints")
    List<Map<String, Object>> fingerprints() {
        return jdbc.queryForList("SELECT * FROM job_fingerprints ORDER BY total_24h DESC");
    }

    @GetMapping("/workers")
    List<Map<String, Object>> workers() {
        return jdbc.queryForList("""
                SELECT id, name, version, capabilities, max_concurrency, running_count,
                       status, last_heartbeat_at
                FROM workers ORDER BY registered_at
                """);
    }

    @GetMapping("/queues")
    List<Map<String, Object>> queues() {
        return jdbc.queryForList("""
                SELECT queue_name,
                       count(*) FILTER (WHERE status = 'READY') AS ready,
                       count(*) FILTER (WHERE status = 'CLAIMED') AS claimed,
                       count(*) FILTER (WHERE status = 'DEADLETTERED') AS deadlettered,
                       max(enqueued_at) FILTER (WHERE status = 'READY') AS oldest_ready
                FROM queue_messages GROUP BY queue_name ORDER BY queue_name
                """);
    }

    @GetMapping("/schedulers")
    Map<String, Object> schedulers() {
        var nodes = jdbc.queryForList("""
                SELECT node_id, host, port, version, started_at, last_seen_at,
                       last_seen_at < now() - interval '30 seconds' AS stale
                FROM scheduler_nodes ORDER BY started_at
                """);
        var lease = leases.current()
                .<Map<String, Object>>map(s -> Map.of(
                        "ownerNodeId", s.ownerNodeId().toString(),
                        "fencingToken", s.fencingToken()))
                .orElse(Map.of("ownerNodeId", "none"));
        return Map.of(
                "nodes", nodes,
                "leader", lease,
                "thisNode", coordinator.nodeId().toString(),
                "thisNodeBelievesItLeads", coordinator.isLeader());
    }
}
