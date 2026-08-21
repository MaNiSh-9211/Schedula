package com.schedula.coordination;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * One coordinator per scheduler process. Drives the FOLLOWER/LEADER lifecycle:
 * <p>
 * - followers probe and try to acquire;
 * - leaders renew at lease/3; a failed renewal or any fenced-write rejection steps down;
 * - membership heartbeat rides the same loop.
 * <p>
 * This is a lease-based election, NOT consensus (ADR-005): during a database partition
 * there is NO leader (fail closed) rather than two.
 */
@Service
public class Coordinator {

    public record Session(UUID nodeId, long fencingToken) {
    }

    private static final Logger log = LoggerFactory.getLogger(Coordinator.class);

    private final SchedulerLeaseStore leases;
    private final UUID nodeId = UUID.randomUUID();
    private final long leaseMs;
    private final Counter leaderChanges;
    private volatile long currentToken;

    public Coordinator(SchedulerLeaseStore leases, MeterRegistry meters,
                       @Value("${schedula.coordinator.lease-ms:15000}") long leaseMs,
                       @Value("${schedula.coordinator.node-port:8080}") Integer nodePort) {
        this.leases = leases;
        this.leaseMs = leaseMs;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        final String fHost = host;
        leases.registerNode(nodeId, fHost, nodePort, "phase3");
        this.leaderChanges = Counter.builder("schedula_leader_changes_total")
                .tag("node", nodeId.toString())
                .register(meters);
        meters.gauge("schedula_is_leader", this, c -> isLeader() ? 1 : 0);
    }

    /**
     * One coordination step; called on the probe interval by the lifecycle loop.
     * Returns the active session when leading, null when following.
     * <p>
     * Renewal failure does not end the attempt: an expired lease is free for anyone
     * INCLUDING its previous owner to re-acquire (ADR-003). The fresh fencing token
     * makes any write from the lapsed interval inert, so falling through to
     * re-acquisition is safe and avoids one probe-interval of needless downtime.
     */
    public synchronized Session step() {
        if (currentToken > 0) {
            Optional<Long> renewed = leases.renew(nodeId, currentToken, leaseMs);
            if (renewed.isPresent()) {
                return session();
            }
            log.warn("[coordinator] lease lapsed; attempting immediate re-acquisition");
            currentToken = 0;
        }
        return leases.tryAcquire(nodeId, leaseMs)
                .map(token -> {
                    currentToken = token;
                    leaderChanges.increment();
                    log.info("[coordinator] acquired leadership with fencing token {}", token);
                    return session();
                })
                .orElse(null);
    }

    /** Called by writers when a fenced write matched zero rows: authority is gone. */
    public synchronized void stepDown() {
        if (currentToken > 0) {
            log.warn("[coordinator] fenced write rejected; stepping down");
            currentToken = 0;
        }
    }

    public boolean isLeader() {
        return currentToken > 0;
    }

    public long fencingToken() {
        return currentToken;
    }

    public UUID nodeId() {
        return nodeId;
    }

    private Session session() {
        leases.heartbeatNode(nodeId);
        return new Session(nodeId, currentToken);
    }

}
