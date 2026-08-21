# OPEN ARCHITECTURAL QUESTIONS

Status: Phase 0. Covers requirement §107.30. Each question has context and a current lean;
none are silently decided. Resolved questions move to ADRs with their evidence.

| # | Question | Context | Current lean |
| --- | --- | --- | --- |
| 1 | When (if ever) does dispatch leadership need sharding? | Single leader is a throughput ceiling by design | Measure in Phase 4; shard-by-queue-range sketch exists; no action before data |
| 2 | Timing wheel vs heap vs DB poll crossover point? | §50/§102 experiment planned Phase 5 | Expect DB-poll fine ≤ ~100K delayed jobs w/ partial index; wheel only if benchmark says so |
| 3 | Does Redis ever earn its place? | Rate-limit counters, hot admission checks, NOTIFY alternative | Not before Phase 7; only if DB round-trips measurably hurt admission P99 |
| 4 | Default missed-execution policy for business-critical cron? | ADR-009 picks COALESCE default | Per-schedule override UI/API may be enough; survey real workloads at Phase 5+ |
| 5 | Fairness quantum size & weights UX | DRR quantum trades latency vs switch cost | Benchmark-driven; expose per-tenant weight API Phase 7 |
| 6 | Postgres RLS as defense-in-depth? | App-layer scoping already enforced | Enable behind feature flag Phase 7 if perf cost negligible |
| 7 | Multi-region posture | Out of scope early; leases/fencing assume one PG | Document single-region limitation explicitly; revisit only with a real requirement |
| 8 | Workflow conditional-branch expression language | Needs safe, deterministic evaluation | Start with declarative predicates (no scripting); DSL later if demanded |
| 9 | Payload encryption at rest per tenant? | Compliance-dependent | Design hook now (payload opaque JSONB), implement on demand |
| 10 | Worker pull vs push delivery | Pull (claim) chosen for simplicity/backpressure | Push (dispatcher→worker) only if claim-poll latency proves harmful at scale |
| 11 | Retention defaults & archival storage target | §52 needs numbers | Defaults after Phase 7 load data; archive to S3-compatible store likely |
| 12 | gRPC for internal worker/scheduler APIs? | REST everywhere keeps tooling simple | Revisit when profiles show serialization cost |
| 13 | Exactly how bounded should "bounded duplication" be advertised? | Lease+sweep gives worst-case window; want honest SLA phrasing | Phrase as "≤ lease + sweep interval, configurable"; finalize with Phase 2 measurements |

Questions are triaged each phase boundary; anything blocking implementation gets an ADR first.
