package com.schedula.common.model;

import java.time.Instant;
import java.util.UUID;

public record WorkflowTimer(
        UUID id,
        UUID wfExecutionId,
        String taskKey,
        Instant firesAt,
        String state) {
}
