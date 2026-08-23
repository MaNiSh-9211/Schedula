package com.schedula.common.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One task instance of a workflow execution. Kind UNDO rows are compensation tasks. */
public record WorkflowTask(
        UUID id,
        UUID wfExecutionId,
        String taskKey,
        Kind kind,
        String undoFor,
        Status status,
        List<String> dependsOn,
        String jobType,
        String payloadJson,
        int attemptNo,
        int maxAttempts,
        Long waitMs,
        UUID jobId,
        String errorClass,
        String errorDetail,
        Instant startedAt,
        Instant finishedAt) {

    public enum Kind { JOB, WAIT, SIGNAL, CHILD, UNDO }

    public enum Status { BLOCKED, RUNNING, SUCCEEDED, FAILED_PERMANENT, SKIPPED, CANCELLED }

    public boolean isOpen() {
        return status == Status.BLOCKED || status == Status.RUNNING;
    }
}
