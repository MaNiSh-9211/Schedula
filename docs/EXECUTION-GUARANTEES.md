# EXECUTION GUARANTEES

Status: Phase 0 proposal. Covers requirements §4, §19–§27, §31, §67, §79, §85, §88, §104.

---

## 1. The guarantee, stated precisely

> Jobs are executed **at-least-once**, with **bounded duplication windows**, and all
> effects are designed to be made **idempotent** via keys. Delivery durability is
> **durable-before-ack**: submission returns success only after the job row is committed.

We do NOT claim exactly-once. Reason (§4, §19): the classic sequence —

1. worker delivers effect to external system (HTTP call lands)
2. worker crashes before ACK
3. lease expires, job redelivered
4. successor executes again → duplicate external effect

No protocol can prevent this without cooperation from the external system; therefore the
system's contract is: duplicates are *possible*, *bounded* (≤ once per lease-expiry window),
*detectable* (attempt count, events), and *neutralizable* (idempotency keys propagated to
handlers).

Per-operation guarantees table:

| Operation | Guarantee |
| --- | --- |
| Job submission | Durable before ack; idempotent via `(tenant_id, idempotency_key)` unique constraint |
| Scheduling → enqueue | Exactly-once occurrence creation per schedule tick (leader + fencing + tx) |
| Queue delivery | At-least-once; redelivery on visibility-timeout expiry or explicit nack |
| Execution | At-least-once; duplicates bounded by lease window; handlers receive idempotency key |
| State recording | Exactly-once final outcome per attempt (guarded by fencing token) |

---

## 2. Retry model (§21)

Policy object stored per job (`retry_policy jsonb`), evaluated by pure functions (unit-tested):

```
RetryPolicy {
  max_attempts            int      (default 3)
  backoff                 FIXED | EXPONENTIAL | EXPONENTIAL_JITTERED (default jittered)
  initial_delay_ms        1000
  multiplier              2.0
  max_delay_ms            60_000
  jitter                  FULL     (see below)
  retry_on                [error classifications]   default: TRANSIENT
  non_retryable_on        [error classifications]   default: VALIDATION, PERMANENT
}
```

Delay formula (exponential + full jitter):
`delay = random(0, min(max_delay_ms, initial * multiplier^(attempt-1)))`

Jitter choice: **full jitter** over equal jitter/guessed decorrelation initially — simplest,
well-understood, prevents synchronized retry waves (retry storms, §21). Benchmark can revisit.

Classification: handlers throw typed errors mapped to classes (`TRANSIENT`, `PERMANENT`,
`VALIDATION`, `THROTTLED`). Defaults: THROTTLED/TRANSIENT retry; VALIDATION/PERMANENT go
straight to terminal failure. Unknown errors default to TRANSIENT with cap — logged loudly.

Storm prevention: backoff above + per-job-type concurrency caps (§26) + tenant quotas (§27)
+ no retries from inside handler code (§67: framework-owned retries only).

Exhaustion ⇒ DEAD + DLQ entry + `job_dead_total` metric + alert annotation. Never silent (§22).

DLQ operations: list/filter (by tenant, type, error class, age), inspect (full payload +
event history), retry-one, retry-bulk (creates new jobs, batched, quota-checked), delete
(admin, audited).

---

## 3. Queue model (§31, ADR-006)

Semantics documentation contract (required by §7 for any queue — ours included):

| Property | Semantics |
| --- | --- |
| Producer | Enqueue is transactional with the state change that warrants it (same PG tx) — no outbox needed because store==broker (ADR-001/006) |
| Consumer | Explicit claim (`FOR UPDATE SKIP LOCKED`), visibility timeout via `claim_expires_at`, ack deletes/marks done, nack or expiry redelivers |
| Ordering | FIFO per queue by `enqueue_seq` **within same priority**; priority dequeues first. NO global ordering guarantee across priorities/queues (stated per §104) |
| Delivery | At-least-once; `deliver_count` capped by `max_deliveries` then DEADLETTERED |
| Delayed delivery | `available_at` future timestamp; claim query filters on it |
| Priority | Integer levels; starvation handled at dispatch fairness layer (MULTI-TENANCY.md), not by breaking strict priority globally |
| Partitioning | Not implemented initially (§32: need demonstrated first); partition key candidate = tenant_id when it arrives |
| Persistence | Same database as job state — atomic with everything else |
| Failure behavior | DB down ⇒ queue unavailable (fail closed); messages durable across all crashes |

