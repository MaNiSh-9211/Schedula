package com.schedula.app;

import com.schedula.common.time.Clock;
import com.schedula.engine.LoopRunner;
import com.schedula.engine.RecoveryService;
import com.schedula.engine.SchedulerLoop;
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
    public SmartLifecycle schedulerLifecycle(SchedulerLoop loop, RecoveryService recovery,
                                             Clock clock,
                                             @Value("${schedula.scheduler.poll-interval-ms:250}") long pollMs,
                                             @Value("${schedula.recovery.sweep-interval-ms:5000}") long sweepMs) {
        return new SmartLifecycle() {
            private LoopRunner runner;
            private ScheduledExecutorService sweeper;
            private volatile boolean runningFlag;

            @Override
            public void start() {
                recovery.recover();
                runner = new LoopRunner("scheduler", pollMs, loop::tick, clock);
                runner.start();
                sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "recovery-sweeper");
                    t.setDaemon(true);
                    return t;
                });
                sweeper.scheduleAtFixedRate(recovery::recover, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
                runningFlag = true;
            }

            @Override
            public void stop() {
                runningFlag = false;
                if (sweeper != null) sweeper.shutdownNow();
                if (runner != null) runner.stop();
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
