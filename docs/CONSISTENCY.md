# CONSISTENCY MODEL

Status: Phase 0 proposal. Covers requirements §10, §51, §80.

Principle (§80): strong consistency only where ownership and state transitions demand it;
eventual consistency wherever a human dashboard or metric can tolerate staleness. Blanket
strong consistency wastes the database; blanket eventual consistency corrupts state.

---

## 1. Per-data consistency requirements

| Data | Required consistency | Rationale |
| --- | --- | --- |
| Job/execution state transitions | Strong (serialized per row) | Two writers completing/failing the same execution must not both win |
| Schedule occurrence creation | Strong (leader-only + tx) | Double-fire or skip of recurring jobs is a correctness bug |
| Leadership/lease rows | Strong (single-row CAS) | The entire coordination mechanism |
| Fence counters | Strong, monotonic | Token ordering is the safety argument |
| Queue claims | Strong (atomic claim) | Two workers claiming one message defeats delivery semantics |
| Worker heartbeats | Last-write-wins (no CAS) | High-frequency advisory signal; conflicts meaningless |
| Metrics/counters exposed to Prometheus | Eventual (in-memory aggregation, scraped) | Monitoring, not decisions |
| Dashboard/list views | Read-committed snapshot | Stale-by-milliseconds acceptable |
| Audit events | Append-only, strongly ordered per target | Legal/debug trail |
| Workflow task dependency state | Strong per workflow execution | Dependency races = wrong DAG semantics |

---

## 2. Isolation levels and patterns (§51)

Default: **READ COMMITTED** (PostgreSQL default). Chosen because all critical mutations use
patterns that are safe under READ COMMITTED:

1. **Guarded update (compare-and-set):**
   `UPDATE jobs SET status='RUNNING', version=version+1 WHERE id=? AND status='DISPATCHED' AND version=?`
   — concurrent conflicting transition affects 0 rows; writer re-reads and reacts. No lost
   update possible regardless of interleaving.

2. **Atomic claim (SKIP LOCKED):**
   ```sql
   UPDATE queue_messages m SET status='CLAIMED', claim_owner=:w, claim_expires_at=...
   WHERE m.id = (
     SELECT id FROM queue_messages
     WHERE queue_name=:q AND status='READY' AND available_at <= now()
     ORDER BY priority DESC, enqueue_seq
     FOR UPDATE SKIP LOCKED LIMIT :batch)
   RETURNING ...
   ```
   Contention-free claiming: blocked rows are skipped, not waited on; N workers scale
   without lock queues. This is the canonical Postgres work-queue pattern; deadlock-free by
   single-statement construction.

3. **Transactional occurrence creation:** schedule tick = one tx { insert occurrence/message;
   advance next_fire_at } — crash between them impossible; duplicates impossible (row lock
   serializes; leader+fencing makes concurrent leaders moot anyway).

Why NOT SERIALIZABLE everywhere: throughput and serialization-failure retry complexity for
zero benefit — our hot paths don't need multi-row snapshot guarantees; they need targeted
atomicity, which the above provides. Where a genuinely multi-row invariant appears
(e.g., workflow fan-out), we use explicit `SELECT ... FOR UPDATE` on the parent aggregate
row first (lock ordering documented: always parent→children, alphabetically within level ⇒
deadlock-free by construction).

Deadlock policy: consistent lock ordering + statement timeout + retry-once-on-deadlock with
jitter; deadlock rate is a tracked metric (`db_deadlocks_total`) — spikes indicate an
ordering violation, which is a bug, not an operational hiccup.

Connection pools: sized per component role (API vs scheduler vs worker), documented defaults,
pool exhaustion alarms; pool saturation is treated as backpressure signal, not an error to
retry-loop through.

---

## 3. What "consistent" means operationally

- After any successful API mutation, subsequent reads (any node) observe it: read-your-writes
  via single-commit visibility (PG).
- Cross-entity invariants (job ↔ execution counts, workflow ↔ task states) are maintained
  transactionally, never reconciled asynchronously — reconciliation jobs exist only for
  *derived* data (metrics rollups), where drift is cosmetic.
- The system never exposes two truths: there is one durable store; caches (if ever added)
  would be invalidation-managed and documented as eventual (none planned pre-Phase 8).
