# ADR-003: Lease-based ownership for jobs and leadership

Status: Accepted (Phase 0)

## Context

Multiple processes act on shared work: schedulers decide when things run; workers execute
and report outcomes. Any of them can crash, pause (GC/VM), or partition — and §3 forbids
assuming "alive = healthy" or "lock expired = previous holder stopped".

## Problem

How do we grant exclusive, recoverable ownership of a resource (a job execution, scheduler
leadership) without trusting liveness signals?

## Options considered

1. **Heartbeat-only coordination** ("whoever heartbeats owns it").
2. **Distributed locks without expiry** (PG advisory locks held indefinitely).
3. **Consensus-based ownership per resource** (Raft/ZK per decision).
4. **Time-bounded leases + fencing (chosen)**.

## Decision

Ownership is always a **lease**: a durable row stating owner, expiry, and an associated
fencing token. Holders renew at lease/3. Expiry — not heartbeat loss, not belief — is the
only event that frees ownership for reassignment. Heartbeats remain advisory health signals
only (§17).

## Why this is best

- Leases convert "is it alive?" (unknowable) into "did it renew in time?" (checkable).
- Recovery is bounded and automatic: worst case = lease duration + sweep interval.
- Paused-but-alive owners are handled correctly: they lose the lease, and fencing (ADR-004)
  neutralizes their late writes.
- Cheap: one row per owned resource; no extra infrastructure.

## Why alternatives were rejected

- **Heartbeat-only:** no exclusivity — two workers can both believe they own a job after a
  partition; also conflates liveness signal with ownership grant, the exact conflation §17
  forbids.
- **Locks without expiry:** crashed holder deadlocks the system until manual cleanup;
  PG advisory locks are session-bound (connection death releases them — helpful but not
  sufficient: process pause keeps session alive while doing nothing).
- **Per-resource consensus:** correct but wildly disproportionate — a consensus round per
  job claim would cap throughput at consensus latency; leadership is the only truly global
  decision, and even that uses a lease (ADR-005) rather than homegrown consensus.

## Trade-offs accepted

- Time-based: requires sane clocks for *expiry comparison* — mitigated by comparing DB-side
  timestamps (`now()` in-statement), never app wall clocks (ADR-008).
- Renewal traffic: 3 writes/lease-period per active execution — measured, acceptable,
  batched where hot.
- Fencing is mandatory to be safe: a lease alone still allows stale-owner writes during the
  partition window → see ADR-004 (leases without fencing are half a design).

## Consequences

Lease durations become critical config with documented ratios (heartbeat < renewal×k <
lease); failure-detection thresholds tuned to avoid mass-reassignment storms on transient
blips; every lease grant/renewal/reclaim emits events for MTTR measurement (§77).

## Revisit triggers

If renewal write volume becomes measurable DB load at target scale → batch renewals or
lengthen leases with cancellation-cooperativeness requirements (longer leases trade recovery
latency for stability — decided by benchmark, not vibes).