Polling honesty (§68): consumers long-poll with interval 200ms–2s (configurable), exponential
backoff to 5s on empty queues with jitter, partial index makes empty polls index-only scans.
Documented cost per poll and why event-driven (LISTEN/NOTIFY) is a measured optimization
later, not a correctness need.

Backpressure (§27): admission control at API (tenant queue-depth quota → reject 429 with
Retry-After), dispatcher capacity checks (no claiming without compatible worker capacity),
bounded in-flight per worker (max_concurrency). Choice: **reject at admission, queue inside
limits, throttle via fairness** — deliberate, documented.

---

## 4. Timeout taxonomy (§23, §79 — deliberately separate concepts)

| Timeout | Default | Purpose | Owner |
| --- | --- | --- | --- |
| API request timeout | 10s | HTTP hygiene | API |
| DB statement timeout | 5s | Bound lock waits / pathological queries | Persistence |
| Queue visibility timeout | 300s | Redelivery if worker silent | Queue layer |
| Execution (job) timeout | per-job, required for long types | Kill/signal handler overrun | Worker runtime |
| Lease duration | 15s | Ownership expiry | Coordination |
| Heartbeat interval/threshold | 5s / 3 misses | Liveness signal | Worker manager |
| Leader lease | 15s | Leadership expiry | Coordination |
| Workflow deadline | per-workflow | Whole-DAG budget | Workflow engine |
| Retry delay | policy | Backoff between attempts | Retry engine |
| Graceful drain deadline | 30s | Shutdown bound | All processes |

Rule: no component reads another's timeout value; each is independently configured.

---

## 5. Cancellation (§24)

- QUEUED/SCHEDULED: dequeue-or-prevent atomically. Race vs dispatcher resolved by doing
  cancel and claim against the same rows in PG serialization order — one wins, audited.
- RUNNING: cooperative only. Set CANCELLING; worker observes flag (polled with lease renewals /
  signaled via renewal response), handler cancellation hook invoked. Worker confirms →
  CANCELLED. If lease expires first, sweeper closes it CANCELLED.
- We do NOT pretend to hard-kill arbitrary code (§24). Handlers document whether they honor
  cancellation; honoring it is part of handler contract tests.
- CANCELLING is a real state, visible, queryable, alertable if stuck > threshold.

---

## 6. Idempotency (§20, §88)

Two layers:
1. **Submission dedup:** `UNIQUE (tenant_id, idempotency_key)` on jobs (+ `idempotency_records`
   for workflows). Concurrent identical submits race on the constraint; loser fetches winner's
   resource and returns it (200/303 semantics documented in API.md). Never check-then-insert.
2. **Effect dedup:** every execution passes `idempotency_key = tenant_id:job_id:attempt_scope`
   to the handler; handler contract says: derive external operation keys from it. Framework
   provides an optional `EffectLedger` helper (records effect-key → result in PG) for handlers
   that want checkpointed effects ("did I already send this email?").

Exactly-once remains impossible for opaque external effects (§85); the ledger pattern shrinks
duplicates to "effects whose external system ignored our key" — documented honestly.

---

## 7. External side effects guidance (§85)

Documented patterns handlers should use, in strength order:
1. Idempotent-by-design APIs (pass our key upstream).
2. EffectLedger checkpoints (effect recorded in same tx as state change where possible).
3. Compensation (workflow-level undo; forward recovery, NOT rollback — named precisely).
Outbox pattern: unnecessary while store==broker (single tx); revisit if a broker is ever
introduced (trigger condition in ADR-006).
