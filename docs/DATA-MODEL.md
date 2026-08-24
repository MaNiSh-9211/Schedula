# DATA MODEL

Status: Phase 0 proposal. Covers requirement §8. PostgreSQL is the source of truth (ADR-001).

Rules applied to every table: it must have a purpose, an owner component, a lifecycle, a
consistency requirement, indexes with justifications, and a retention policy. Tables are
introduced in the phase where their problem exists — the schema migrates forward per phase.

---

## 1. Entity groups

```
tenants ─┬─< jobs ──< job_executions ──< job_attempts(merged into executions)
         │        └─< job_events
         ├─< job_schedules            (recurring/cron definitions)
         ├─< workflows ─< workflow_versions
         │        └─< workflow_executions ─< workflow_task_executions
         │                 └─< workflow_timers
         ├─< idempotency_records
         └─< audit_events

workers (cluster-scoped)      scheduler_nodes, scheduler_leases, fence_counters
queues (config)               queue_messages
```

---

## 2. Table catalog

### 2.1 `tenants` — Phase 7 (created early as FK target in Phase 1 with a default tenant)

| Aspect | Decision |
| --- | --- |
| Purpose | Root of isolation; every business row carries `tenant_id`. |
| Owner | API layer |
| Lifecycle | Created by admin; soft-deletable only when no active resources. |
| Consistency | Strong. |
| Retention | Indefinite. |

Key columns: `id uuid pk`, `name`, `status`, `default_priority`, `quota_*` columns or 1:N
`tenant_quotas` (Phase 7).

### 2.2 `jobs`

| Aspect | Decision |
| --- | --- |
| Purpose | One logical unit of work + its desired scheduling. |
| Owner | Job Service |
| Lifecycle | CREATED → terminal (COMPLETED/FAILED/DEAD/CANCELLED); row retained for retention window. |
| Consistency | Strong; all transitions via guarded UPDATE on `(status, version)`. |
| Retention | Terminal rows archived after N days (retention config; §52). |

Columns (Phase 1 scope): `id uuid pk`, `tenant_id fk`, `job_type text`, `priority int`,
`status job_status`, `payload jsonb`, `max_attempts int`, `retry_policy jsonb`,
`timeout_ms bigint`, `scheduled_for timestamptz` (null = immediate), `schedule_id fk null`,
`version bigint` (optimistic concurrency), `created_at/updated_at timestamptz`,
`idempotency_key text null`.

Constraints & indexes:
- `UNIQUE (tenant_id, idempotency_key)` — partial index `WHERE idempotency_key IS NOT NULL`.
  Atomic DB-level dedup; never "check-then-insert" (§20).
- `INDEX (status, scheduled_for) WHERE status IN ('SCHEDULED','RETRY_WAIT')` — the scheduler's
  hot query is "give me due work"; this keeps it an index scan even at 1M+ scheduled jobs.
- `INDEX (tenant_id, created_at DESC)` — tenant-scoped listing APIs.
- `INDEX (status) WHERE status IN ('CANCELLING')` — tiny partial index for the canceller loop.

### 2.3 `job_schedules` — Phase 1 (fixed interval), Phase 5 (cron/timezone)

Purpose: recurring schedule definitions evaluated by the leader scheduler.
Columns: `id`, `tenant_id`, `job_template jsonb`, `kind ('FIXED_INTERVAL'|'CRON')`,
`expr text`, `timezone text`, `next_fire_at timestamptz`, `missed_policy enum`
(`SKIP_TO_LATEST|RUN_ONCE|RUN_ALL|COALESCE`, default COALESCE per ADR-009), `state`,
`last_enqueued_at`.
Indexes: `INDEX (state, next_fire_at)` — the scheduler's due-schedule scan.
Consistency: strong; `next_fire_at` advanced transactionally **in the same transaction** that
enqueues the occurrence, so a crashed scheduler can never double-fire or skip silently.
Retention: until deleted.

### 2.4 `job_executions`

| Aspect | Decision |
| --- | --- |
| Purpose | One attempt of one job; the lease/fencing holder. |
| Owner | Job Service (rows created by dispatcher/scheduler) |
| Lifecycle | PENDING → RUNNING → terminal or RETRY_WAIT (new execution row per attempt). |
| Consistency | Strong; worker writes guarded by `fencing_token`. |
| Retention | Same as parent job; drives history size (§52). |

