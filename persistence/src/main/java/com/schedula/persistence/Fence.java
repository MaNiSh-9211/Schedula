package com.schedula.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Monotonic fencing tokens (ADR-004). Must be called inside the transaction that grants
 * ownership so token minting serializes with the ownership write.
 */
@Repository
public class Fence {

    public static final String EXECUTION = "EXECUTION";

    private final JdbcTemplate jdbc;

    public Fence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextToken(String resource) {
        Long token = jdbc.queryForObject(
                "UPDATE fence_counters SET counter = counter + 1 WHERE resource_name = ? RETURNING counter",
                Long.class, resource);
        if (token == null) {
            throw new IllegalStateException("fence counter missing for resource " + resource);
        }
        return token;
    }
}
