package com.schedula.engine.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.WorkflowDefinition;
import com.schedula.common.model.WorkflowExecution;
import com.schedula.common.model.WorkflowTask;
import com.schedula.common.retry.RetryPolicy;
import com.schedula.common.time.Clock;
import com.schedula.coordination.Coordinator;
import com.schedula.persistence.EventStore;
import com.schedula.persistence.JobStore;
import com.schedula.persistence.WorkflowStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable DAG driver (Phase 6). Every task is a REAL platform job, so retries, leases,
 * timeouts and the DLQ come for free; this driver only reconciles persisted state:
 *
 *   1. fire due WAIT timers            (durable timers survive any restart)
 *   2. reconcile finished backing jobs (task RUNNING + job terminal -> SUCCEEDED/FAILED)
 *   3. retry failed tasks per task policy (fresh job, attempt_no++)
 *   4. unblock newly-satisfiable tasks  (deps all SUCCEEDED -> create backing job)
 *   5. run compensations on failure     (reverse order UNDO tasks for succeeded tasks)
 *   6. close workflows whose sets are empty
 *
 * Everything derives from rows: crash at any point is recovered by the next tick.
 */
@Service
public class WorkflowDriver {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDriver.class);

    private final WorkflowStore store;
    private final JobStore jobs;
    private final EventStore events;
    private final Coordinator coordinator;
    private final Clock clock;
    private final Counter wfStarted;
    private final Counter wfCompleted;
    private final Counter wfFailed;

    public WorkflowDriver(WorkflowStore store, JobStore jobs, EventStore events,
                          Coordinator coordinator, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.jobs = jobs;
        this.events = events;
        this.coordinator = coordinator;
        this.clock = clock;
        this.wfStarted = Counter.builder("schedula_workflow_started_total").register(meters);
        this.wfCompleted = Counter.builder("schedula_workflow_completed_total").register(meters);
        this.wfFailed = Counter.builder("schedula_workflow_failed_total").register(meters);
    }

    /** Registers a new immutable version of a named workflow. */
    public WorkflowStore.Registered register(UUID tenantId, String name, String definitionJson) {
        WorkflowDefinition def = WorkflowDefinition.parse(definitionJson);
        return store.register(tenantId, name, def);
    }

    public WorkflowExecution start(UUID tenantId, String name, String inputJson) {
        UUID wfId = store.findByName(tenantId, name)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow " + name));
        UUID versionId = store.latestVersionId(wfId)
                .orElseThrow(() -> new IllegalStateException("workflow has no versions"));
        String definitionJson = store.definitionOf(versionId).orElseThrow();
        WorkflowDefinition def = WorkflowDefinition.parse(definitionJson);

        WorkflowExecution exec = store.createExecution(tenantId, versionId, inputJson);
        store.insertTasks(exec.id(), def); // executions are pinned to their version (§56)
        wfStarted.increment();
        return exec;
    }

    public void tick() {
        if (!coordinator.isLeader()) {
            return;
        }
        fireDueTimers();
        for (WorkflowExecution exec : store.findOpen()) {
            try {
                advance(exec);
            } catch (RuntimeException e) {
                log.warn("workflow {} tick failed: {}", exec.id(), e.toString());
            }
        }
    }

    // --- timers ----------------------------------------------------------------

    private void fireDueTimers() {
        for (var timer : store.dueTimers(clock.now(), 50)) {
            if (!store.fireTimer(timer.id())) continue; // someone else fired it
            store.tasksFor(timer.wfExecutionId()).stream()
                    .filter(t -> t.taskKey().equals(timer.taskKey()))
                    .findFirst()
                    .ifPresent(t -> {
                        if (store.transitionTask(t.id(), WorkflowTask.Status.RUNNING,
                                WorkflowTask.Status.SUCCEEDED)) {
                            store.markSucceededAt(t.id());
                        }
                    });
        }
    }

    // --- main reconciliation ---------------------------------------------------

    private void advance(WorkflowExecution exec) {
        List<WorkflowTask> tasks = store.tasksFor(exec.id());

        boolean changed = reconcileFinishedJobs(tasks);
        if (changed) tasks = store.tasksFor(exec.id());

        // failure handling first: FAILING runs compensation before anything else proceeds
        if (exec.status() == WorkflowExecution.Status.FAILING
                || tasks.stream().anyMatch(t -> t.status() == WorkflowTask.Status.FAILED_PERMANENT)) {
            handleFailure(exec, tasks);
            return;
        }

        boolean created = unblockReadyTasks(exec, tasks);
        if (created) return; // next tick reconciles fresh state

        boolean allDone = tasks.stream().noneMatch(WorkflowTask::isOpen);
        boolean allSucceeded = tasks.stream()
                .allMatch(t -> t.status() == WorkflowTask.Status.SUCCEEDED
                        || t.status() == WorkflowTask.Status.SKIPPED);
        if (allDone && allSucceeded && exec.status() == WorkflowExecution.Status.RUNNING) {
            if (store.casStatus(exec.id(), exec.version(), WorkflowExecution.Status.COMPLETED, null)) {
                wfCompleted.increment();
                log.info("workflow {} completed", exec.id());
            }
        }
    }

    private boolean reconcileFinishedJobs(List<WorkflowTask> tasks) {
        boolean changed = false;
        for (WorkflowTask t : tasks) {
            if (t.kind() != WorkflowTask.Kind.JOB || t.status() != WorkflowTask.Status.RUNNING
                    || t.jobId() == null) {
                continue;
            }
            Job job = jobs.findById(t.jobId()).orElse(null);
            if (job == null || !job.status().isTerminal()) continue;

            switch (job.status()) {
                case COMPLETED -> {
                    if (store.transitionTask(t.id(), WorkflowTask.Status.RUNNING,
                            WorkflowTask.Status.SUCCEEDED)) {
                        store.markSucceededAt(t.id());
                        changed = true;
                    }
                }
            case DEAD, FAILED_TERMINAL, CANCELLED -> {
                // the backing job already exhausted ITS OWN retry policy (task maxAttempts
                // is applied at job level), so DEAD here means genuinely exhausted
                if (store.transitionTask(t.id(), WorkflowTask.Status.RUNNING,
                        WorkflowTask.Status.FAILED_PERMANENT)) {
                    store.markFailed(t.id(), "PERMANENT",
                            "job ended " + job.status() + " after " + t.attemptNo() + " attempts");
                    changed = true;
                }
            }
                default -> { /* non-terminal oddity: leave for next tick */ }
            }
        }
        return changed;
    }

    /** Task-level retries are independent of workflow-level failure (§37): new job, same key. */

    private boolean unblockReadyTasks(WorkflowExecution exec, List<WorkflowTask> tasks) {
        Map<String, WorkflowTask> byKey = new HashMap<>();
        tasks.forEach(t -> byKey.put(t.taskKey(), t));
        boolean created = false;

        for (WorkflowTask t : tasks) {
            if (t.status() != WorkflowTask.Status.BLOCKED) continue;

            if (t.kind() == WorkflowTask.Kind.WAIT) {
                if (depsSatisfied(t, byKey)) {
                    if (store.transitionTask(t.id(), WorkflowTask.Status.BLOCKED,
                            WorkflowTask.Status.RUNNING)) {
                        store.insertTimer(exec.id(), t.taskKey(),
                                clock.now().plusMillis(t.waitMs() == null ? 0 : t.waitMs()));
                        created = true;
                    }
                }
                continue;
            }

            if (!depsSatisfied(t, byKey)) continue;

            if (t.kind() == WorkflowTask.Kind.UNDO) {
                WorkflowTask original = byKey.get(t.undoFor());
                String payload = undoPayload(original);
                Job undoJob = jobs.create(new JobStore.Insert(
                        exec.tenantId(), t.jobType(), 0, payload, null, "{}",
                        60_000L, clock.now(), null,
                        "wfundo:" + exec.id() + ":" + t.taskKey(),
                        List.of(), 0, 0L, null, null));
                if (store.transitionTask(t.id(), WorkflowTask.Status.BLOCKED,
                        WorkflowTask.Status.RUNNING)) {
                    store.attachJob(t.id(), undoJob.id(), 1);
                    created = true;
                }
                continue;
            }

            // normal JOB task
            Job job = jobs.create(new JobStore.Insert(
                    exec.tenantId(), t.jobType(), 0, t.payloadJson(),
                    t.maxAttempts(), "{}", 60_000L, clock.now(), null,
                    "wftask:" + exec.id() + ":" + t.taskKey() + ":1",
                    List.of(), 0, 0L, null, null));
            if (store.transitionTask(t.id(), WorkflowTask.Status.BLOCKED,
                    WorkflowTask.Status.RUNNING)) {
                store.attachJob(t.id(), job.id(), 1);
                created = true;
            }
        }
        return created;
    }

    private boolean depsSatisfied(WorkflowTask t, Map<String, WorkflowTask> byKey) {
        for (String dep : t.dependsOn()) {
            WorkflowTask d = byKey.get(dep);
            if (d == null) return false;
            // UNDO siblings don't gate normal flow
            if (d.kind() == WorkflowTask.Kind.UNDO) continue;
            if (d.status() != WorkflowTask.Status.SUCCEEDED
                    && d.status() != WorkflowTask.Status.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Failure protocol: first permanent failure flips RUNNING->FAILING once; then UNDO
     * tasks for succeeded-with-undo tasks are enqueued in reverse completion order; when
     * every undo finished, the workflow closes FAILED (+compensated flag).
     */
    private void handleFailure(WorkflowExecution exec, List<WorkflowTask> tasks) {
        Map<String, WorkflowTask> byKey = new HashMap<>();
        tasks.forEach(t -> byKey.put(t.taskKey(), t));

        var def = WorkflowDefinition.parse(store.definitionOf(exec.workflowVersionId()).orElse("{}"));
        var specByKey = new HashMap<String, WorkflowDefinition.TaskSpec>();
        def.tasks().forEach(s -> specByKey.put(s.key(), s));

        if (exec.status() == WorkflowExecution.Status.RUNNING) {
            if (!store.casStatus(exec.id(), exec.version(), WorkflowExecution.Status.FAILING, null)) {
                return;
            }
            log.warn("workflow {} failing; running compensations", exec.id());
            // reverse declaration order = coarse reverse topological order for undoing
            var undone = new java.util.HashSet<String>();
            for (int i = def.tasks().size() - 1; i >= 0; i--) {
                var spec = def.tasks().get(i);
                WorkflowTask done = byKey.get(spec.key());
                if (spec.undo() == null || done == null
                        || done.status() != WorkflowTask.Status.SUCCEEDED) continue;
                if (!undone.add(spec.key())) continue;
                store.insertUndoTask(exec.id(), "undo:" + spec.key(), spec.key(),
                        spec.undo().jobType(),
                        spec.undo().payload() == null ? "{}" : spec.undo().payload().toString());
            }
            return;
        }

        // COMPENSATING / FAILING with undos already queued: wait until they finish
        List<WorkflowTask> undos = tasks.stream()
                .filter(t -> t.kind() == WorkflowTask.Kind.UNDO).toList();
        boolean anyOpenUndo = undos.stream().anyMatch(u ->
                u.status() == WorkflowTask.Status.BLOCKED || u.status() == WorkflowTask.Status.RUNNING);
        if (anyOpenUndo) {
            driveUndos(exec, undos, byKey);
            return;
        }
        boolean allUndosOk = undos.stream().allMatch(u -> u.status() == WorkflowTask.Status.SUCCEEDED);
        boolean compensated = !undos.isEmpty() && allUndosOk;
        log.info("[wf] closing {} as FAILED compensated={} (exec.version={}, undos={})",
                exec.id(), compensated, exec.version(), undos.size());
        if (store.casStatus(exec.id(), exec.version(), WorkflowExecution.Status.FAILED, compensated)) {
            wfFailed.increment();
            log.info("workflow {} FAILED compensated={}", exec.id(), compensated);
        } else {
            log.warn("[wf] close CAS lost for {} expectedVersion={}", exec.id(), exec.version());
        }
    }

    private void driveUndos(WorkflowExecution exec, List<WorkflowTask> undos,
                            Map<String, WorkflowTask> byKey) {
        for (WorkflowTask u : undos) {
            if (u.status() != WorkflowTask.Status.BLOCKED) continue;
            WorkflowTask original = byKey.get(u.undoFor());
            String payload = undoPayload(original);
            Job undoJob = jobs.create(new JobStore.Insert(
                    exec.tenantId(), u.jobType(), 0, payload, null, "{}",
                    60_000L, clock.now(), null,
                    "wfundo:" + exec.id() + ":" + u.taskKey(),
                    List.of(), 0, 0L, null, null));
            if (store.transitionTask(u.id(), WorkflowTask.Status.BLOCKED,
                    WorkflowTask.Status.RUNNING)) {
                store.attachJob(u.id(), undoJob.id(), 1);
            }
        }
        // reconcile undo job outcomes
        for (WorkflowTask u : undos) {
            if (u.status() != WorkflowTask.Status.RUNNING || u.jobId() == null) continue;
            Job job = jobs.findById(u.jobId()).orElse(null);
            if (job == null || !job.status().isTerminal()) continue;
            if (job.status() == JobStatus.COMPLETED) {
                if (store.transitionTask(u.id(), WorkflowTask.Status.RUNNING,
                        WorkflowTask.Status.SUCCEEDED)) store.markSucceededAt(u.id());
            } else {
                // compensations must not be silently lost: undo jobs carry their own
                // max_attempts=2 internally; terminal non-success here is final
                if (store.transitionTask(u.id(), WorkflowTask.Status.RUNNING,
                        WorkflowTask.Status.FAILED_PERMANENT)) store.markFailed(u.id(), "PERMANENT", "undo failed");
            }
        }
    }

    private String undoPayload(WorkflowTask original) {
        try {
            ObjectMapper json = new ObjectMapper();
            JsonNode node = json.readTree(original.payloadJson() == null ? "{}" : original.payloadJson());
            var out = json.createObjectNode();
            out.set("original", node);
            out.put("undoFor", original.taskKey());
            return json.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"undoFor\":\"" + original.taskKey() + "\"}";
        }
    }

    private UUID tenantOfTask(WorkflowTask t) {
        return store.findExecById(t.wfExecutionId())
                .map(WorkflowExecution::tenantId)
                .orElseThrow(() -> new IllegalStateException("orphan task"));
    }
}



