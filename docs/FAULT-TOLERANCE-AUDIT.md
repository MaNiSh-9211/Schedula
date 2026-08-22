# FAULT-TOLERANCE AUDIT — honest edge-case assessment

Status: living document. Graded against Temporal / AWS Step Functions / Airflow /
Solid Queue (the systems this is compared to). Rule of the repo (§60, §104): no
claim without proof; every gap listed with severity and plan.

---

## 1. Verdict (one paragraph)

Schedula handles the **classic distributed-scheduler failure modes** — worker crash,
lost ACK, duplicate delivery, leader failover, split-brain writes, clock jumps,
DB blips — with tested mechanisms at parity with the Solid Queue / River / pgmq
tier, and its **fencing + lease combination is stronger than Airflow's**. It does
NOT yet match Temporal's event-sourced durability for workflows, it shares the
**industry-unavoidable at-least-once duplication window** for external side
effects, and it has **real unhandled edge cases listed in §3 below** — the biggest
being autovacuum churn under sustained load and client-visible submission loss
during a DB primary failover.

---

## 2. Handled at parity or better (with proof)

| Failure | Mechanism | Proof |
| --- | --- | --- |
| Two workers grab one job | `FOR UPDATE SKIP LOCKED` atomic claim, tx-wrapped (industry checklist item ✓) | QueueClaimIT: 8 workers × 50 msgs → 0 duplicates |
| Worker crash mid-job | claim/lease expiry + redelivery; old execution ABANDONED | ReliabilityIT.expiredClaim… |
| Lost ACK | same path; identical idempotency key delivered to handler | CHAOS-004 pattern |
| Stale worker corrupts state | fencing tokens on every outcome write | AuthIT-era FencedWritesIT |
| Leader dies / split brain | PG lease election, takeover ≤ lease, stale-leader writes inert | LeaseElectionIT ×4 |
| DB failover of app nodes | fail-closed control plane; zero acknowledged-loss | FullFlow under blips |
| Clock jumps on nodes | instants compared DB-side; durations monotonic (ADR-008) | CronSchedule DST suite |
| Transactional enqueue | job+message commit together — dual-write class impossible (Solid Queue's core argument) | architecture invariant |

Context from industry: transactional enqueue is why Rails' Solid Queue runs
20M jobs/day on this exact pattern; measured overhead ≈1.5 ms/task; ceiling
≈1–5k jobs/sec before autovacuum becomes the limit (see §3.1).

---

## 3. KNOWN edge cases still open (ranked)

### 3.1 Autovacuum / dead-tuple churn — HIGH (capacity, not correctness)
Every claim/finalize is an UPDATE → dead tuples at thousands/sec make
`queue_messages`/`jobs` an autovacuum hotspot; industry consensus calls this
"the real capacity limit long before CPU".
**Have:** retention sweeper (bounded batches). 
**Missing:** fillfactor tuning on hot tables, aggressive per-table vacuum settings,
HOT-update friendliness review, pgmq-style partitioning when volume demands.
**Plan:** ops runbook entry + tuned storage params (Phase 9 first item).

### 3.2 External side-effect duplication window — FUNDAMENTAL (all systems)
Effect fired externally → crash before ACK → redelivery = duplicate effect.
Fencing protects our rows, not the universe. **Temporal documents the same**
(activity retries are at-least-once; exactly-once requires user idempotency).
**Mitigation shipped:** bounded window (≤ lease+sweep), idempotency keys end-to-end,
optional EffectLedger checkpoint primitive.
**Residual risk:** handlers that ignore the contract. Mitigated by docs + capability
metadata idea (open).

### 3.3 Client-visible submission ambiguity during DB failover — HIGH
Fail-closed design: during a PG primary loss, API returns errors. A client that
timed out *after* our commit but *before* reading the response cannot know whether
its job exists unless it submitted with an Idempotency-Key. Same ambiguity exists
in every broker-based system; best-in-class answer is "clients MUST use idempotency
keys" — ours supports it but does not enforce/document loudly enough yet.
**Plan:** make key presence a first-class field in client SDKs/docs + replay endpoint.

### 3.4 Workflow engine maturity vs Temporal — MEDIUM
Have: durable DAGs, timers, compensation, versioning, crash-resume-from-rows.
Not comparable yet: event-sourced history/replay-determinism, signals & queries into
running executions, child workflows, local activity model, sticky execution.
Compensation is forward-recovery best-effort (documented), not a saga manager.
**Plan:** signals + child workflows are the two highest-value additions (Phase 6b).

### 3.5 Single-region / single-PG control plane — MEDIUM
No multi-active regions; PG primary is SPoF for scheduling (fail-closed).
Best-in-class multi-region schedulers accept either CRDT-ish partitioned queues or
an async replication lag they surface as staleness. Deliberately out of scope until
a requirement exists (OPEN-QUESTIONS #7).

### 3.6 DB-host clock step — LOW-MED
Node clocks are neutralized (DB-time comparisons). But if the **DB host itself**
steps backward, `now()-based` leases stretch/shrink once. NTP + chrony discipline on
DB hosts is the operational answer; documented requirement, not enforced by us.

### 3.7 Poll latency vs LISTEN/NOTIFY — LOW
Empty-poll cost is index-cheap and backoff-limited, but wake-on-commit would cut
P99 wait-latency meaningfully at low volume. Documented experiment (§68), unbuilt.

### 3.8 Observability depth — LOW-MED
Metrics + alerts exist; **OTel tracing through queue hops is designed but
unimplemented**; no DAG-graph visualization in UI; audit viewer is table-grade.

### 3.9 Misc sharp edges found in self-review
- `jobs.transition(RUNNING→COMPLETED)` is status-guarded but not token-guarded;
  safe today because only the fencing-valid execution finisher reaches that call
  path — fragile-by-convention rather than by constraint (tighten: pass token).
- DLQ bulk-retry lacks per-item quota re-check batching guard.
- Webhook HMAC secret is global, not per-tenant (rotation story pending).
- UI offset pagination on schedules/DLQ (jobs have keyset now).

---

## 4. Scorecard vs named systems

| Dimension | Schedula | Temporal | Airflow | Solid Queue |
| --- | --- | --- | --- | --- |
| At-least-once tasks + dedup keys | ✅ | ✅ | ✅ | ✅ |
| Fencing/stale-owner protection | ✅ tokens | ✅ run IDs | ❌ (zombie kills only) | ⚠️ basic |
| Leader failover bounded + tested | ✅ ≤lease | ✅ | ⚠️ historical pain | n/a (shared DB) |
| Durable workflows w/ timers | ✅ rows | ✅✅ event-sourced | ⚠️ deferral only | ❌ |
| Signals / child workflows | ❌ planned | ✅✅ | ❌ | ❌ |
| Backfill (RUN_ALL) | ✅ capped | n/a model | ✅✅ native | n/a |
| Multi-queue routing + fair share | ✅ WRR+caps | ✅ task queues | ✅ pools | ✅ queues |
| Autovacuum-at-scale story | ⚠️ partial | n/a (own DB) | ⚠️ known pain | ⚠️ documented |
| Exactly-once external effects | ❌ physics | ❌ physics | ❌ physics | ❌ physics |

---

## 5. Immediate action list (executing next)

1. Token-guard the RUNNING→COMPLETED transition (close §3.9 fragility). 
2. Autovacuum/storage runbook for hot tables (fillfactor, per-table settings). 
3. Client-retry guidance: docs + response header carrying idempotency echo. 
4. Workflow signals (post-message into running execution) — Phase 6b start. 
5. OTel tracing spans across submit→dispatch→execute. 
6. Per-tenant webhook secret + rotation endpoint.

Each lands with tests before the next starts.