Columns: `id uuid pk`, `job_id fk`, `attempt_no int`, `status exec_status`, `worker_id null`,
`queue_message_id fk null`, `fencing_token bigint not null` (from `fence_counters`),
`lease_expires_at timestamptz null`, `started_at`, `finished_at`, `error_class`, `error_detail`,
`result_summary jsonb`.
Indexes:
- `INDEX (worker_id, status) WHERE status='RUNNING'` — recovery scan "what was this dead
  worker running?" and worker utilization queries.
- `INDEX (lease_expires_at) WHERE status='RUNNING'` — lease-expiry sweeper.
- `UNIQUE (job_id, attempt_no)`.

### 2.5 `job_events` — append-only

Purpose: audit/debug event stream (§86). One row per state transition / notable action with
`(event_type, actor, reason, fencing_token, payload jsonb, occurred_at)`.
Indexes: `INDEX (job_id, occurred_at)`; `INDEX (event_type, occurred_at)` for ops queries.
This is *not* event sourcing: state lives in `jobs`; events are derived history. Retention:
partitioned by month (Phase 8), archived after N days.

### 2.6 `workers`

Purpose: registry + liveness. Columns: `id uuid pk`, `name`, `version`, `capabilities text[]`,
`cpu_capacity`, `mem_capacity_mb`, `max_concurrency int`, `running_count int` (denormalized,
recomputed on claim/ack), `status ('REGISTERING'|'HEALTHY'|'DRAINING'|'UNHEALTHY'|'DEAD')`,
`last_heartbeat_at timestamptz`, `registered_at`, `draining_since null`.
Indexes: `INDEX (status, last_heartbeat_at)` — failure-detector sweep.
Consistency: heartbeats are high-frequency low-value writes → last-write-wins acceptable;
**liveness decisions** (marking DEAD) are strong-consistency guarded updates.
Retention: DEAD workers pruned after N days.

### 2.7 `scheduler_nodes` / `scheduler_leases` / `fence_counters`

- `scheduler_nodes`: membership + heartbeat (`node_id pk`, `last_seen_at`, `version`, `host`).
- `scheduler_leases`: single-row-per-resource table (`resource_name pk 'SCHEDULER_LEADER'`,
  `owner_node_id`, `expires_at`, `fence_token`). All leadership changes are single-statement
  CAS updates against this row (see COORDINATION.md).
- `fence_counters`: `resource_name pk`, `counter bigint`. Incremented inside the same
  transaction that grants/renews ownership; yields monotonically increasing fencing tokens
  (ADR-004).
Consistency: strong; these rows are the entire point of coordination.

### 2.8 `queues` (config) and `queue_messages`

`queues`: logical queue definitions — `name pk`, `visibility_timeout_ms`, `max_deliveries`,
`dlq_target`, `concurrency_limit_per_job_type`, `tenant_weights jsonb` (Phase 7).

`queue_messages`:

| Aspect | Decision |
| --- | --- |
| Purpose | Durable delivery record between dispatcher and workers (ADR-006). |
| Owner | Queue Layer |
| Lifecycle | READY → CLAIMED → DONE, or READY again (redelivery), or DEADLETTERED. |
| Consistency | Strong; claims via `FOR UPDATE SKIP LOCKED` in one statement. |
| Retention | DONE rows deleted promptly; DEADLETTERED retained until resolved. |

Columns: `id uuid pk`, `queue_name fk`, `job_execution_id fk`, `priority int`,
`enqueue_seq bigint` (per-queue FIFO tiebreaker), `available_at timestamptz` (delayed delivery),
`claim_owner null`, `claim_expires_at null`, `deliver_count int`, `enqueued_at`.
Indexes:
- `INDEX (queue_name, priority DESC, enqueue_seq) WHERE status='READY' AND available_at<=now()`
  shape — the consumer claim query; partial index keeps empty-queue polls cheap (§68 documents
  poll cost).
- `INDEX (claim_expires_at) WHERE status='CLAIMED'` — visibility-timeout reclaimer.

### 2.9 Dead-letter representation

