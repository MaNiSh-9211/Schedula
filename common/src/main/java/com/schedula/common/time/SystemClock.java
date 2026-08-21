package com.schedula.common.time;

import java.time.Instant;

public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }

    @Override
    public String toString() {
        return "SystemClock";
    }
}
