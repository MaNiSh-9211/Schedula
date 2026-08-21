# ADR-005: Leader election via PostgreSQL lease (labeled: simplified mechanism)

Status: Accepted (Phase 0)

## Context

Some operations must be single-writer at a time: schedule occurrence creation, dispatch
loop, sweeper passes. Multiple scheduler nodes will run (Phase 3+). §14 requires protecting
against stale leaders and split brain; §103 requires labeling simplified mechanisms honestly.

## Problem

How do N identical scheduler nodes agree on one leader — with automatic failover — without
building or operating a consensus cluster?

## Options considered

1. **PostgreSQL lease row + fencing tokens (chosen).**
2. **etcd / ZooKeeper** (real consensus).
3. **Kubernetes Lease API / leader-election primitives.**
4. **Redis Redlock-based election.**
5. **Homemade Raft implementation.**

## Decision

A single `scheduler_leases` row is the leadership truth. Candidates acquire/renew via
single-statement CAS (`owner=:me OR expires_at<now()`), each grant minting a fresh fencing
token (ADR-004). Renewal failure or any fenced-write rejection ⇒ immediate step-down.
Failover bound = lease duration (default 15s).

## Why this is best

- Correctness for our actual need: we don't need general consensus, we need *at most one
  effective writer* — which lease+fencing provides even when two nodes briefly believe
  they lead (the stale one's writes are inert).
- Zero new infrastructure: the store we already depend on arbitrates. One failure domain
  instead of two; PG-down already means control-plane fail-closed (documented), so adding
  etcd wouldn't keep scheduling alive anyway — it would just elect a leader that can't
  reach the data.
- Bounded, testable failover; measured in Phase 3 (INCIDENT-001 drill).

## Why alternatives were rejected

- **etcd/ZK:** genuinely stronger availability semantics (consensus survives store-node
  loss), but adds an always-on cluster, new client/failure modes, and operational surface —
  while the bottleneck resource (PG) being down still halts us. Revisit if/when coordination
  must outlive DB outages (open question logged).
- **K8s Lease:** couples dev (compose) and prod coordination paths to k8s; breaks local
  parity; also only as strong as its API server availability — same class of trade-off with
  less portability.
- **Redlock:** documented safety controversy under partitions/clock jumps (Martin Kleppmann
  vs antirez debate) — the exact failure modes this project exists to take seriously;
  rejected on safety grounds, not fashion.
- **Homemade Raft:** §14 explicitly forbids unless requested; a correct Raft is weeks of
  edge-case work to re-derive what a lease+token already safely provides at our scale.

## Limitations (stated plainly per §103)

- This is NOT consensus: during a PG partition, there may be no leader (fail closed) rather
  than a functioning one (fail open). Chosen deliberately: silent dual-writers are worse
  than a scheduling pause.
- Safety depends on PG single-row write linearizability — solid within one primary; failover
  of PG itself has its own RPO/RTO story documented in FAILURE-MODES.md.

## Trade-offs accepted

- Leadership gap ≤ lease duration on crash (bounded unavailability).
- Leader node is a throughput hotspot by design (sharding sketch exists; measured trigger in
  ROADMAP Phase 4/9).

## Consequences

All leader mutations carry token predicates; followers stay warm (probing, non-mutating);
`leader_changes_total` and takeover-latency histograms are first-class metrics from day one
of Phase 3.

## Revisit triggers

Requirement for scheduling continuity through PG-primary loss, or coordination latency
becoming measurable in failover drills → evaluate etcd with measurements; new ADR required.
