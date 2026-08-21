package com.schedula.persistence;

import com.schedula.common.model.JobEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class EventStore {

    private static final RowMapper<JobEvent> EVENT = (rs, i) -> new JobEvent(
            rs.getLong("id"),
            Mappers.uuid(rs, "job_id"),
            Mappers.uuid(rs, "job_execution_id"),
            rs.getString("event_type"),
            rs.getString("actor"),
            rs.getString("reason"),
            rs.getObject("fencing_token") == null ? null : rs.getLong("fencing_token"),
            rs.getString("payload_json"),
            Mappers.instant(rs, "occurred_at"));

    private final JdbcTemplate jdbc;

    public EventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID jobId, UUID jobExecutionId, String eventType, String actor,
                       String reason, Long fencingToken) {
        jdbc.update("""
                        INSERT INTO job_events (job_id, job_execution_id, event_type, actor, reason, fencing_token)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                jobId, jobExecutionId, eventType, actor, reason, fencingToken);
    }

    public List<JobEvent> listByJob(UUID jobId) {
        return jdbc.query("SELECT * FROM job_events WHERE job_id = ? ORDER BY id", EVENT, jobId);
    }
}
