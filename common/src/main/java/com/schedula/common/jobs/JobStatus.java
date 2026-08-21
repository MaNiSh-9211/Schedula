package com.schedula.common.jobs;

import java.util.Map;
import java.util.Set;

public enum JobStatus {
    CREATED,
    SCHEDULED,
    QUEUED,
    DISPATCHED,
    RUNNING,
    RETRY_WAIT,
    PAUSED,
    COMPLETED,
    FAILED_TERMINAL,
    DEAD,
    CANCELLED,
    REJECTED;

    public static final Set<JobStatus> TERMINAL =
            Set.of(COMPLETED, FAILED_TERMINAL, DEAD, CANCELLED, REJECTED);

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = Map.ofEntries(
            Map.entry(CREATED, Set.of(SCHEDULED, REJECTED)),
            Map.entry(SCHEDULED, Set.of(QUEUED, PAUSED, CANCELLED)),
            Map.entry(QUEUED, Set.of(DISPATCHED, PAUSED, CANCELLED)),
            // QUEUED/DEAD out of DISPATCHED are recovery edges taken only by the sweeper
            // when a claim expires without an ack (lease-loss redelivery / max deliveries).
            Map.entry(DISPATCHED, Set.of(RUNNING, QUEUED, DEAD)),
            Map.entry(RUNNING, Set.of(COMPLETED, RETRY_WAIT, DEAD, FAILED_TERMINAL, QUEUED)),
            Map.entry(RETRY_WAIT, Set.of(QUEUED, DEAD, CANCELLED)),
            Map.entry(PAUSED, Set.of(SCHEDULED, CANCELLED)));

    public boolean canTransitionTo(JobStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static void assertLegal(JobStatus from, JobStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
