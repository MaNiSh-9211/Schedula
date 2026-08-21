# ADR-004: Fencing tokens against stale owners

Status: Accepted (Phase 0)

## Context

ADR-003 grants ownership via leases. But a lease expiring does not stop the old owner: a GC
pause, VM freeze, or network partition can leave an ex-leader or ex-worker fully alive and
acting after someone else took over. §3: "if a lock expired, the previous process stopped" is
a forbidden assumption.

## Problem

How do we prevent a stale (but live) owner from corrupting state that a new owner is now
rightfully mutating?

## Options considered

1. **Trust leases alone** ("expired = gone").
2. **Optimistic version checks only** (`version` column).
3. **Fencing tokens minted at ownership grant (chosen)**.
4. **Two-phase commit / distributed transactions across owners**.

## Decision

Every ownership-sensitive mutation must present the **current fencing token**, checked
atomically in the same UPDATE:

```sql
UPDATE job_executions SET status='COMPLETED', ...
WHERE id=:e AND fencing_token=:myToken;   -- 0 rows ⇒ I am stale ⇒ drop result
```

Tokens come from a monotonic per-resource counter incremented inside the transaction that
grants/renews ownership. Leaders carry a leadership token for scheduling writes; workers
carry execution tokens for outcome writes.

## Why this is best

- Turns "stale owner acts" from corruption into a no-op + metric. The database serializes;
  the older token's write matches zero rows. Safety argument is one sentence and testable.
- Composes with optimistic versions: versions prevent *lost updates among legitimate
  writers*; fencing prevents *illegitimate writers entirely*. Different problems, both needed.
- Zero extra infrastructure — a counter row and a WHERE predicate.

## Why alternatives were rejected

- **Leases alone:** the partition window is exactly when corruption happens; this is the
  classic split-brain hole §13/§14 warn about.
- **Versions only:** a stale worker holding an old row image can still pass a version check
  if it never observed the intermediate bump (read-old, pause, write-old+1 collides — but
  multi-field partial updates can slip through without state predicates); more importantly,
  versions don't express *ownership revocation*, which is the semantic we need.
- **2PC across owners:** nothing to coordinate — the stale writer isn't a participant in a
  transaction, it's an uninvited guest; 2PC addresses availability, not authority.

## Trade-offs accepted

- One extra predicate + counter contention on hot resources (negligible; counters are
  per-resource, updated only on ownership changes).
- Rejected writes require disciplined client behavior: **never retry a fenced rejection** —
  step down / discard. Enforced by wrapping fenced operations so rejection routes to
  step-down logic, and tested (CHAOS-002).

## Consequences

Honesty boundary (§60): fencing protects *our* state, not the universe. A stale worker that
already fired its external HTTP call cannot be un-fired by a rejected ACK — that residual
duplicate belongs to idempotency (ADR-002). Both mechanisms are documented as covering
different halves of the problem.

`fenced_write_rejected_total > 0` in steady-state production is an alert-worthy anomaly;
expected only during failovers/tests.

## Revisit triggers

None. Removing fencing would reintroduce a proven corruption path; cost is trivially low.
