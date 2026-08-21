# OBSERVABILITY

Status: Phase 0 proposal. Covers requirements §43–§45, §73, §78. Instrumentation starts
Phase 1 (metrics + structured logs); tracing Phase 2+; dashboards Phase 8.

---

## 1. Correlation identifiers

Every log line, metric (where labelable), span, and queue message carries:

```
tenant_id, job_id, job_execution_id, workflow_id?, workflow_execution_id?,
worker_id?, scheduler_node_id, request_id / trace_id
```

Trace context propagates through the queue: message metadata holds W3C `traceparent`;
worker resumes the submit-time trace across the dispatch hop — a job's full journey is one
trace despite process boundaries and hours of delay.

## 2. Structured logging

JSON lines via logback-encoder; MDC carries correlation ids; **no string-concatenated
"job failed" logs** (§43). Example:

```json
{"ts":"...","level":"WARN","msg":"execution failed; retry scheduled",
 "tenant_id":"t1","job_id":"j-123","job_execution_id":"e-9","attempt":2,
 "error_class":"THROTTLED","retry_delay_ms":3871,"fencing_token":412,
 "trace_id":"..."}
```

Rules: no secrets/tokens/payload dumps (SECURITY.md); levels have documented meanings
(ERROR = needs action, WARN = degraded-but-handled, INFO = state transitions only).

## 3. Metrics (Micrometer → Prometheus)

Minimum set (§44) with types/labels:

| Metric | Type | Labels |
| --- | --- | --- |
| job_submitted_total / started / completed / failed / retried / dead / cancelled | counter | tenant, job_type, outcome |
| queue_depth | gauge | queue, priority-band |
| queue_age (oldest ready msg) | gauge | queue |
| scheduler_lag | timer/histogram | node |
| job_execution_duration | histogram | job_type, outcome |
| job_wait_duration (enqueue→start) | histogram | queue, tenant |
| worker_utilization | gauge | worker |
| worker_failure_total / lease_expiration_total | counter | reason |
| leader_changes_total | counter | node |
| workflow_started/completed/failed_total | counter | workflow |
| tenant_throttled_total | counter | tenant, quota_kind |
| autoscaling_events_total | counter | direction |
| illegal_transition_total | counter | from,to,actor |
| db_deadlocks_total, fenced_write_rejected_total | counter | component |

`fenced_write_rejected_total > 0` is a first-class alert: it means a stale owner acted —
expected during failover tests, alarming in production steady-state.

## 4. Scheduler lag (§45)

Definition: `actual_dispatch_time − scheduled_for` for delayed/scheduled jobs; measured as a
histogram, alerting on P99 (not average — §45 explicitly). Reported per node; leadership
changes annotated so lag spikes correlate with failovers automatically.

## 5. Tracing (OpenTelemetry)

Spans: `submit` → `schedule-evaluate` → `dispatch` → `queue-wait` (span links, not child —
time gap) → `execute` (+ handler child spans) → `finalize`. Retry chains linked to original.
Exporter OTLP → collector; sampling: head-based 10% default, always-on for error traces.

## 6. Health probes & ops surfaces

- `/health/readiness`: registered + dependencies reachable (API: DB; worker: DB + registered).
- `/health/liveness`: process responsive — deliberately NOT "holds leases" (lease loss must
  not restart-loop; recovery handles it).
- `/metrics`, admin query APIs (jobs stuck in CANCELLING > X, DLQ size, per-tenant depth).
- Grafana dashboards (Phase 8): scheduler, workers, queues, jobs, workflows, tenants, DB,
  autoscaling, failure rates, latency percentiles (§73).

## 7. Explainability requirement

For any job id, an operator can reconstruct: why created → when eligible → when enqueued →
who claimed → what happened → why terminal — entirely from `job_events` + spans, without
code access. This is a tested property (TESTING.md includes an "explainability" integration test).
