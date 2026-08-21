package com.schedula.app;

import com.schedula.coordination.Coordinator;
import com.schedula.common.jobs.JobStatus;
import com.schedula.persistence.ScheduleStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHAOS-002 essence: a stale scheduler that still believes it leads cannot mutate state.
 * Every leader write carries an EXISTS check against the live lease; the ex-leader's
 * token no longer matches, so its writes match zero rows.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.roles.scheduler=false",
        "schedula.roles.worker=false"
})
class FencedWritesIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long LEASE_MS = 800;

    @Autowired
    ScheduleStore schedules;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staleLeadersWritesAreRejectedFreshOnesAccepted() throws Exception {
        var nodeA = new Coordinator(new com.schedula.coordination.SchedulerLeaseStore(jdbc),
                new SimpleMeterRegistry(), LEASE_MS, 9081);
        var nodeB = new Coordinator(new com.schedula.coordination.SchedulerLeaseStore(jdbc),
                new SimpleMeterRegistry(), LEASE_MS, 9082);

        long tokenA = nodeA.step().fencingToken();
        assertThat(nodeB.step()).isNull();

        var schedule = schedules.create(new ScheduleStore.Insert(
                com.schedula.api.JobsController.DEFAULT_TENANT,
                "fenced-" + java.util.UUID.randomUUID(), "log", "{}", 60_000, "COALESCE"));

        // leader A dies; B takes over with a strictly higher fencing token
        Thread.sleep(LEASE_MS + 300);
        long tokenB = nodeB.step().fencingToken();
        assertThat(tokenB).isGreaterThan(tokenA);

        Instant next = schedule.nextFireAt().plusSeconds(60);
        Instant now = Instant.now();

        // A's write carries the dead token: rejected by the lease EXISTS predicate
        boolean asStaleLeader = schedules.advanceFire(schedule.id(), schedule.version(),
                next, now, tokenA);
        // B's write carries the live token: accepted
        boolean asCurrentLeader = schedules.advanceFire(schedule.id(), schedule.version(),
                next, now, tokenB);

        assertThat(asStaleLeader).as("stale leader must not mutate schedules").isFalse();
        assertThat(asCurrentLeader).as("current leader mutates normally").isTrue();

        // fenced job transitions behave identically
        var jobId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, tenant_id, job_type, status, payload_json)
                VALUES (?, ?, 'log', 'SCHEDULED', '{}'::jsonb)
                """, jobId, com.schedula.api.JobsController.DEFAULT_TENANT);

        var jobStore = new com.schedula.persistence.JobStore(jdbc,
                new com.schedula.persistence.EventStore(jdbc));
        boolean staleMove = jobStore.transition(jobId, java.util.Set.of(JobStatus.SCHEDULED),
                JobStatus.QUEUED, "stale-scheduler", "fenced", tokenA);
        boolean freshMove = jobStore.transition(jobId, java.util.Set.of(JobStatus.SCHEDULED),
                JobStatus.QUEUED, "current-leader", "legit", tokenB);

        assertThat(staleMove).isFalse();
        assertThat(freshMove).isTrue();

        // Fencing contract: the stale leader may still BELIEVE it leads (no step-down has
        // run), but the durable truth has moved on. Authority lives in the lease row.
        assertThat(nodeA.isLeader()).as("stale leader belief is harmless").isTrue();
        var lease = new com.schedula.coordination.SchedulerLeaseStore(jdbc).current();
        assertThat(lease).hasValueSatisfying(s -> {
            assertThat(s.ownerNodeId()).isEqualTo(nodeB.nodeId());
            assertThat(s.fencingToken()).isEqualTo(tokenB);
        });
    }
}
