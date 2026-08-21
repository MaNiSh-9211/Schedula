package com.schedula.common.model;

import java.time.Instant;
import java.util.UUID;

public record JobEvent(
        long id,
        UUID jobId,
        UUID jobExecutionId,
        String eventType,
        String actor,
        String reason,
        Long fencingToken,
        String payloadJson,
        Instant occurredAt) {
}
