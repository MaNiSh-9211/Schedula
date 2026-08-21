package com.schedula.worker;

import com.schedula.common.retry.ErrorClass;

/** Handler failure with an explicit retryability classification (retry engine input). */
public class ClassifiedException extends RuntimeException {

    private final ErrorClass errorClass;

    public ClassifiedException(ErrorClass errorClass, String message) {
        super(message);
        this.errorClass = errorClass;
    }

    public ClassifiedException(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
