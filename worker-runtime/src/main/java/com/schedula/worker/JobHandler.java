package com.schedula.worker;

/**
 * A typed handler. The platform never executes arbitrary user code (SECURITY.md):
 * job types resolve to registered handlers only.
 * Returning a JSON string stores it as the execution result, retrievable via
 * GET /v1/jobs/{id}/executions (results are also surfaced on the admin UI).
 */
@FunctionalInterface
public interface JobHandler {

    String handle(JobContext ctx) throws Exception;
}
