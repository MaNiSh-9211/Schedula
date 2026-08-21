# ADR-010: Weighted fair dispatch (Deficit Round Robin over tenants)

Status: Accepted (Phase 0 design target; implementation Phase 4–5, benchmark-gated)

## Context

§25/§29: priority alone starves low-priority work; FIFO alone lets big tenants starve small
ones. The §29 acceptance scenario is explicit: Tenant A=1M jobs, B=10, C=100 — B must not
starve, and fairness must be *measured*, not asserted.

## Problem

In what order does the dispatcher serve ready messages from many tenants with different
weights and priorities?

## Options considered

1. **Strict global priority.**
2. **Pure round-robin across tenants.**
3. **Weighted Fair Queueing (WFQ) via Deficit Round Robin (DRR) — chosen.**
4. **Lottery / random weighted sampling.**
5. **Proportional-Share with virtual-time (strict WFQ theory).**

## Decision

Dispatcher selects work in two levels:
1. **Across tenants:** DRR with per-tenant weight (default 1) and quantum = N messages;
   deficit counters give long-run service share ∝ weights, O(1) per message.
2. **Within a tenant's turn:** highest priority first, FIFO within priority.

Starvation bound: a non-empty tenant waits at most Σⱼ(quantumⱼ)/wᵢ turns ≈ bounded by
configurable quantum sizes — asserted numerically by the fairness benchmark.

## Why this is best

- Directly answers the measured-fairness requirement with an algorithm whose guarantees are
  provable and whose implementation is simple enough to reason about under contention.
- DRR is the standard practical approximation of WFQ: exact shares asymptotically, constant
  work per dispatch decision, no floating-point/virtual-time bookkeeping to get subtly wrong.
- Priority is preserved where users expect it (within their own tenant) without letting one
  tenant's P0 flood the world.

## Why alternatives were rejected

- **Strict global priority:** reintroduces starvation by construction — the very bug this
  ADR exists to kill (§25).
- **Pure round-robin:** ignores agreed weights; a paying heavy tenant and a trial tenant get
  identical service — no policy lever.
- **Lottery sampling:** probabilistic fairness only; tail behavior (B waiting k consecutive
  losses) needs bounds we'd have to measure statistically rather than prove; weaker story
  for the same complexity.
- **Strict virtual-time WFQ:** theoretically ideal, operationally fragile (timestamp
  bookkeeping across concurrent claim batches); DRR captures ~all the benefit at a fraction
  of the correctness risk. Revisit if benchmark shows share skew beyond tolerance.

## Trade-offs accepted

- A tenant-A P0 can wait behind B's weighted slice for up to one quantum round — bounded,
  configurable, documented (users trading strict global priority for tenant fairness).
- Dispatcher keeps per-tenant state (deficit counters) — trivial memory, but it makes the
  dispatcher stateful across restarts → counters rebuilt from queue statistics on leader
  change (documented recovery path).

## Consequences

Fairness benchmark (§29 scenario + hot-tenant chaos CHAOS-008) is the exit gate; metrics
`dispatch_share_ratio{tenant}` expose actual-vs-configured shares continuously; quantum and
weights fully configurable (§70).

## Revisit triggers

Measured share skew > tolerance or head-of-line latency complaints from high-weight tenants
→ evaluate strict WFQ/SHAPER variants with benchmark evidence; new ADR required.
