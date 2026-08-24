package com.schedula.app;

import com.schedula.common.time.Clock;
import com.schedula.engine.LoopRunner;
import com.schedula.engine.RecoveryService;
import com.schedula.engine.RetentionService;
import com.schedula.engine.SchedulerLoop;
import com.schedula.persistence.EventStore;
import com.schedula.persistence.JobStore;
import com.schedula.worker.WorkerLoop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
public class SchedulaConfig {

    @Bean
    public Clock clock() {
        return Clock.SYSTEM;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "schedula.roles.scheduler", havingValue = "true", matchIfMissing = true)
    public SmartLifecycle schedulerLifecycle(
            SchedulerLoop loop,
            RecoveryService recovery,
            RetentionService retention,
            com.schedula.coordination.Coordinator coordinator,
            com.schedula.persistence.WorkflowStore workflowStore,
            JobStore jobStore,
            EventStore eventStore,
            Clock clock,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            io.micrometer.core.instrument.MeterRegistry meters,
            @Value("${schedula.scheduler.poll-interval-ms:250}") long pollMs,
            @Value("${schedula.recovery.sweep-interval-ms:5000}") long sweepMs,
            @Value("${schedula.coordinator.probe-interval-ms:1000}") long probeMs) {
        return new SmartLifecycle() {
            private LoopRunner runner;
            private LoopRunner coordinatorRunner;
            private LoopRunner wfRunner;
            private ScheduledExecutorService sweeper;
            private volatile boolean runningFlag;

            @Override
            public void start() {
                coordinatorRunner = new LoopRunner("coordinator", probeMs, coordinator::step, clock);
                coordinatorRunner.start();
                runner = new LoopRunner("scheduler", pollMs, loop::tick, clock);
                runner.start();
                sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "recovery-sweeper");
                    t.setDaemon(true);
                    return t;
                });
                sweeper.scheduleAtFixedRate(recovery::recover, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
                sweeper.scheduleAtFixedRate(retention::run,
                        Math.max(sweepMs, 60_000L), Math.max(sweepMs, 60_000L), TimeUnit.MILLISECONDS);
                var predictor = new com.schedula.engine.PressurePredictor(jdbc, meters,
                        Double.parseDouble(System.getenv().getOrDefault("SCHEDULA_PRESSURE_THRESHOLD", "5000")));
                sweeper.scheduleAtFixedRate(predictor::sample, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
                var webhooks = new com.schedula.engine.WebhookDispatcher(jdbc, coordinator, meters,
                        System.getenv().getOrDefault("SCHEDULA_WEBHOOK_SECRET", "schedula-dev-secret"));
                sweeper.scheduleAtFixedRate(webhooks::tick, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
                var firewall = new com.schedula.engine.CascadeFirewall(jdbc,
                        Integer.parseInt(System.getenv().getOrDefault("SCHEDULA_FW_THRESHOLD", "10")),
                        Integer.parseInt(System.getenv().getOrDefault("SCHEDULA_FW_WINDOW_MIN", "5")));
                sweeper.scheduleAtFixedRate(firewall::releaseAllQuarantined,
                        sweepMs * 4, sweepMs * 4, TimeUnit.MILLISECONDS);
                var wfDriver = new com.schedula.engine.workflow.WorkflowDriver(
                        workflowStore, jobStore, eventStore, coordinator, clock, meters);
                wfRunner = new LoopRunner("workflow-driver", pollMs, wfDriver::tick, clock);
                wfRunner.start();
                var queueDepth = new java.util.concurrent.atomic.AtomicLong(0);
                meters.gauge("schedula_queue_depth", queueDepth);
                sweeper.scheduleAtFixedRate(() -> {
                    try {
                        Long d = jdbc.queryForObject(
                                "SELECT count(*) FROM queue_messages WHERE status = 'READY'",
                                Long.class);
                        if (d != null) queueDepth.set(d);
                    } catch (RuntimeException ignored) { }
                }, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
                runningFlag = true;
            }

            @Override
            public void stop() {
                runningFlag = false;
                if (sweeper != null) sweeper.shutdownNow();
                if (runner != null) runner.stop();
                if (wfRunner != null) wfRunner.stop();
                if (coordinatorRunner != null) coordinatorRunner.stop();
                // release leadership proactively for fast rolling deployments
                coordinator.stepDown();
            }

            @Override
            public boolean isRunning() {
                return runningFlag;
            }
        };
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "schedula.roles.worker", havingValue = "true", matchIfMissing = true)
    public SmartLifecycle workerLifecycle(WorkerLoop worker) {
        return new SmartLifecycle() {
            private volatile boolean runningFlag;

            @Override
            public void start() {
                worker.start();
                runningFlag = true;
            }

            @Override
            public void stop() {
                runningFlag = false;
                worker.stop();
            }

            @Override
            public boolean isRunning() {
                return runningFlag;
            }
        };
    }
}

