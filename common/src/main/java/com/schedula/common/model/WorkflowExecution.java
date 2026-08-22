package com.schedula.common.model;

import java.time.Instant;
import java.util.UUID;

public record WorkflowExecution(
        UUID id,
        UUID tenantId,
        UUID workflowVersionId,
        Status status,
        boolean compensated,
        String inputJson,
        String outputJson,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { RUNNING, FAILING, COMPENSATING, COMPLETED, FAILED, CANCELLED }

    public boolean isOpen() {
        return status == Status.RUNNING || status == Status.FAILING
                || status == Status.COMPENSATING;
    }
}
