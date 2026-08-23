package com.schedula.api;

import com.schedula.api.auth.RequestTenant;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.WorkflowExecution;
import com.schedula.common.model.WorkflowTask;
import com.schedula.engine.workflow.WorkflowDriver;
import com.schedula.persistence.JobStore;
import com.schedula.persistence.WorkflowStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workflows")
public class WorkflowController {

    private final WorkflowDriver driver;
    private final WorkflowStore store;
    private final JobStore jobs;

    public WorkflowController(WorkflowDriver driver, WorkflowStore store, JobStore jobs) {
        this.driver = driver;
        this.store = store;
        this.jobs = jobs;
    }

    public record RegisterRequest(String name, Map<String, Object> definition) {
    }

    public record StartRequest(Map<String, Object> input) {
    }

    /** Registers (or bumps the version of) a workflow definition. */
    @PostMapping
    ResponseEntity<?> register(@RequestBody RegisterRequest req, HttpServletRequest http) {
        try {
            UUID tenant = RequestTenant.isAdmin(http)
                    ? JobsController.DEFAULT_TENANT : RequestTenant.tenant(http)
                    .orElse(JobsController.DEFAULT_TENANT);
            String json = writeJson(req.definition());
            var reg = driver.register(tenant, req.name(), json);
            return ResponseEntity.created(URI.create("/v1/workflows/" + req.name()))
                    .body(Map.of("workflowId", reg.workflowId().toString(),
                            "version", reg.version()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    private UUID resolveTenant(HttpServletRequest http) {
        if (RequestTenant.isAdmin(http)) {
            return JobsController.DEFAULT_TENANT;
        }
        return RequestTenant.tenant(http).orElse(JobsController.DEFAULT_TENANT);
    }

    private static String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("not valid json: " + value);
        }
    }

    @PostMapping("/{name}/executions")
    ResponseEntity<?> start(@PathVariable String name, @RequestBody StartRequest req,
                            HttpServletRequest http) {
        UUID tenant = resolveTenant(http);
        try {
            String input = writeJson(req.input() == null ? Map.of() : req.input());
            WorkflowExecution exec = driver.start(tenant, name, input);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("workflowExecutionId", exec.id().toString(),
                                 "status", exec.status().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/executions")
    List<Map<String, Object>> recent(@RequestParam(defaultValue = "25") int limit) {
        return store.findRecent(Math.min(limit, 100)).stream().map(e -> Map.<String, Object>of(
                "id", e.id().toString(),
                "status", e.status().name(),
                "compensated", e.compensated(),
                "createdAt", e.createdAt() == null ? "" : e.createdAt().toString()
        )).toList();
    }

    @GetMapping("/executions/{id}")
    ResponseEntity<?> status(@PathVariable UUID id, HttpServletRequest http) {
        var exec = store.findExecById(id).orElseThrow(() -> new NotFoundException("workflow execution", id));
        List<WorkflowTask> tasks = store.tasksFor(id);
        return ResponseEntity.ok(Map.of(
                "id", exec.id().toString(),
                "status", exec.status().name(),
                "compensated", exec.compensated(),
                "tasks", tasks.stream().map(t -> Map.of(
                        "key", t.taskKey(),
                        "kind", t.kind().name(),
                        "status", t.status().name(),
                        "attemptNo", t.attemptNo(),
                        "jobId", t.jobId() == null ? "" : t.jobId().toString(),
                        "error", (t.errorClass() == null ? "" : t.errorClass())
                                + " " + (t.errorDetail() == null ? "" : t.errorDetail())
                )).toList()
        ));
    }

    /** Deliver a signal into a running workflow execution. */
    @PostMapping("/executions/{id}/signals")
    ResponseEntity<?> signal(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("signal");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "signal name required"));
        }
        store.insertSignal(id, name,
                writeJson(body.getOrDefault("payload", Map.of())));
        return ResponseEntity.accepted().body(Map.of("delivered", true));
    }

    /** Cooperative cancel: open tasks' backing jobs get cancelled; DAG stops advancing. */
    @PostMapping("/executions/{id}/cancel")
    ResponseEntity<?> cancel(@PathVariable UUID id) {
        var exec = store.findExecById(id).orElseThrow(() -> new NotFoundException("workflow execution", id));
        if (!store.casStatus(id, exec.version(), WorkflowExecution.Status.CANCELLED, null)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "race; retry"));
        }
        for (WorkflowTask t : store.tasksFor(id)) {
            if (!t.isOpen() || t.jobId() == null) continue;
            var job = jobs.findById(t.jobId());
            if (job.isEmpty() || job.get().status().isTerminal()) continue;
            // respect the state machine: pre-execution cancels sync; running goes CANCELLING
            if (!jobs.transition(t.jobId(),
                    java.util.Set.of(JobStatus.SCHEDULED, JobStatus.QUEUED,
                            JobStatus.PAUSED, JobStatus.RETRY_WAIT),
                    JobStatus.CANCELLED, "api:wfcancel", "workflow cancelled")) {
                jobs.transition(t.jobId(),
                        java.util.Set.of(JobStatus.RUNNING, JobStatus.DISPATCHED),
                        JobStatus.CANCELLING, "api:wfcancel", "workflow cancelled");
            }
        }
        return ResponseEntity.accepted().body(Map.of("id", id.toString(), "status", "CANCELLED"));
    }
}

