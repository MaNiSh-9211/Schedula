# API PROPOSAL

Status: Phase 0 proposal. Covers requirements §55, §88. REST/JSON; resource-oriented;
idempotency-first. Versioned under `/v1`.

---

## Conventions

- **Idempotency:** mutating POSTs accept `Idempotency-Key` header (§88). Same key + same
  request hash ⇒ original response replayed (`200` with `Idempotent-Replay: true`), never a
  duplicate resource. Key scope: `(tenant, endpoint)`. Conflicting payload under same key ⇒ `409`.
- **Errors:** RFC7807-style envelope `{type,title,status,detail,instance,errors?}`.
- **Pagination:** cursor-based (`?limit=50&cursor=...`), cursors opaque+signed.
- **Async semantics:** submission returns `202` with job id once durably committed — not before.
- **Time:** all timestamps UTC ISO-8601.

## Endpoints

### Jobs

| Method & path | Purpose | Notes |
| --- | --- | --- |
| `POST /v1/jobs` | Submit job | Body: type, payload, priority?, scheduled_for?, retry_policy?, timeout_ms?, idempotency fields. `202` |
| `GET /v1/jobs/{id}` | Job + current state | Includes next_attempt_at when RETRY_WAIT |
| `GET /v1/jobs/{id}/executions` | Attempt history | Ordered by attempt_no |
| `POST /v1/jobs/{id}/cancel` | Cancel | QUEUED→CANCELLED sync; RUNNING→CANCELLING `202`; terminal→`409` |
| `POST /v1/jobs/{id}/retry` | Manual retry from FAILED/DEAD | Creates new job linked to original; audited |
| `POST /v1/jobs/{id}/pause` / `resume` | Pause scheduling | PAUSED state machine |

### Schedules (recurring/cron)

| `POST /v1/schedules` | Create recurring definition | cron expr + timezone + missed_policy |
| `GET /v1/schedules/{id}` | Inspect incl. next_fire_at | |
| `DELETE /v1/schedules/{id}` | Stop recurrence | Future occurrences dropped, audited |

### Workflows

| `POST /v1/workflows` | Register definition (versioned) | DAG spec validated server-side (cycles, unknown types) |
| `POST /v1/workflows/{id}/executions` | Start execution | `202`, pinned to a definition version (§56) |
| `GET /v1/workflow-executions/{id}` | Status + task states | |
| `POST /v1/workflow-executions/{id}/cancel` | Cooperative cancel | |

### DLQ

| `GET /v1/dlq` | List/filter dead letters | Filters: tenant, type, error_class, age |
| `GET /v1/dlq/{executionId}` | Full detail + events | |
| `POST /v1/dlq/retry` | Bulk retry | Body: filter or explicit ids; quota-checked; audited |
| `DELETE /v1/dlq/{executionId}` | Remove | admin only; audited |

### Fleet & ops

| `GET /v1/workers` | Registry + health + utilization | |
| `POST /v1/workers/{id}/drain` | Graceful drain trigger | operator action |
| `GET /v1/queues` | Depth, age, config | |
| `GET /v1/schedulers` | Nodes, leader, lease expiry, fencing token | |
| `GET /v1/tenants/{id}/quotas` (+PUT admin) | Quota management | audited |
| `GET /metrics` | Prometheus scrape | unauthenticated internal |

HTTP status discipline: `202` accepted-async, `409` illegal-state transitions (with machine-
readable `type`), `422` validation, `429` quota/backpressure (+Retry-After), `409` on
idempotency conflict. No silent 200s for deferred outcomes.

CLI (§75) is a thin client over these endpoints in Phase 8 — one API surface, two frontends,
no divergent behavior.
