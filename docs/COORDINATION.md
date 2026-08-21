# COORDINATION: Leader Election, Leases, Fencing

Status: Phase 0 proposal. Covers requirements §13–§15, §17, §18, §78.

The coordination problem in one sentence: **multiple processes must agree on who owns a
mutating decision, while assuming any owner can crash, pause, or partition at any moment —
and that "believing" you own something is not the same as owning it.**

---

## 1. Why coordination exists here

Naïve design: run N schedulers, each polls for due jobs. Failure mode: two schedulers see
the same due job and both enqueue it → duplicate execution; or both advance `next_fire_at`
→ skipped occurrences. Naïve fix #2: "grab the row with an UPDATE first" helps per-row but
does not help global decisions (missed-schedule policy sweeps, fairness balancing, retention).
Some operations are inherently single-writer ⇒ we need leadership. And because leaders can
go stale (GC pause past lease expiry, network partition), leadership alone is insufficient
⇒ fencing.

We do NOT implement homemade consensus (§14). We use a lease on a single PostgreSQL row,
with fencing tokens making stale-leader writes harmless. This is a deliberately chosen,
labeled **simplified** mechanism (ADR-005): it depends on PG availability and PG's
linearizability for single-row writes; it is not Raft. The trade-off is documented, and the
revisit trigger is written down.

---

## 2. Lease model

A lease = time-bounded ownership of a resource, recorded durably:

```
scheduler_leases: (resource_name='SCHEDULER_LEADER', owner_node_id, expires_at, fence_token)
job execution:    lease fields on job_executions / queue_messages claims
```

Acquire (single atomic statement):

```sql
UPDATE scheduler_leases
SET owner_node_id = :me, expires_at = now() + :lease_duration,
    fence_token = next_fence('SCHEDULER_LEADER')   -- incremented in same tx
WHERE resource_name = 'SCHEDULER_LEADER'
  AND (owner_node_id = :me OR expires_at < now());  -- re-own or expired
-- 0 rows updated => someone else holds it
```

Renew: same statement but only succeeds while we are still owner (`owner_node_id=:me`).
Lose: renewal returns 0 rows, or a fenced write is rejected ⇒ step down immediately.

### Timing relationships (defaults; all configurable, §70)

```
heartbeat_interval      = 5s     (workers -> registry)
lease_renew_interval    = lease/3
execution_lease         = 15s    (worker job ownership)
leader_lease            = 15s
failure_threshold       = 3 missed heartbeats => UNHEALTHY signal
lease expiry            = the ONLY thing that frees ownership (not heartbeats)
```

Why these ratios: renewal happens 3× per lease, so one lost renewal packet tolerates two
more attempts before risk; failure detection (heartbeat-based, advisory) fires well after a
single blip would have caused harm if we acted on it prematurely. Mass-reassignment storms
from transient network delay are prevented by never acting on heartbeats alone (§17).

Clock caveat (§78): leases compare timestamps **stored by PostgreSQL** (`now()` inside the
statement), not application wall clocks — nodes with skewed clocks cannot shorten or extend
someone else's lease by lying about local time. Durations *within* a process use monotonic
clocks. Residual skew risk (PG server vs itself is nil; cross-node ordering relies on DB
time) documented in FAILURE-MODES.md.

---

## 3. Leader election model

Protocol (all state transitions are single-statement CAS on the lease row):

1. **Boot:** node registers in `scheduler_nodes`, becomes FOLLOWER, probes lease every
   `probe_interval` (default 2s) with jitter to avoid thundering herd.
2. **Acquire:** follower runs Acquire above. Success ⇒ LEADER with fresh `fence_token`.
3. **Exercise leadership:** leader runs schedule evaluation, dispatch, sweeper loops. Every
   mutating statement includes `AND fence_token = :my_token` (or reads-then-writes within a
   transaction that re-validates). Rejection ⇒ token stale ⇒ step down.
4. **Renew:** every `lease/3`. Renewal failure (0 rows) ⇒ step down.
5. **Failover:** leader dies/pauses/partitions. Lease expires ≤ 15s later. Followers'
   acquires start succeeding; winner increments fence counter so any late write from the old
   leader fails its token check.
6. **Old leader returns:** if paused (not dead), its next renew/write fails token check ⇒
   steps down. It may briefly *believe* it is leader; it cannot *act* as leader. This is the
   split-brain answer: not prevention of dual belief, but neutralization of dual action.

What leadership protects (scope): schedule evaluation & occurrence creation, dispatch loop,
sweeper loops (lease expiry reclaim, DLQ moves, retention). What it does NOT gate: API
writes (multi-writer safe via row-level guarded updates), worker execution (protected by
execution leases + fencing instead).

Known limitation (documented, not hidden): during PG unavailability there is no leader and
no scheduling — the system fails closed for control-plane mutations rather than risking
split-brain. Data plane (already-running jobs) continues; their completions queue behind DB
recovery. See FAILURE-MODES.md "DB outage".

---

## 4. Execution leases (worker side)

When a worker claims a queue message it receives an execution lease:
`(worker_id, job_execution_id, claim_expires_at, fencing_token)`.

- Worker renews at `lease/3` while running. Handler code gets a cancellation hook fired when
  renewal fails (cooperative stop attempt).
- Expiry ⇒ message becomes claimable again (visibility timeout semantics); sweeper marks the
  orphaned execution recoverable.
- Old worker may still be alive (pause, partition). Therefore completion/failure writes carry
  `fencing_token`; a superseded worker's ACK hits 0 rows and is discarded — the successor's
  outcome wins. Duplicate execution may already have happened externally; idempotency keys
  make that safe (ADR-002).

---

## 5. Fencing tokens

**Where they live:** `fence_counters(resource_name, counter)`; tokens minted inside the
transaction that grants/renews ownership, so tokens are monotonically increasing per
resource under PG's serial ordering of that row.

**How validated:** every ownership-sensitive mutation appends
`AND fencing_token = :token` (and sets nothing otherwise). Examples:
worker completing an execution; leader enqueuing a scheduled occurrence; leader advancing
`next_fire_at`.

**Generation:** `UPDATE fence_counters SET counter = counter + 1 ... RETURNING counter` —
atomic, gap-free enough (gaps irrelevant; only monotonicity matters).

**Retries:** a client whose fenced write was rejected MUST NOT retry blindly — rejection
means ownership was lost; retrying would be a stale write. The correct behavior is
abandon-and-step-down (leader) or drop-result (worker).

**Database races:** two nodes acquiring simultaneously serialize on the lease row's row
lock; exactly one UPDATE matches. Two fenced writers serialize on the target row; the older
token's UPDATE matches 0 rows. No read-modify-write in application code anywhere in these
paths.

**Cost:** one extra predicate per sensitive write + one counter row per resource. Cheap;
bought insurance against the worst class of bugs (stale-owner corruption).

Limits (honesty per §60): fencing protects *our* state. If a stale worker already fired an
external HTTP call before losing its lease, fencing cannot un-fire it — that class of
duplicate belongs to idempotency design (ADR-002), not fencing.

---

## 6. Relationship summary

```
heartbeats        -> advisory liveness signal (dashboards, UNHEALTHY marking)
leases            -> exclusive, expiring ownership (the real gate)
fencing tokens    -> make actions by ex-owners inert even if they still act
idempotency keys  -> make the residual duplicates (external effects) harmless
```

Each layer covers the failure mode the previous cannot. Removing any one reintroduces a
documented hole.
