# Schedula — Distributed Job Scheduling & Workflow Execution Platform

A production-grade distributed job scheduler and workflow engine, built deliberately and
incrementally. The project optimizes for **correctness, explicit invariants, failure-awareness,
and measurable behavior** — not feature count or repository size.

**Current status: Phases 0–6 complete · Phase 8 core (auth, k8s, dashboards) complete · remaining: RLS/archival export, benchmark numbers on real hardware**

---

## What this system is

A platform that accepts, schedules, dispatches, executes, retries, and recovers:

- one-time, immediate, delayed, recurring (fixed-interval + cron with timezone/DST) jobs
- prioritized, retryable, capability/resource-constrained jobs with quotas and backpressure
- DAG workflows with versioned definitions, durable wait timers, and compensation

...across multiple scheduler nodes (leader-elected, fenced) and a fleet of workers,
surviving crashes, lost acknowledgements, network partitions, clock skew, and process
pauses — with an admin UI at `/` for all of it.

## Core guarantee (stated precisely)

> **At-least-once execution + idempotent effects.**

We do **not** claim exactly-once execution. External side effects cannot be rolled into a
database transaction; duplicates are possible during crash/ACK-loss windows and must be made
safe via idempotency keys. See [EXECUTION-GUARANTEES.md](docs/EXECUTION-GUARANTEES.md).

## Engineering rules this repo follows

1. Every architectural decision is recorded as an ADR: the problem, the options considered,
   why the chosen option wins, and why each alternative was rejected.
2. No technology is introduced without a documented reason.
3. No unproven guarantees ("exactly-once", "zero downtime", "linear scaling").
4. Features are introduced only when their problem becomes real (no day-one sharding,
   timing wheels, work stealing, or Kafka).
5. Every subsystem answers: what fails, what races, what duplicates, what goes stale.

---

## Documentation index

| Document | Contents |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Executive overview, component diagram, boundaries, deployment architecture |
| [DATA-MODEL.md](docs/DATA-MODEL.md) | Tables, keys, indexes, lifecycle, retention |
| [STATE-MACHINES.md](docs/STATE-MACHINES.md) | Job / workflow / worker / scheduler state machines with transition tables |
| [COORDINATION.md](docs/COORDINATION.md) | Leader election, leases, fencing tokens |
| [EXECUTION-GUARANTEES.md](docs/EXECUTION-GUARANTEES.md) | Execution semantics, retry model, queue model, timeouts, cancellation |
| [FAILURE-MODES.md](docs/FAILURE-MODES.md) | Failure matrix + deep dives on the hard cases |
| [CONSISTENCY.md](docs/CONSISTENCY.md) | Per-data consistency requirements, isolation levels, concurrency patterns |
| [MULTI-TENANCY.md](docs/MULTI-TENANCY.md) | Tenancy model, quotas, fair scheduling |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | Metrics, tracing, structured logging, scheduler lag |
| [SECURITY.md](docs/SECURITY.md) | AuthN/AuthZ, tenant isolation, execution-model security, audit |
| [API.md](docs/API.md) | REST API proposal |
| [TECHNOLOGY-CHOICES.md](docs/TECHNOLOGY-CHOICES.md) | Stack decisions, alternatives rejected, major trade-offs |
| [ROADMAP.md](docs/ROADMAP.md) | Phase 0–9 implementation roadmap with exit criteria |
| [TESTING.md](docs/TESTING.md) | Test strategy, fault injection, chaos scenarios, load testing |
| [OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md) | Unresolved architectural questions |
| [BENCHMARKS.md](docs/BENCHMARKS.md) | Benchmark methodology, dev-laptop observations, known ceilings |
| [rls-template.sql](docs/rls-template.sql) | Optional Postgres RLS defense-in-depth template (off by default) |
| [OPERATIONS.md](docs/OPERATIONS.md) | Operator quick reference: run, CLI, signals, failure handling |

### Architecture Decision Records (`docs/adr/`)

| ADR | Decision |
| --- | --- |
| [ADR-001](docs/adr/ADR-001-postgresql-as-source-of-truth.md) | PostgreSQL as sole durable store |
| [ADR-002](docs/adr/ADR-002-at-least-once-execution.md) | At-least-once execution + idempotency keys |
| [ADR-003](docs/adr/ADR-003-lease-based-ownership.md) | Lease-based ownership for jobs and leadership |
| [ADR-004](docs/adr/ADR-004-fencing-tokens.md) | Fencing tokens against stale owners |
| [ADR-005](docs/adr/ADR-005-postgres-leader-election.md) | Leader election via PostgreSQL lease (labeled simplified) |
| [ADR-006](docs/adr/ADR-006-database-backed-queue.md) | Database-backed queue instead of an external broker (initially) |
| [ADR-007](docs/adr/ADR-007-java-spring-modular-monolith.md) | Java 21 + Spring modular monolith |
| [ADR-008](docs/adr/ADR-008-utc-and-injected-clock.md) | UTC storage + injected Clock abstraction |
| [ADR-009](docs/adr/ADR-009-missed-execution-policy.md) | Missed-execution policy = coalesce to one run (configurable) |
| [ADR-010](docs/adr/ADR-010-weighted-fair-dispatch.md) | Weighted fair dispatch for multi-tenant fairness |

---

## Quickstart (with admin UI)

Prereqs: Docker + Java 21 (or just Docker for full compose).

```bash
docker compose up -d postgres
mvn -pl app spring-boot:run        # API + scheduler + workers in one process
# startup log prints the default tenant API key (compose preset: sk_..._devkey123)
```

Open the **admin UI at http://localhost:8080**, paste the key, and drive everything:
submit jobs (delays/priorities/caps), create cron schedules, watch retries hit the DLQ,
replay dead letters, inspect worker fleet, leader/fencing tokens, and run DAG workflows.

CLI alternative:

```bash
export SCHEDULA_KEY=<key from log>
./schedula.sh submit log '{"msg":"hello"}'
./schedula.sh status <jobId>
./schedula.sh schedulers           # leader + fencing token
./schedula.sh dlq                  # dead letters
```

Metrics: `curl localhost:8080/actuator/prometheus | grep schedula_` ·
Dashboard JSON in `k8s/grafana-dashboard.json` · Load harness `load/submit-mixed.js`.

---

## Phase plan (summary)

```
Phase 0  Architecture .................. DONE
Phase 1  Single-node scheduler ......... DONE
Phase 2  Reliable single node .......... DONE (leases, cancellation, DLQ, audits)
Phase 3  Distributed schedulers ........ DONE (leader election + fencing)
Phase 4  Distributed workers ........... DONE (caps, quotas, backpressure, fairness)
Phase 5  Advanced scheduling ........... DONE (cron+DST, weighted fair dispatch)
Phase 6  Workflow engine ............... DONE (DAGs, timers, compensation)
Phase 7  Multi-tenancy ................. CORE DONE (quotas, isolation; RLS/archival export pending)
Phase 8  Production platform ........... CORE DONE (auth, k8s, UI, CLI, dashboards; Grafana JSON in k8s/)
Phase 9  Scale engineering ............. harness ready; dedicated-hardware numbers pending
```

Full detail with entry/exit criteria: [ROADMAP.md](docs/ROADMAP.md).

Each phase must solve a real problem introduced by the previous phase. The progression is:
single node → reliable single node → distributed scheduler → distributed workers →
advanced scheduling → workflow engine → multi-tenant platform → production deployment →
large-scale system.
