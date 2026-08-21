package com.schedula.common.retry;

import java.util.random.RandomGenerator;

public final class DelayCalculator {

    private DelayCalculator() {
    }

    public static long delayMs(RetryPolicy policy, int attempt, RandomGenerator random) {
        if (attempt < 1) throw new IllegalArgumentException("attempt is 1-based");
        long base = switch (policy.backoff()) {
            case FIXED -> policy.initialDelayMs();
            case EXPONENTIAL, EXPONENTIAL_JITTERED ->
                    cappedExponential(policy, attempt);
        };
        if (policy.backoff() == RetryPolicy.Backoff.EXPONENTIAL_JITTERED) {
            return (long) (random.nextDouble() * base);
        }
        return base;
    }

    private static long cappedExponential(RetryPolicy policy, int attempt) {
        double raw = policy.initialDelayMs() * Math.pow(policy.multiplier(), attempt - 1L);
        if (raw >= policy.maxDelayMs()) return policy.maxDelayMs();
        return Math.max(0, (long) raw);
    }
}
