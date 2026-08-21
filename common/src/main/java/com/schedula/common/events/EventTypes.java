package com.schedula.common.events;

public final class EventTypes {

    private EventTypes() {
    }

    public static final String JOB_CREATED = "JOB_CREATED";
    public static final String JOB_SCHEDULED = "JOB_SCHEDULED";
    public static final String JOB_QUEUED = "JOB_QUEUED";
    public static final String JOB_DISPATCHED = "JOB_DISPATCHED";
    public static final String JOB_STARTED = "JOB_STARTED";
    public static final String JOB_COMPLETED = "JOB_COMPLETED";
    public static final String JOB_FAILED = "JOB_FAILED";
    public static final String JOB_RETRY_SCHEDULED = "JOB_RETRY_SCHEDULED";
    public static final String JOB_DEAD = "JOB_DEAD";
    public static final String JOB_CANCELLED = "JOB_CANCELLED";
    public static final String JOB_PAUSED = "JOB_PAUSED";
    public static final String JOB_RESUMED = "JOB_RESUMED";

    public static final String EXECUTION_LEASE_GRANTED = "EXECUTION_LEASE_GRANTED";
    public static final String CLAIM_EXPIRED_REQUEUED = "CLAIM_EXPIRED_REQUEUED";
    public static final String MESSAGE_DEADLETTERED = "MESSAGE_DEADLETTERED";
    public static final String SCHEDULE_TICKED = "SCHEDULE_TICKED";
}
