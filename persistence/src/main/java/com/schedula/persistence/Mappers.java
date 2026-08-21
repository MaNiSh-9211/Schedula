package com.schedula.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedula.common.jobs.ExecStatus;
import com.schedula.common.jobs.JobStatus;
import com.schedula.common.model.Job;
import com.schedula.common.model.JobExecution;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

final class Mappers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Mappers() {
    }

    static String canonicalize(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            JsonNode node = JSON.readTree(json);
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json: " + e.getMessage(), e);
        }
    }

    static final RowMapper<Job> JOB = (rs, i) -> mapJob(rs);

    static Job mapJob(ResultSet rs) throws SQLException {
        return new Job(
                uuid(rs, "id"),
                uuid(rs, "tenant_id"),
                rs.getString("job_type"),
                rs.getInt("priority"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("payload_json"),
                rs.getInt("max_attempts"),
                rs.getString("retry_policy_json"),
                rs.getLong("timeout_ms"),
                instant(rs, "scheduled_for"),
                uuid(rs, "schedule_id"),
                rs.getString("idempotency_key"),
                rs.getInt("attempts_made"),
                instant(rs, "next_attempt_at"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static final RowMapper<JobExecution> EXECUTION = (rs, i) -> mapExecution(rs);

    static JobExecution mapExecution(ResultSet rs) throws SQLException {
        return new JobExecution(
                uuid(rs, "id"),
                uuid(rs, "job_id"),
                rs.getInt("attempt_no"),
                ExecStatus.valueOf(rs.getString("status")),
                uuid(rs, "worker_id"),
                rs.getLong("fencing_token"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getString("error_class"),
                rs.getString("error_detail"),
                instant(rs, "created_at"));
    }

    static UUID uuid(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
