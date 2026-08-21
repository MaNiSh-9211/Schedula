package com.schedula.queue;

import com.schedula.common.model.QueueMessage;
import com.schedula.persistence.EventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class QueueClaimIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static PostgresQueue queue;

    @BeforeAll
    static void setUp() {
        DataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        queue = new PostgresQueue(jdbc, new EventStore(jdbc), new SimpleMeterRegistry());
    }

    private static UUID enqueueOne(int priority) {
        UUID jobId = UUID.randomUUID();
        queue.enqueue(jobId, UUID.randomUUID(), null, priority, java.time.Instant.now());
        return jobId;
    }

    @org.junit.jupiter.api.BeforeEach
    void isolateQueue() {
        jdbc.update("TRUNCATE queue_messages");
    }

    @Test
    void concurrentWorkersNeverDoubleClaim() throws Exception {
        int total = 50;
        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            jobIds.add(enqueueOne(0));
        }
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<List<UUID>>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            UUID workerId = UUID.randomUUID();
            futures.add(pool.submit((Callable<List<UUID>>) () -> {
                List<UUID> claimed = new ArrayList<>();
                for (int round = 0; round < 10; round++) {
                    for (QueueMessage m : queue.claim(workerId, 5, 60_000)) {
                        claimed.add(m.jobId());
                    }
                }
                return claimed;
            }));
        }
        Set<UUID> seen = new HashSet<>();
        int duplicates = 0;
        for (Future<List<UUID>> f : futures) {
            for (UUID id : f.get()) {
                if (!seen.add(id)) duplicates++;
            }
        }
        pool.shutdownNow();
        assertThat(duplicates).as("duplicate claims across workers").isZero();
        assertThat(seen).hasSize(total);
    }

    @Test
    void priorityComesFirstWithinQueue() {
        enqueueOne(0);
        enqueueOne(10);
        UUID worker = UUID.randomUUID();
        List<QueueMessage> claimed = queue.claim(worker, 1, 60_000);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).priority()).isEqualTo(10);
    }

    @Test
    void ackOnlyWorksForCurrentOwner() {
        enqueueOne(0);
        UUID owner = UUID.randomUUID();
        List<QueueMessage> claimed = queue.claim(owner, 1, 60_000);
        UUID messageId = claimed.get(0).id();
        assertThat(queue.ack(messageId, UUID.randomUUID())).isFalse();
        assertThat(queue.ack(messageId, owner)).isTrue();
    }

    @Test
    void expiredClaimIsReclaimedAndRedelivered() throws Exception {
        enqueueOne(0);
        UUID owner = UUID.randomUUID();
        List<QueueMessage> first = queue.claim(owner, 1, 1);
        assertThat(first).hasSize(1);
        Thread.sleep(120);
        List<PostgresQueue.Reclaimed> reclaimed = queue.reclaimExpired(5);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).deadlettered()).isFalse();
        List<QueueMessage> redelivered = queue.claim(UUID.randomUUID(), 1, 60_000);
        assertThat(redelivered).hasSize(1);
        assertThat(redelivered.get(0).jobId()).isEqualTo(first.get(0).jobId());
        assertThat(redelivered.get(0).deliverCount()).isEqualTo(2);
    }

    @Test
    void maxDeliveriesSendsToDlq() throws Exception {
        enqueueOne(0);
        for (int delivery = 0; delivery < 5; delivery++) {
            UUID owner = UUID.randomUUID();
            List<QueueMessage> claimed = queue.claim(owner, 1, 1);
            assertThat(claimed).hasSize(1);
            Thread.sleep(120);
            List<PostgresQueue.Reclaimed> reclaimed = queue.reclaimExpired(5);
            boolean deadlettered = reclaimed.stream().anyMatch(PostgresQueue.Reclaimed::deadlettered);
            if (delivery == 4) {
                assertThat(deadlettered).isTrue();
                return;
            }
            assertThat(deadlettered).isFalse();
        }
        throw new AssertionError("message never reached DLQ");
    }
}
