package com.schedula.common.jobs;

public class IllegalTransitionException extends RuntimeException {

    private final JobStatus from;
    private final JobStatus to;

    public IllegalTransitionException(JobStatus from, JobStatus to) {
        super("illegal job transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public JobStatus from() {
        return from;
    }

    public JobStatus to() {
        return to;
    }
}
