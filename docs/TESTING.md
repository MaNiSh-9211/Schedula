# TESTING STRATEGY

Status: Phase 0 proposal. Covers requirements §57, §58, §46, §47, §48, §102.

Philosophy: this project's core claims are *failure-behavior* claims. A test suite that
never kills anything mid-flight proves nothing worth the repo name. Therefore fault
injection is a first-class, automated mechanism — not a manual ritual.

---

## 1. Test pyramid

| Level | Scope | Tooling | Examples |
| --- | --- | --- | --- |
| Unit | pure logic: state transition tables, backoff math, cron/DST evaluation, DAG topo/conditionals, fairness DRR counters, resource matching | JUnit5 + jqwik (property-based) for time/fairness | "exponential+jitter delay ∈ [0, cap]"; "no illegal transition accepted" fuzzed |
| Component | one module + real Postgres via Testcontainers | Testcontainers, injected Clock | claim query under 50 concurrent threads → zero double-claims |
| Integration | multi-module in-process, real PG | Spring test slices | submit→schedule→dispatch→execute→complete E2E |
| Failure/chaos | scripted faults against running system | fault hooks + compose scripts + toxiproxy | §47 scenario catalog below |
| Load/benchmark | throughput/latency ceilings | k6 (HTTP) + JVM harness (scheduler loops) | §76 matrix |

Rule from §51: **concurrent DB logic is never tested against mocks** — races live in SQL,
so tests run real PostgreSQL with real thread pools.

## 2. Invariant test list (from §58, expanded — each maps to ≥1 automated test)

1. Completed job cannot execute again except explicit retry (new job).
2. Cancelled-while-queued job never dispatches (race-tested: cancel vs claim concurrently ×1000).
3. Stale worker cannot commit outcome after losing lease (fencing rejection asserted).
4. Only current lease owner renews; renewal by ex-owner returns denial.
5. Expired lease ⇒ execution recoverable within lease+sweep bound.
6. Tenant quotas cannot be exceeded under concurrent submission burst.
7. Workflow task never READY before deps SUCCEEDED.
8. Workflow survives engine restart at every task boundary (randomized kill points).
9. Job survives scheduler restart; schedule occurrences neither skipped nor doubled.
10. DEAD jobs stay dead; DLQ retry creates linked new work only.
11. Duplicate delivery possible ⇒ handler sees same idempotency key both times.
12. Explainability: any terminal job reconstructs full history from events alone.
13. Illegal transitions impossible via API fuzzing (random state mutations rejected).
14. Clock jumps (±hours) do not lose or duplicate scheduled work.

## 3. Fault-injection mechanisms (§46)

Built deliberately into Phase 1–2, not retrofitted:

- **Injected `Clock`:** all scheduling decisions flow through it; tests jump time forward
  (lease expiry, backoff, timers) without sleeping; wall-clock never used for durations (ADR-008).
- **`FaultInjector` hooks:** pointcuts at DB client, heartbeat sender, ACK path, lease renewal —
  configurable per-test to throw/delay/drop. Production config keeps them inert; feature-flagged (§93).
- **Process-level chaos:** compose orchestration kills/pauses (`SIGKILL`, `SIGSTOP`) containers
  at scripted or random moments; PG restart; network latency/partition via Toxiproxy between
  workers↔PG and schedulers↔PG.
- **Duplicate/lost message simulation:** queue layer test mode duplicating deliveries and
  dropping ACKs probabilistically.

## 4. Chaos scenario catalog (repeatable, automated — §47)

| ID | Scenario | Expected outcome (asserted) |
| --- | --- | --- |
| CHAOS-001 | Worker does external effect then crashes pre-ACK | redelivery ≤ lease+sweep; successor executes; idempotency key identical; effect-dedup verified by counting handler-side effects |
| CHAOS-002 | SIGSTOP leader past lease expiry | follower takes over ≤ lease duration; old leader's next write fenced-rejected; no duplicate occurrences |
| CHAOS-003 | PG restart 30s during load | submissions fail closed (client-visible), zero acknowledged-loss; system drains backlog post-recovery; MTTR recorded |
| CHAOS-004 | Lost ACK (drop finalize call) | execution redelivered once; attempt history shows both; final state consistent |
| CHAOS-005 | Duplicate delivery injection p=10% | no state corruption; dedup metrics align |
| CHAOS-006 | Clock jumps +2h / −2h | schedules fire correctly per missed-policy; leases don't mass-expire on backward jump (DB-time comparison) |
| CHAOS-007 | Worker fleet loses 30% instantly | jobs recover within bound; no thundering-herd reassignment storm (rate-limited sweep) |
| CHAOS-008 | Hot tenant submits 1M jobs | other tenants' P99 dispatch wait bounded (fairness); admission rejects overflow |

Each scenario lives as code+compose profile; CI runs the fast subset nightly, full set pre-release.
INCIDENT-00x docs (§96) are written FROM these runs with real logs/metrics attached.

## 5. Load-testing strategy (§48)

- **k6 suites:** submission API at 100/1K/10K jobs/sec ramps; mixed workloads (immediate/
  delayed/recurring-heavy; many-tenant; one-huge-tenant; high-retry).
- **JVM benchmark harness:** scheduler poll loop, dispatcher claim loop, fairness algorithm —
  isolated microbenchmarks (JMH where hot) because HTTP is not the bottleneck there.
- **Measured always:** throughput, P50/P95/P99 latencies, scheduler lag percentiles, DB CPU,
  lock waits, pool saturation, queue depth trajectories.
- **Pass gates per phase** defined in ROADMAP exit criteria; results land in BENCHMARKS.md
  with methodology + environment spec so numbers are reproducible (§49: baseline → change →
  result → explanation, or it didn't happen).
