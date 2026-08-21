package com.schedula.common.retry;

public record RetryPolicy(
        int maxAttempts,
        Backoff backoff,
        long initialDelayMs,
        double multiplier,
        long maxDelayMs) {

    public enum Backoff { FIXED, EXPONENTIAL, EXPONENTIAL_JITTERED }

    public static final RetryPolicy DEFAULT =
            new RetryPolicy(3, Backoff.EXPONENTIAL_JITTERED, 1_000, 2.0, 60_000);

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialDelayMs < 0) throw new IllegalArgumentException("initialDelayMs must be >= 0");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
        if (maxDelayMs < initialDelayMs) throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
    }

    public static RetryPolicy fromJson(String json) {
        if (json == null || json.isBlank()) return DEFAULT;
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new RetryPolicy(
                    n.path("maxAttempts").asInt(DEFAULT.maxAttempts()),
                    parseBackoff(n.path("backoff").asText(null)),
                    n.path("initialDelayMs").asLong(DEFAULT.initialDelayMs()),
                    n.path("multiplier").asDouble(DEFAULT.multiplier()),
                    n.path("maxDelayMs").asLong(DEFAULT.maxDelayMs()));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid retryPolicy json: " + e.getMessage(), e);
        }
    }

    private static Backoff parseBackoff(String s) {
        if (s == null) return DEFAULT.backoff();
        return Backoff.valueOf(s);
    }
}
