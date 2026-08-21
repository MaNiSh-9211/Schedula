package com.schedula.worker;

import java.util.UUID;

/** Everything a handler may see. Payload is opaque JSON to the platform. */
public record JobContext(
        UUID jobId,
        UUID tenantId,
        String jobType,
        String payloadJson,
        String idempotencyKey,
        int attempt,
        UUID executionId,
        long fencingToken,
        CancellationToken cancellation) {

    public JobContext {
        if (cancellation == null) {
            cancellation = new CancellationToken();
        }
    }
}
