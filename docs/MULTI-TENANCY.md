# MULTI-TENANCY

Status: Phase 0 proposal. Covers requirements §25–§29, §82. Implementation lands Phase 7,
but tenancy columns and admission hooks exist from Phase 1 (retrofitting tenancy is
notoriously painful).

---

## 1. Tenancy model

- Every business row carries `tenant_id` (jobs, schedules, workflows, executions, events,
  idempotency records, audit). Workers and schedulers are platform-scoped, not tenant-scoped.
- Isolation enforcement points:
  1. **API layer:** tenant resolved from credentials; every query forced through
     tenant-scoped repository methods (no raw tenant-less access paths compile-clean).
  2. **Data layer:** composite keys/indexes lead with `tenant_id`; optional Postgres RLS as
     defense-in-depth behind a `SET app.tenant_id` setting (Phase 7 decision, OPEN-QUESTIONS #6).
  3. **Dispatch layer:** fairness algorithm is tenant-aware (below), so isolation extends to
     *throughput*, not just visibility.
- Cross-tenant reads do not exist in the application vocabulary; admin tooling uses a
  separate audited role.

## 2. Quotas (§27, §28)

| Quota | Scope | Enforcement point | On breach |
| --- | --- | --- | --- |
| Submission rate | tenant | API admission (token bucket, durable-ish via Redis later; in-memory+DB check initially) | 429 + Retry-After, `tenant_throttled_total` |
| Queue depth (pending jobs) | tenant | API admission (count query on partial index) | 429 reject — deliberate: backpressure over silent queuing (§27) |
| Concurrent executions | tenant | Dispatcher claim step | Job waits QUEUED (not rejected — it was already admitted) |
| Concurrent executions per job type | job type (+tenant) | Dispatcher claim step | Same |
| Scheduled (future) jobs | tenant | API admission | 429 |
| Retention window | tenant | Retention sweeper | Archival, never silent deletion |

Distinction made explicit: **admission rejection** (never accepted the work) vs
**dispatch deferral** (accepted, waiting for capacity/quota). Different APIs surface both
differently; conflating them produces angry users and hidden SLA violations.

## 3. Fair scheduling (§29)

Problem: strict priority or FIFO dispatch lets a tenant with 1M queued jobs starve tenants
with 10. Naïve fix (round-robin across all tenants) ignores agreed weights and mixes badly
with priority.

Selected algorithm: **Weighted Fair Queueing over tenants, Deficit Round Robin implementation,
priority preserved *within* tenant** (ADR-010).

- Each tenant has weight wᵢ (default 1). Dispatcher iterates ready messages using per-tenant
  deficit counters so long-run service share ∝ wᵢ (DRR gives O(1) per message and exact
  weighted shares asymptotically).
- Priority interacts as: tenant selection first (weighted), then highest-priority-first
  within that tenant's batch. Pure global priority would reintroduce starvation; this is a
  documented trade-off (a P0 for tenant B waits behind tenant A's weighted slice — bounded
  by quantum size, configurable).
- Starvation proof obligation: with any non-empty tenant queue, inter-dispatch gap for that
  tenant is bounded by Σ(quantumⱼ)/wᵢ × job_time — asserted by the fairness benchmark
  (Tenant A=1M, B=10, C=100 scenario from §29; measure B's max wait).

Hotspot analysis hooks (§82): per-tenant dispatch metrics expose hot tenants; per-queue depth
exposes hot queues; `pg_stat_statements` + lock metrics expose hot rows. Partitioning
(§32) remains unimplemented until one of these shows a real ceiling — trigger conditions
written in ROADMAP Phase 9.

## 4. Tenant lifecycle

Create (admin, audited) → active → draining (no new submissions; running finishes) →
suspended (API rejects) → deleted (only when no live resources; history archived per
retention). Quota changes are audited events (§54) and take effect on next admission/dispatch
decision — no restart needed.
