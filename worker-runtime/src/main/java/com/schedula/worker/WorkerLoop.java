package com.schedula.worker;

import com.schedula.common.jobs.ExecStatus;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.retry.DelayCalculator;
import com.schedula.common.retry.ErrorClass;
import com.schedula.common.retry.RetryPolicy;
import com.schedula.common.time.Clock;
import com.schedula.dispatcher.DispatchService;
import com.schedula.dispatcher.DispatchService.Claimed;
import com.schedula.persistence.ExecutionStore;
import com.schedula.persistence.JobStore;
import com.schedula.persistence.WorkerStore;
import com.schedula.queue.PostgresQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.random.RandomGenerator;

/**
 * Worker runtime. Claims work via the dispatcher, runs handlers on virtual threads with
 * per-job timeouts, records outcomes in guarded transactions, and drains gracefully.
 * <p>
 * Phase 1 note: lease renewal is a Phase 2 feature; the visibility timeout (claim expiry)
 * plus fencing tokens already make redelivery safe if this process dies mid-run.
 */
@Service
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    public record Props(UUID workerId, String name, int concurrency, int batchSize,
                        long pollIntervalMs, long visibilityTimeoutMs,
                        long heartbeatIntervalMs, long drainDeadlineMs) {
    }

    private final DispatchService dispatcher;
    private final ExecutionStore executions;
    private final JobStore jobs;
    private final PostgresQueue queue;
    private final WorkerStore workers;
    private final HandlerRegistry registry;
    private final Clock clock;
    private final Props props;
    private final RandomGenerator random = RandomGenerator.getDefault();

    private final ExecutorService handlerPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, CountDownLatch> inflight = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatPump;
    private Thread mainLoop;
    private volatile boolean running;
    private volatile boolean draining;

    private final Counter startedTotal;
    private final Counter completedTotal;
    private final Counter failedTotal;
    private final Counter retryScheduledTotal;
    private final Counter deadTotal;
    private final Timer executionDuration;

    public WorkerLoop(DispatchService dispatcher, ExecutionStore executions, JobStore jobs,
                      PostgresQueue queue, WorkerStore workers, HandlerRegistry registry,
                      Clock clock, MeterRegistry meters,
                      @Value("${schedula.worker.concurrency:8}") int concurrency,
                      @Value("${schedula.worker.batch-size:16}") int batchSize,
                      @Value("${schedula.worker.poll-interval-ms:250}") long pollIntervalMs,
                      @Value("${schedula.queue.visibility-timeout-ms:300000}") long visibilityTimeoutMs,
                      @Value("${schedula.worker.heartbeat-interval-ms:5000}") long heartbeatIntervalMs,
                      @Value("${schedula.worker.drain-deadline-ms:30000}") long drainDeadlineMs) {
        this.dispatcher = dispatcher;
        this.executions = executions;
        this.jobs = jobs;
        this.queue = queue;
        this.workers = workers;
        this.registry = registry;
        this.clock = clock;
        this.props = new Props(UUID.randomUUID(), "worker-" + UUID.randomUUID().toString().substring(0, 8),
                concurrency, batchSize, pollIntervalMs, visibilityTimeoutMs,
                heartbeatIntervalMs, drainDeadlineMs);
        this.startedTotal = Counter.builder("schedula_job_started_total").register(meters);
        this.completedTotal = Counter.builder("schedula_job_completed_total").register(meters);
        this.failedTotal = Counter.builder("schedula_job_failed_total").register(meters);
        this.retryScheduledTotal = Counter.builder("schedula_job_retried_total").register(meters);
        this.deadTotal = Counter.builder("schedula_job_dead_total").register(meters);
        this.executionDuration = Timer.builder("schedula_job_execution_duration")
                .description("handler wall time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        workers.register(props.workerId(), props.name(), "phase1", props.concurrency());
        heartbeatPump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worker-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatPump.scheduleAtFixedRate(this::safeHeartbeat,
                props.heartbeatIntervalMs(), props.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        mainLoop = new Thread(this::loop, "worker-main");
        mainLoop.setDaemon(false);
        mainLoop.start();
        log.info("worker started id={} concurrency={}", props.workerId(), props.concurrency());
    }

    public synchronized void stop() {
        if (!running) return;
        draining = true;
        log.info("worker draining: stop claiming, wait for inflight (deadline {}ms)", props.drainDeadlineMs());
        long deadline = clock.monotonicNanos() + TimeUnit.MILLISECONDS.toNanos(props.drainDeadlineMs());
        for (UUID execId : Set.copyOf(inflight.keySet())) {
            try {
                boolean ok = inflight.get(execId).await(
                        Math.max(1, deadline - clock.monotonicNanos()), TimeUnit.NANOSECONDS);
                if (!ok) log.warn("drain deadline hit with execution {} still running", execId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        if (mainLoop != null) mainLoop.interrupt();
        if (heartbeatPump != null) heartbeatPump.shutdownNow();
        handlerPool.shutdownNow();
        workers.setDraining(props.workerId());
        workers.deregister(props.workerId());
        log.info("worker stopped");
    }

    private void safeHeartbeat() {
        try {
            workers.heartbeat(props.workerId());
        } catch (RuntimeException e) {
            log.warn("heartbeat failed: {}", e.toString());
        }
    }

    private void loop() {
        while (running) {
            try {
                int freeSlots = props.concurrency() - inflight.size();
                if (freeSlots <= 0) {
                    parkQuietly(25);
                    continue;
                }
                List<Claimed> claimed = dispatcher.claimAndDispatch(
                        props.workerId(), Math.min(props.batchSize(), freeSlots),
                        props.visibilityTimeoutMs());
                if (claimed.isEmpty()) {
                    parkQuietly(pollBackoffMs());
                    continue;
                }
                for (Claimed c : claimed) {
                    handlerPool.submit(() -> runOne(c));
                }
            } catch (Exception e) {
                log.warn("claim loop error: {}", e.toString());
                parkQuietly(pollBackoffMs());
            }
        }
    }

    private long pollBackoffMs() {
        return props.pollIntervalMs() + random.nextLong(Math.max(1, props.pollIntervalMs()));
    }

    private void runOne(Claimed claimed) {
        var msg = claimed.message();
        var exec = claimed.execution();
        CountDownLatch latch = new CountDownLatch(1);
        inflight.put(exec.id(), latch);
        try {
            if (!executions.start(exec.id(), props.workerId(), claimed.fencingToken())) {
                queue.cancelClaimed(msg.id(), props.workerId());
                return;
            }
            startedTotal.increment();
            Job job = jobs.findById(msg.jobId()).orElse(null);
            if (job == null) {
                finishFailed(claimed, "VALIDATION", "job row missing");
                return;
            }
            var handlerOpt = registry.find(job.jobType());
            if (handlerOpt.isEmpty()) {
                finishFailed(claimed, ErrorClass.PERMANENT.name(),
                        "no handler registered for type " + job.jobType());
                markJobOutcome(claimed, job, ExecStatus.FAILED, ErrorClass.PERMANENT.name(),
                        "no handler for type " + job.jobType());
                return;
            }
            long startNanos = clock.monotonicNanos();
            Outcome outcome = executeWithTimeout(handlerOpt.get(), job, claimed);
            executionDuration.record(clock.monotonicNanos() - startNanos, TimeUnit.NANOSECONDS);
            switch (outcome.kind()) {
                case SUCCESS -> finishSuccess(claimed, job);
                case TIMEOUT -> handleFailure(claimed, job, "TIMEOUT", "execution timed out after "
                        + job.timeoutMs() + "ms", ErrorClass.TRANSIENT);
                case ERROR -> handleFailure(claimed, job, outcome.errorClass(),
                        outcome.message(), classify(outcome.errorClass()));
            }
        } catch (RuntimeException fatal) {
            log.error("unexpected worker error for execution {}: {}", exec.id(), fatal.toString());
            finishFailed(claimed, "INTERNAL", fatal.toString());
        } finally {
            inflight.remove(exec.id());
            latch.countDown();
        }
    }

    private record Outcome(Kind kind, String errorClass, String message) {
        enum Kind {SUCCESS, TIMEOUT, ERROR}
    }

    private Outcome executeWithTimeout(JobHandler handler, Job job, Claimed claimed) {
        Future<?> future = handlerPool.submit(() -> {
            try {
                handler.handle(context(job, claimed));
            } catch (Exception e) {
                throw new Wrapped(e);
            }
        });
        try {
            future.get(job.timeoutMs(), TimeUnit.MILLISECONDS);
            return new Outcome(Outcome.Kind.SUCCESS, null, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Outcome(Outcome.Kind.TIMEOUT, "TIMEOUT", "timeout");
        } catch (Exception e) {
            Throwable cause = e.getCause() instanceof Wrapped w ? w.getCause() : e;
            String cls = cause instanceof ClassifiedException ce ? ce.errorClass().name()
                    : guessClass(cause);
            return new Outcome(Outcome.Kind.ERROR, cls, String.valueOf(cause));
        }
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Throwable cause) {
            super(cause);
        }
    }

    private String guessClass(Throwable t) {
        String n = t.getClass().getSimpleName().toLowerCase();
        if (n.contains("connect") || n.contains("timeout") || n.contains("unavailable")) {
            return ErrorClass.TRANSIENT.name();
        }
        return ErrorClass.PERMANENT.name();
    }

    private ErrorClass classify(String errorClassName) {
        try {
            return ErrorClass.valueOf(errorClassName);
        } catch (IllegalArgumentException e) {
            return ErrorClass.PERMANENT;
        }
    }

    private JobContext context(Job job, Claimed claimed) {
        return new JobContext(job.id(), job.tenantId(), job.jobType(), job.payloadJson(),
                job.tenantId() + ":" + job.id(), claimed.message().deliverCount(),
                claimed.execution().id(), claimed.fencingToken());
    }

    private void finishSuccess(Claimed claimed, Job job) {
        executions.finish(claimed.execution().id(), ExecStatus.COMPLETED, null, null,
                claimed.fencingToken());
        boolean moved = jobs.transition(job.id(), Set.of(JobStatus.RUNNING), JobStatus.COMPLETED,
                "worker:" + props.workerId(), "attempt " + claimed.message().deliverCount() + " succeeded");
        if (moved) {
            queue.ack(claimed.message().id(), props.workerId());
            completedTotal.increment();
        }
    }

    private void handleFailure(Claimed claimed, Job job, String errorClass, String detail,
                               ErrorClass classified) {
        executions.finish(claimed.execution().id(), ExecStatus.FAILED, errorClass, detail,
                claimed.fencingToken());
        RetryPolicy policy = RetryPolicy.fromJson(job.retryPolicyJson());
        boolean retryable = classified.retryableByDefault();
        boolean attemptsLeft = job.attemptsMade() + 1 < policy.maxAttempts();
        if (retryable && attemptsLeft) {
            long delay = DelayCalculator.delayMs(policy, job.attemptsMade() + 1, random);
            boolean moved = jobs.markRetryEligible(job.id(), clock.now().plusMillis(delay),
                    "worker:" + props.workerId(), errorClass + ": " + detail);
            if (moved) {
                queue.nack(claimed.message().id(), props.workerId(), delay);
                retryScheduledTotal.increment();
                return;
            }
        }
        markJobOutcome(claimed, job, ExecStatus.FAILED, errorClass, detail);
        deadTotal.increment();
    }

    /** Terminal path: job DEAD + message DLQ'd, atomically. */
    private void markJobOutcome(Claimed claimed, Job job, ExecStatus execStatus,
                                String errorClass, String detail) {
        boolean moved = jobs.transition(job.id(), Set.of(JobStatus.RUNNING), JobStatus.DEAD,
                "worker:" + props.workerId(), errorClass + ": " + detail);
        if (moved) {
            queue.deadletter(claimed.message().id(), props.workerId(), detail);
        }
    }

    private void finishFailed(Claimed claimed, String errorClass, String detail) {
        executions.finish(claimed.execution().id(), ExecStatus.FAILED, errorClass, detail,
                claimed.fencingToken());
        failedTotal.increment();
    }

    private void parkQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public UUID workerId() {
        return props.workerId();
    }
}
