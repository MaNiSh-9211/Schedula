package com.schedula.worker;

/**
 * A typed handler. The platform never executes arbitrary user code (SECURITY.md):
 * job types resolve to registered handlers only.
 */
@FunctionalInterface
public interface JobHandler {

    void handle(JobContext ctx) throws Exception;
}
