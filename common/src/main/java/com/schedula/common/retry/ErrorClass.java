package com.schedula.common.retry;

public enum ErrorClass {
    TRANSIENT,
    THROTTLED,
    PERMANENT,
    VALIDATION;

    public boolean retryableByDefault() {
        return this == TRANSIENT || this == THROTTLED;
    }
}
