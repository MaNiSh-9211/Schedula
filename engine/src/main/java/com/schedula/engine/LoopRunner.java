package com.schedula.engine;

import com.schedula.common.time.Clock;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * Minimal fixed-interval loop runner with jittered scheduling. Deliberately simple:
 * Phase 3 replaces single-instance loops with leader-elected ones, not fancier timers.
 */
public final class LoopRunner {

    private final String name;
    private final long intervalMs;
    private final Runnable body;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public LoopRunner(String name, long intervalMs, Runnable body, Clock clock) {
        this.name = name;
        this.intervalMs = intervalMs;
        this.body = body;
        this.clock = clock;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "loop-" + name);
            t.setDaemon(true);
            return t;
        });
        long jittered = jitteredInterval();
        executor.scheduleAtFixedRate(this::safeRun, jittered, intervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void safeRun() {
        try {
            body.run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("schedula.loop." + name)
                    .warn("loop {} iteration failed: {}", name, e.toString());
        }
    }

    private long jitteredInterval() {
        return intervalMs / 2 + random.nextLong(Math.max(1, intervalMs / 2));
    }

    public long nowNanos() {
        return clock.monotonicNanos();
    }
}
