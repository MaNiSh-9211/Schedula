package com.schedula.worker;

/**
 * Cooperative cancellation signal delivered to handlers. The platform cannot kill
 * arbitrary code (SECURITY.md); honoring this token is part of the handler contract.
 */
public final class CancellationToken {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
