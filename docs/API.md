# API Reference

Base URL: `http://localhost:8080`
Auth: `X-API-Key` header (tenant scope) or `X-Admin-Key` (platform scope)

## Jobs

### POST /v1/jobs
Submit a job. Returns 201 Created (or 200 if idempotent replay).

```json
{
  "jobType": "echo",
  "payload": {"key": "value"},
  "priority": 0,
  "maxAttempts": 3,
  "timeoutMs": 60000,
  "scheduledFor": "2026-12-25T09:00:00Z",
  "retryPolicy": {"backoff": "EXPONENTIAL_JITTERED", "initialDelayMs": 1000},
  "requiredCapabilities": ["python"],
  "requiredCpu": 2,
  "requiredMemMb": 512,
  "queueName": "billing",
  "webhookUrl": "https://your-app.com/callback"
}
```

Headers: `Idempotency-Key: <your-key>` for dedup.

### GET /v1/jobs/{id}
Returns full job state including attempts, next_retry_at, webhook_state.

### GET /v1/jobs/{id}/executions
Attempt history with fencing tokens and error details.

### GET /v1/jobs/{id}/events
Full event timeline (created, queued, started, completed...).

### GET /v1/jobs?status=RUNNING&limit=50&before=<ISO>
Keyset-paginated listing. Response includes X-Next-Cursor header.

### POST /v1/jobs/batch
Bulk submit up to 500 jobs in one transaction.
Body: `{"jobs": [SubmitRequest...]}`

### POST /v1/jobs/{id}/cancel
Queued/scheduled: sync cancel. Running: cooperative via CANCELLING state.

### POST /v1/jobs/{id}/pause | /resume | /retry

## Schedules

### POST /v1/schedules
```json
{
  "name": "daily-report",
  "jobType": "report",
  "cronExpr": "0 0 9 * * *",
  "timezone": "Europe/Berlin",
  "missedPolicy": "COALESCE",
  "targetWorkflow": null
}
```

### GET /v1/schedules/{id} | DELETE /v1/schedules/{id}

## Workflows

### POST /v1/workflows — register definition (versioned)
```json
{
  "name": "order-flow",
  "definition": {
    "tasks": [
      {"key": "validate", "jobType": "log", "payload": {}},
      {"key": "process", "jobType": "log", "dependsOn": ["validate"],
        "undo": {"jobType": "log", "payload": {"rollback": true}}},
      {"key": "wait", "waitMs": 5000, "dependsOn": ["process"]},
      {"key": "gate", "signal": "approve", "dependsOn": ["wait"]},
      {"key": "sub", "childWorkflow": {"name": "notify"}, "dependsOn": ["gate"]}
    ]
  }
}
```

### POST /v1/workflows/{name}/executions — start execution
### GET /v1/workflows/executions?limit=25 — recent executions
### GET /v1/workflows/executions/{id} — status + task states
### POST /v1/workflows/executions/{id}/signals — deliver signal
### POST /v1/workflows/executions/{id}/cancel — cooperative cancel

## DLQ

| Endpoint | Purpose |
|----------|---------|
| GET /v1/dlq | List dead letters |
| POST /v1/dlq/retry-bulk | Bulk retry by filter |
| DELETE /v1/dlq/delete-bulk | Bulk delete by filter |
| POST /v1/dlq/{msgId}/retry | Retry one |
| DELETE /v1/dlq/{msgId} | Delete one |

## Fleet & Ops

| Endpoint | Purpose |
|----------|---------|
| GET /v1/workers | Worker registry + health + utilization |
| GET /v1/queues | Per-queue depth, claimed, dead-lettered |
| GET /v1/schedulers | Nodes, leader, fencing token |
| GET /v1/fingerprints | Job type P50/P95/P99/success rate |
| GET /v1/health/{type} | Predictive health score (0-100) |
| GET /v1/timeline/{jobId} | Execution decision timeline |

## Admin (requires X-Admin-Key)

| Endpoint | Purpose |
|----------|---------|
| POST /v1/admin/tenants | Create tenant + API key |
| POST /v1/admin/tenants/{id}/rotate | Rotate key |
| GET /v1/admin/audits | Audit trail viewer |

