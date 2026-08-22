package com.schedula.common.model;

import com.schedula.common.jobs.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        UUID tenantId,
        String jobType,
        String queueName,
        int priority,
        JobStatus status,
        String payloadJson,
        int maxAttempts,
        String retryPolicyJson,
        long timeoutMs,
        Instant scheduledFor,
        UUID scheduleId,
        String idempotencyKey,
        int attemptsMade,
        Instant nextAttemptAt,
        long version,
        java.util.List<String> requiredCapabilities,
        int requiredCpu,
        long requiredMemMb,
        String webhookUrl,
        String webhookState,
        int webhookAttempts,
        Instant createdAt,
        Instant updatedAt) {
}
