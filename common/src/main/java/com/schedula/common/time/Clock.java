package com.schedula.common.time;

import java.time.Instant;

public interface Clock {

    Instant now();

    long monotonicNanos();

    Clock SYSTEM = new SystemClock();
}
