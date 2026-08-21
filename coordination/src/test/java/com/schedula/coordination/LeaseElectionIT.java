package com.schedula.coordination;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LeaseElectionIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static SchedulerLeaseStore store;
    Coordinator nodeA;
    Coordinator nodeB;
    static final long LEASE_MS = 800;

    @BeforeAll
    static void setUp() {
        DataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new SchedulerLeaseStore(jdbc);
    }

    @BeforeEach
    void freshNodesAndEmptyLease() {
        // fresh coordinator instances per test: in-memory leadership belief must not leak
        // across scenarios whose DB state we reset below
        jdbc.update("DELETE FROM scheduler_leases");
        nodeA = new Coordinator(store, new SimpleMeterRegistry(), LEASE_MS, 8081);
        nodeB = new Coordinator(store, new SimpleMeterRegistry(), LEASE_MS, 8082);
    }

    @Test
    void exactlyOneLeaderAtATime() {
        Coordinator.Session a = nodeA.step();
        assertThat(a).isNotNull();
        long tokenA = nodeA.fencingToken();
        assertThat(tokenA).isPositive();

        assertThat(nodeB.step()).isNull();
        assertThat(store.current())
                .hasValueSatisfying(s -> {
                    assertThat(s.ownerNodeId()).isEqualTo(nodeA.nodeId());
                    assertThat(s.fencingToken()).isEqualTo(tokenA);
                });
    }

    @Test
    void takeoverHappensOnlyAfterLeaseExpiryWithFreshToken() throws Exception {
        Coordinator.Session a = nodeA.step();
        long tokenA = a.fencingToken();

        Thread.sleep(LEASE_MS + 300); // leader dies: nobody renews

        Coordinator.Session b = nodeB.step();
        assertThat(b).isNotNull();
        assertThat(b.fencingToken()).isGreaterThan(tokenA);

        // old leader wakes up and probes: it must NOT regain or renew its lost authority
        assertThat(nodeA.step()).isNull();
        assertThat(nodeA.isLeader()).isFalse();
    }

    @Test
    void staleLeaderCannotRenew() throws Exception {
        nodeA.step();
        long tokenA = nodeA.fencingToken();
        Thread.sleep(LEASE_MS + 300);
        nodeB.step(); // B takes over

        assertThat(store.renew(nodeA.nodeId(), tokenA, LEASE_MS)).isEmpty();
        assertThat(store.renew(nodeB.nodeId(), nodeB.fencingToken(), LEASE_MS)).isPresent();
    }

    @Test
    void reAcquisitionAfterOwnLapseBurnsNewToken() throws Exception {
        Coordinator.Session first = nodeA.step();
        long firstToken = first.fencingToken();
        Thread.sleep(LEASE_MS + 300); // lapse: own lease expired, nobody took it
        Coordinator.Session second = nodeA.step();
        assertThat(second).isNotNull();
        assertThat(second.fencingToken()).isGreaterThan(firstToken);
    }
}
