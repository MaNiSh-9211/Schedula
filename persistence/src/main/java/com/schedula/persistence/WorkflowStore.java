package com.schedula.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedula.common.model.WorkflowDefinition;
import com.schedula.common.model.WorkflowExecution;
import com.schedula.common.model.WorkflowTask;
import com.schedula.common.model.WorkflowTimer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkflowStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<WorkflowExecution> EXEC = (rs, i) -> new WorkflowExecution(
            Mappers.uuid(rs, "id"),
            Mappers.uuid(rs, "tenant_id"),
            Mappers.uuid(rs, "workflow_version_id"),
            WorkflowExecution.Status.valueOf(rs.getString("status")),
            rs.getBoolean("compensated"),
            rs.getString("input"),
            rs.getString("output"),
            rs.getLong("version"),
            Mappers.instant(rs, "created_at"),
            Mappers.instant(rs, "updated_at"));

    private static final RowMapper<WorkflowTask> TASK = (rs, i) -> new WorkflowTask(
            Mappers.uuid(rs, "id"),
            Mappers.uuid(rs, "wf_execution_id"),
            rs.getString("task_key"),
            WorkflowTask.Kind.valueOf(rs.getString("kind")),
            rs.getString("undo_for"),
            WorkflowTask.Status.valueOf(rs.getString("status")),
            listFrom(rs.getArray("depends_on")),
            rs.getString("job_type"),
            rs.getString("payload_json"),
            rs.getInt("attempt_no"),
            rs.getInt("max_attempts"),
            rs.getObject("wait_ms") == null ? null : rs.getLong("wait_ms"),
            Mappers.uuid(rs, "job_id"),
            rs.getString("error_class"),
            rs.getString("error_detail"),
            Mappers.instant(rs, "started_at"),
            Mappers.instant(rs, "finished_at"));

    private static List<String> listFrom(java.sql.Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        return List.of((String[]) a.getArray());
    }

    private final JdbcTemplate jdbc;

    public WorkflowStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- definitions -----------------------------------------------------------

    public record Registered(UUID workflowId, UUID versionId, int version) {
    }

    /** Registers a NEW immutable version; re-registering the same name bumps the version. */
    public Registered register(UUID tenantId, String name, WorkflowDefinition def) {
        UUID wfId = jdbc.query("""
                        INSERT INTO workflows (id, tenant_id, name) VALUES (?, ?, ?)
                        ON CONFLICT (tenant_id, name) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id
                        """, (rs, i) -> Mappers.uuid(rs, "id"), 
                com.schedula.common.ids.UuidV7.generate(), tenantId, name).get(0);
        Integer maxVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM workflow_versions WHERE workflow_id = ?",
                Integer.class, wfId);
        int nextVersion = maxVersion + 1;
        UUID versionId = com.schedula.common.ids.UuidV7.generate();
        jdbc.update("""
                INSERT INTO workflow_versions (id, workflow_id, version, definition)
                VALUES (?, ?, ?, ?::jsonb)
                """, versionId, wfId, nextVersion, def.toJson());
        return new Registered(wfId, versionId, nextVersion);
    }

    public Optional<UUID> latestVersionId(UUID workflowId) {
        return jdbc.query("""
                        SELECT id FROM workflow_versions WHERE workflow_id = ?
                        ORDER BY version DESC LIMIT 1
                        """, (rs, i) -> Mappers.uuid(rs, "id"), workflowId).stream().findFirst();
    }

    public Optional<String> definitionOf(UUID versionId) {
        return jdbc.query("SELECT definition::text AS d FROM workflow_versions WHERE id = ?",
                (rs, i) -> rs.getString("d"), versionId).stream().findFirst();
    }

    public Optional<UUID> findByName(UUID tenantId, String name) {
        return jdbc.query("SELECT id FROM workflows WHERE tenant_id = ? AND name = ?",
                (rs, i) -> Mappers.uuid(rs, "id"), tenantId, name).stream().findFirst();
    }

    // --- executions ------------------------------------------------------------

    public WorkflowExecution createExecution(UUID tenantId, UUID versionId, String inputJson) {
        UUID execId = com.schedula.common.ids.UuidV7.generate();
        jdbc.update("""
                INSERT INTO workflow_executions (id, tenant_id, workflow_version_id, status, input)
                VALUES (?, ?, ?, 'RUNNING', ?::jsonb)
                """, execId, tenantId, versionId,
                inputJson == null ? "{}" : inputJson);
        return findExecById(execId).orElseThrow();
    }

    public Optional<WorkflowExecution> findExecById(UUID id) {
        return jdbc.query("SELECT * FROM workflow_executions WHERE id = ?", EXEC, id)
                .stream().findFirst();
    }

    public List<WorkflowExecution> findOpen() {
        return jdbc.query("""
                SELECT * FROM workflow_executions
                WHERE status IN ('RUNNING','FAILING','COMPENSATING')
                ORDER BY created_at LIMIT 100
                """, EXEC);
    }

    public List<WorkflowExecution> findRecent(int limit) {
        return jdbc.query("SELECT * FROM workflow_executions ORDER BY created_at DESC LIMIT ?",
                EXEC, limit);
    }

    /** Guarded CAS on execution state/version. */
    public boolean casStatus(UUID execId, long expectedVersion,
                             WorkflowExecution.Status target, Boolean compensated) {
        int n = jdbc.queryForObject("""
                WITH moved AS (
                    UPDATE workflow_executions SET status = ?, compensated = COALESCE(?, compensated),
                        version = version + 1, updated_at = now()
                    WHERE id = ? AND version = ?
                    RETURNING id)
                SELECT count(*) FROM moved
                """, Integer.class, target.name(), compensated, execId, expectedVersion);
        return n != 0;
    }

    // --- tasks -----------------------------------------------------------------

    public void insertTasks(UUID execId, WorkflowDefinition def) {
        for (var t : def.tasks()) {
            String kind;
            if (t.waitMs() != null) kind = "WAIT";
            else if (t.signalName() != null && !t.signalName().isBlank()) kind = "SIGNAL";
            else if (t.childWorkflow() != null && !t.childWorkflow().isBlank()) kind = "CHILD";
            else kind = "JOB";
            jdbc.update("""
                    INSERT INTO workflow_task_executions
                        (id, wf_execution_id, task_key, kind, status, depends_on, job_type,
                         payload_json, max_attempts, wait_ms)
                    VALUES (?, ?, ?, ?, 'BLOCKED', ?, ?, ?::jsonb, ?, ?)
                    """,
                    com.schedula.common.ids.UuidV7.generate(), execId, t.key(),
                    kind,
                    (t.dependsOn() == null
                            ? java.util.Collections.<String>emptyList() : t.dependsOn())
                            .toArray(new String[0]),
                    t.jobType(),
                    t.payload() == null ? "{}" : t.payload().toString(),
                    t.maxAttempts() == null ? 3 : t.maxAttempts(),
                    t.waitMs());
        }
    }

    public List<WorkflowTask> tasksFor(UUID execId) {
        return jdbc.query("""
                SELECT * FROM workflow_task_executions WHERE wf_execution_id = ?
                ORDER BY task_key
                """, TASK, execId);
    }

    /** Guarded single-task transition used everywhere so races cannot corrupt the DAG. */
    public boolean transitionTask(UUID taskId, WorkflowTask.Status expectedFrom,
                                  WorkflowTask.Status to) {
        int n = jdbc.queryForObject("""
                WITH moved AS (
                    UPDATE workflow_task_executions SET status = ?
                    WHERE id = ? AND status = ?
                    RETURNING id)
                SELECT count(*) FROM moved
                """, Integer.class, to.name(), taskId, expectedFrom.name());
        return n != 0;
    }

    public void attachJob(UUID taskId, UUID jobId, int attemptNo) {
        jdbc.update("""
                UPDATE workflow_task_executions SET job_id = ?, attempt_no = ?,
                    started_at = CASE WHEN started_at IS NULL THEN now() ELSE started_at END
                WHERE id = ?
                """, jobId, attemptNo, taskId);
    }

    public void markFailed(UUID taskId, String errorClass, String detail) {
        jdbc.update("""
                UPDATE workflow_task_executions SET finished_at = now(),
                    error_class = ?, error_detail = ? WHERE id = ?
                """, errorClass, detail, taskId);
    }

    public void markSucceededAt(UUID taskId) {
        jdbc.update("UPDATE workflow_task_executions SET finished_at = now() WHERE id = ?", taskId);
    }

    public void insertUndoTask(UUID execId, String undoKey, String undoFor,
                               String jobType, String payloadJson) {
        jdbc.update("""
                INSERT INTO workflow_task_executions
                    (id, wf_execution_id, task_key, kind, undo_for, status, job_type, payload_json, attempt_no)
                VALUES (?, ?, ?, 'UNDO', ?, 'BLOCKED', ?, ?::jsonb, 0)
                ON CONFLICT (wf_execution_id, task_key) DO NOTHING
                """,
                com.schedula.common.ids.UuidV7.generate(), execId, undoKey, undoFor,
                jobType, payloadJson);
    }

    // --- timers ----------------------------------------------------------------

    public void insertTimer(UUID execId, String taskKey, Instant firesAt) {
        jdbc.update("""
                INSERT INTO workflow_timers (id, wf_execution_id, task_key, fires_at)
                VALUES (?, ?, ?, ?)
                """, com.schedula.common.ids.UuidV7.generate(), execId, taskKey,
                Timestamp.from(firesAt));
    }

    public List<WorkflowTimer> dueTimers(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM workflow_timers WHERE state = 'ACTIVE' AND fires_at <= ?
                ORDER BY fires_at LIMIT ?
                """, (rs, i) -> new WorkflowTimer(
                        Mappers.uuid(rs, "id"), Mappers.uuid(rs, "wf_execution_id"),
                        rs.getString("task_key"), Mappers.instant(rs, "fires_at"),
                        rs.getString("state")),
                Timestamp.from(now), limit);
    }

    /** Fire-at-most-once: only one caller flips ACTIVE->FIRED. */
    public boolean fireTimer(UUID timerId) {
        return jdbc.update(
                "UPDATE workflow_timers SET state = 'FIRED' WHERE id = ? AND state = 'ACTIVE'",
                timerId) == 1;
    }

    // --- signals ---------------------------------------------------------------

    public void insertSignal(UUID execId, String signalName, String payloadJson) {
        jdbc.update("""
                INSERT INTO workflow_signals (id, wf_execution_id, signal_name, payload_json)
                VALUES (?, ?, ?, ?::jsonb)
                """, com.schedula.common.ids.UuidV7.generate(), execId, signalName,
                payloadJson == null ? "{}" : payloadJson);
    }

    public List<Map<String, Object>> unconsumedSignals(UUID execId, String signalName) {
        return jdbc.queryForList("""
                SELECT id, signal_name, payload_json::text AS payload
                FROM workflow_signals
                WHERE wf_execution_id = ? AND signal_name = ? AND consumed = FALSE
                ORDER BY created_at LIMIT 1
                """, execId, signalName);
    }

    public boolean consumeSignal(UUID signalId) {
        return jdbc.update(
                "UPDATE workflow_signals SET consumed = TRUE WHERE id = ? AND consumed = FALSE",
                signalId) == 1;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}


