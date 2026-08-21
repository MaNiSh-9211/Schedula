package com.schedula.common.model;

import com.schedula.common.jobs.ExecStatus;

import java.time.Instant;
import java.util.UUID;

public record JobExecution(
        UUID id,
        UUID jobId,
        int attemptNo,
        ExecStatus status,
        UUID workerId,
        long fencingToken,
        Instant startedAt,
        Instant finishedAt,
        String errorClass,
        String errorDetail,
        Instant createdAt) {
}
