package com.schedula.common.model;

import java.time.Instant;
import java.util.UUID;

public record JobSchedule(
        UUID id,
        UUID tenantId,
        String name,
        String jobType,
        String payloadJson,
        Kind kind,
        Long intervalMs,
        String cronExpr,
        String timezone,
        MissedPolicy missedPolicy,
        State state,
        Instant nextFireAt,
        Instant lastEnqueuedAt,
        long version,
        int weight,
        Instant createdAt) {

    public enum Kind { FIXED_INTERVAL, CRON }

    public enum MissedPolicy { COALESCE, SKIP_TO_LATEST, RUN_ALL }

    public enum State { ACTIVE, PAUSED, DELETED }
}