DLQ is a **query over explicit states**, not a separate broker destination: executions end
`DEAD` with `dead_reason`, plus `queue_messages.status='DEADLETTERED'` retaining payload.
Rationale: DLQ operations (inspect/filter/bulk-retry/delete, §22) need joins with job context
anyway; keeping it in PG makes manual retry a transactional state transition with audit.

### 2.10 Workflow tables — Phase 6

- `workflows` (`id`, `tenant_id`, `name`) → `workflow_versions` (`workflow_id`, `version`,
  `definition jsonb` DAG spec, immutable once executions exist — §56 versioning rule).
- `workflow_executions`: `id`, `workflow_version_id`, `tenant_id`, `status`, `input jsonb`,
  `output jsonb`, `version` (optimistic), timestamps.
- `workflow_task_executions`: one row per task node instance: `wf_execution_id`, `task_key`,
  `status`, `depends_on text[]`, `job_execution_id null` (a task may be backed by a real job),
  `retry_policy`, `attempt_no`, `compensation_state`.
- `workflow_timers`: durable timers (§39): `id`, `wf_execution_id`, `fires_at`, `state`,
  consumed by the same due-timer sweep as schedules. A WAIT-24h survives any restart because
  it is a row, not a thread sleep.

Indexes mirror the job pattern: due-timer scan `(state, fires_at)`, dependency resolution
lookups `(wf_execution_id, task_key)` unique.

### 2.11 `idempotency_records`

Purpose: API-level dedup beyond jobs (e.g., workflow submission). `(tenant_id, idem_key pk)`,
`resource_type`, `resource_id`, `request_hash`, `created_at`. Insert-conflict returns the
existing resource id → safe replay semantics for POSTs (§88). Retention: days.

### 2.12 `audit_events`

Immutable records of administrative actions (§54): actor, action, target, before/after hash,
timestamp. Append-only; no UPDATE grant in the app DB role. Retention: long, partitioned.

---

---

## 3. Innovation-Era Tables (V8–V15)

| Table | Purpose | Key columns |
| --- | --- | --- |
| `retry_oracle` | Adaptive retry delay learning per (type, error_class, attempt, bucket) | succeeded, failed counts |
| `job_fingerprints` (view) | Auto-computed P50/P95/P99/success_rate per job_type over 24h | derived, no storage |
| `dependency_health` | Cascade firewall: tracks failure counts + quarantine state per downstream host | host, failure_count, quarantined |
| `worker_affinity` | Per-worker success rate + speed per job type for routing decisions | total_runs, total_success, total_duration_ms |
| `anomaly_baselines` | Welford's online mean/variance per job type for anomaly detection | sample_count, mean_duration_s, m2_duration |
| `health_profiles` | Historical success rate per (job_type, hour_of_day) for predictive scoring | total_completions, total_failures |
| `execution_timeline` | Decision audit: every phase transition with context JSONB | phase, actor, decision |
| `effect_records` | Idempotent effect dedup for handlers (claim-then-run pattern) | tenant_id, job_id, effect_key |
| `workflow_signals` | External events delivered into running workflow executions | signal_name, consumed |

## 4. Cross-cutting conventions

- **IDs:** UUIDv7 (time-ordered) — index-friendly inserts vs random UUIDv4, still globally unique.
- **Time:** all timestamps `timestamptz` stored UTC (ADR-008). Durations computed from
  monotonic clocks in-process; wall clock only crosses process boundaries.
- **Money-like precision:** none needed; payloads are opaque JSONB to the core.
- **Optimistic locking:** `version bigint` on mutable aggregates (`jobs`,
  `workflow_executions`); every mutation is `UPDATE ... SET version=version+1 WHERE id=? AND version=?`.
- **Guarded transitions:** state changes always include expected-state predicates in the WHERE
  clause so concurrent actors cannot corrupt state (see CONSISTENCY.md).
- **Migrations:** Flyway; expand→migrate→contract pattern for rolling deployments (§94).

## 5. Deliberately absent tables (for now)

`queue_partitions` (needs demonstrated hotspot, §32), `worker_leases` as separate table
(lease fields live on executions/messages; extract when leases become multi-object),
`secrets`, `rbac` (Phase 8), archive tables (Phase 8 retention milestone).

