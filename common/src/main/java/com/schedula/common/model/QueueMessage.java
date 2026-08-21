package com.schedula.common.model;

import java.time.Instant;
import java.util.UUID;

public record QueueMessage(
        UUID id,
        String queueName,
        UUID jobExecutionId,
        UUID jobId,
        UUID tenantId,
        int priority,
        long enqueueSeq,
        Status status,
        Instant availableAt,
        UUID claimOwner,
        Instant claimExpiresAt,
        int deliverCount,
        Instant enqueuedAt) {

    public enum Status { READY, CLAIMED, DONE, DEADLETTERED, CANCELLED }
}
