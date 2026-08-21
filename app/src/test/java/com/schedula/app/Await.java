package com.schedula.app;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.fail;

/** Tiny polling helper; avoids adding awaitility for a few call sites. */
final class Await {

    private Await() {
    }

    static <T> T until(Supplier<T> probe, Predicate<T> done, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        T last = null;
        RuntimeException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                last = probe.get();
                lastError = null;
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (lastError == null && done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastError != null) {
            fail("condition not met within " + timeoutMs + "ms: " + lastError);
        }
        fail("condition not met within " + timeoutMs + "ms");
        return last;
    }
}
