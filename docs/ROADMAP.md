# ROADMAP

Status: Phase 0. Covers requirement §98 (phases), §59 (methodology per feature).

Methodology binding every phase: for each feature — explain problem → requirements →
invariants → failure modes → data model → APIs → transitions → smallest correct
implementation → tests → run → inject failure → fix recovery → benchmark → document → only
then optimize/expand.

---

## Phase 0 — Architecture (COMPLETE)

Deliverable: this documentation package + ADR set. Exit criteria: all §107 items covered;
guarantees stated precisely; no unjustified technology. ✔

## Phase 1 — Single-node scheduler

**Goal:** correct single-process core; everything else builds on these bones.
- Scope: job CRUD API, durable jobs, immediate + delayed execution, fixed-interval schedules,
  basic worker runtime (typed handlers, HTTP callback type), full job state machine,
  retry engine with classification/backoff/jitter, execution+job timeouts, in-process
  dispatcher with simple priority claim.
- Invariants proven by tests: state machine legality; durable-before-ack; restart loses
  nothing submitted; retries honor policy exactly.
- **Exit criteria:** unit + Testcontainers integration green; kill -9 mid-execution test
  shows recovery on restart; scheduler-lag metric emitted; README quickstart works from compose.

## Phase 2 — Reliable single node

**Goal:** survive failures without operator intervention.
- Scope: execution leases + renewal, worker registration/heartbeat/failure detection,
  lease-expiry sweeper recovery, idempotency keys end-to-end (+EffectLedger helper),
  DLQ with admin ops, audit events, graceful shutdown/drain, cancellation incl. CANCELLING,
  OTel tracing through queue.
- Failure tests: worker crash before ACK (duplicate-effect scenario §47), lost ACK,
  pause-past-lease (faked clock), DB blip during finalize.
- **Exit criteria:** chaos scenarios automated and passing; MTTR(lease-expiry) measured ≤
  lease duration + sweep interval; zero lost submissions across fault suite.

## Phase 3 — Distributed scheduler

**Goal:** N schedulers, one leader, safe failover.
- Scope: scheduler_nodes membership, leader lease election/renewal/step-down, fencing tokens
  on all leader writes + worker writes, stale-leader/stale-worker rejection paths,
  failover metrics.
- Tests: leader kill → bounded takeover; partition old leader → its writes rejected
  (`fenced_write_rejected_total` increments); dual-belief window shown harmless; clock-jump
  forward/backward scenarios.
- **Exit criteria:** failover < lease duration measured; split-brain injection produces zero
  corrupted state; INCIDENT-001 documented from actual run logs.

## Phase 4 — Distributed workers

**Goal:** horizontal worker scaling with real queue semantics.
- Scope: capabilities/capacity-aware worker selection, resource requirements matching,
  SKIP LOCKED batch claiming, per-type & tenant concurrency caps, backpressure admission,
  visibility-timeout redelivery, drain/dead worker reassignment at scale.
- Benchmarks: claim throughput vs worker count (expect near-linear until DB ceiling);
  document the ceiling number honestly.
- **Exit criteria:** 100 workers simulated without claim contention collapse; backpressure
  rejects verified under overload; fairness smoke test (§29 mini-version) passes.

## Phase 5 — Advanced scheduling

**Goal:** production-grade time handling + scheduling intelligence.
- Scope: cron w/ timezone + DST correctness (property-based tests across DST transitions),
  missed-execution policies (ADR-009) incl. catch-up bounds, weighted fair dispatch
  (ADR-010) + fairness benchmark, resource-aware selection finalized, timing-wheel
  experiment module vs heap vs poll (§50/§102) with published numbers, LISTEN/NOTIFY wakeup
  experiment if wait-latency warrants.
- **Exit criteria:** DST fuzz suite green; fairness benchmark shows Tenant B max-wait bound
  holds; timing-wheel decision made FROM DATA and recorded as ADR addendum.

## Phase 6 — Workflow engine

**Goal:** durable DAGs that survive anything the job layer survives.
- Scope: versioned definitions, DAG validation, dependency resolution, parallel fan-out,
  conditional branches, task-level retries distinct from workflow retries, durable timers
  (WAIT survives restart), workflow recovery recomputation, compensation framework
  (forward-recovery semantics, idempotent compensations), workflow deadlines.
- Tests: kill engine mid-fan-out → resume exactly; timer across restart; compensation runs
  on permanent leaf failure; version pinning (v1 execution unaffected by v2 deploy).
- **Exit criteria:** long-running workflow simulation (accelerated clocks) survives 20 random
  process kills with zero lost steps; deep/wide DAG benchmarks recorded.

## Phase 7 — Multi-tenancy hardening

**Goal:** noisy neighbors cannot harm each other.
- Scope: quotas everywhere (admission + dispatch), rate limiting, RLS defense-in-depth
  decision, per-tenant metrics/dashboards, retention/archival pipeline (partitioned history).
- **Exit criteria:** §29 fairness benchmark at scale passes quantitatively; quota breach
  behavior table verified; archival restores queryable.

## Phase 8 — Production platform

**Goal:** operable by someone who didn't build it.
- Scope: RBAC/API-key rotation, k8s manifests/Helm (probes, PDB, HPA on queue depth),
  autoscaler with hysteresis/cooldown, Grafana dashboard pack, CLI, incident runbooks
  (INCIDENT-001..008 exercised), security review pass, load-test suite in CI-nightly.
- **Exit criteria:** rolling deployment under load with zero lost jobs (measured); HPA scales
  on queue-depth signal in demo; runbook drills pass from docs alone.

## Phase 9 — Scale engineering

**Goal:** know the limits; then move them where it matters.
- Scope: §76 benchmark matrix (10K→1M delayed, 1K–10K/sec submission, 100–1K workers, large/
  deep DAGs, high-retry, hot-tenant), hotspot analysis (§82), targeted optimizations each
  with baseline/change/result (§49), partitioning decision from measurements, capacity model
  doc ("what doubles what").
- **Exit criteria:** BENCHMARKS.md contains reproducible numbers + methodology; every
  optimization traceable to a measurement; OPEN-QUESTIONS resolved or scheduled.

---

## Dependency notes

Phases are sequential by design: 3 needs 2's leases; 4 needs 3's fencing for safe
reassignment; 5's fairness needs 4's queues; 6 rides on 1–4 primitives; 7 polishes 4–5;
9 measures everything honestly. Skipping ahead would invalidate earlier exit criteria.
