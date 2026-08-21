package com.schedula.common.retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayCalculatorTest {

    private final SplittableRandom random = new SplittableRandom(42);

    @Test
    void fixedBackoffIsConstant() {
        RetryPolicy p = new RetryPolicy(5, RetryPolicy.Backoff.FIXED, 2_000, 2.0, 60_000);
        assertThat(DelayCalculator.delayMs(p, 1, random)).isEqualTo(2_000);
        assertThat(DelayCalculator.delayMs(p, 4, random)).isEqualTo(2_000);
    }

    @Test
    void exponentialDoublesAndCaps() {
        RetryPolicy p = new RetryPolicy(10, RetryPolicy.Backoff.EXPONENTIAL, 1_000, 2.0, 8_000);
        assertThat(DelayCalculator.delayMs(p, 1, random)).isEqualTo(1_000);
        assertThat(DelayCalculator.delayMs(p, 2, random)).isEqualTo(2_000);
        assertThat(DelayCalculator.delayMs(p, 3, random)).isEqualTo(4_000);
        assertThat(DelayCalculator.delayMs(p, 4, random)).isEqualTo(8_000);
        assertThat(DelayCalculator.delayMs(p, 9, random)).isEqualTo(8_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void jitteredDelayStaysWithinHalfOpenBase(int attempt) {
        RetryPolicy p = new RetryPolicy(10, RetryPolicy.Backoff.EXPONENTIAL_JITTERED, 1_000, 2.0, 16_000);
        long base = Math.min(16_000, (long) (1_000 * Math.pow(2, attempt - 1)));
        for (int i = 0; i < 500; i++) {
            long d = DelayCalculator.delayMs(p, attempt, random);
            assertThat(d).isBetween(0L, base - 1);
        }
    }

    @Test
    void oneBasedAttemptEnforced() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertThatThrownBy(() -> DelayCalculator.delayMs(p, 0, random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyValidationRejectsNonsense() {
        assertThatThrownBy(() -> new RetryPolicy(0, RetryPolicy.Backoff.FIXED, 1, 2, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, RetryPolicy.Backoff.FIXED, 100, 0.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonParsingAppliesDefaults() {
        assertThat(RetryPolicy.fromJson(null)).isEqualTo(RetryPolicy.DEFAULT);
        assertThat(RetryPolicy.fromJson("{}")).isEqualTo(RetryPolicy.DEFAULT);
        RetryPolicy p = RetryPolicy.fromJson("{\"maxAttempts\":5,\"backoff\":\"FIXED\"}");
        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.backoff()).isEqualTo(RetryPolicy.Backoff.FIXED);
        assertThat(p.initialDelayMs()).isEqualTo(RetryPolicy.DEFAULT.initialDelayMs());
    }
}
