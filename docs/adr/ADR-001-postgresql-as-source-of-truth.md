# ADR-001: PostgreSQL as the sole durable store

Status: Accepted (Phase 0)

## Context

The platform needs durable job state, execution history, schedules, queue semantics,
coordination leases, workflow state, and audit trails (§8). Something must be the source of
truth, and every additional system of record multiplies failure modes and consistency
burden.

## Problem

Which system holds durable state — one database wearing multiple hats, or specialized
systems per concern (broker for queues, consensus store for coordination, RDBMS for state)?

## Options considered

1. **PostgreSQL for everything (chosen).**
2. **PostgreSQL + Kafka** (events/queue in Kafka).
3. **PostgreSQL + RabbitMQ/SQS** (queue external).
4. **NoSQL primary (MongoDB/DynamoDB/Cassandra)**.
5. **MySQL** as the relational store.

## Decision

PostgreSQL is the single source of truth for all durable state through at least Phase 5.
Queue semantics are implemented on Postgres tables (`FOR UPDATE SKIP LOCKED` claims,
visibility timeouts via claim expiry); coordination leases live in Postgres rows; workflow
state is ordinary transactional data.

## Why this is best

- **Atomicity across concerns:** enqueue + state transition commit in ONE transaction. With
  an external broker, "job marked QUEUED" and "message published" are two systems' truths →
  dual-write problem → outbox pattern → relay lag/failure modes. Same-database removes an
  entire class of bugs before writing a line of code.
- **One consistency model:** guarded updates and row locks give us exactly-once *state
  recording* everywhere; no eventual-consistency edges in the state machine.
- **Adequate performance with headroom:** SKIP LOCKED claiming scales to thousands of
  jobs/sec/node; partial indexes keep million-row scheduled-job scans cheap. Targets are
  hypotheses until benchmarked — but the ceiling is far above Phase 1–4 needs.
- **Operational cost:** one system to run, back up, monitor, and reason about during
  incidents. For a project whose purpose is deep understanding of scheduling (not broker
  ops), this is decisive.

## Why alternatives were rejected

- **+Kafka:** log semantics ≠ mutable-state semantics; job lifecycle needs cross-entity
  transactions (job↔execution↔schedule), which Kafka cannot provide; adds ZK/KRaft cluster,
  partition/rebalance complexity, and a second failure domain before any measured need.
  Revisit trigger exists (ADR-006) if throughput outgrows PG.
- **+RabbitMQ/SQS:** good visibility-timeout/ack semantics, but splits truth across systems;
  SQS adds per-message costs and 15-min visibility cap constraints; both reintroduce the
  outbox problem.
- **NoSQL primary:** our invariants are relational (unique idempotency keys, FK integrity,
  multi-row transactions for schedule ticks); document stores make these app-enforced and
  racy — precisely what §10 forbids.
- **MySQL:** viable, but `SKIP LOCKED` maturity/docs and isolation documentation favor PG;
  no feature need pushes the other way.

## Trade-offs accepted

- Throughput ceiling vs a dedicated broker (documented; escape path defined).
- Polling load on the DB (mitigated by partial indexes, batch sizes, backoff; LISTEN/NOTIFY
  experiment planned).
- DB availability becomes a single dependency: control plane fails closed when PG is down
  (documented degraded mode, FAILURE-MODES.md).

## Consequences

Schema/migrations become first-class (Flyway, expand→migrate→contract). Connection pooling
sizing matters platform-wide. Benchmarks must measure DB CPU/locks from Phase 1 so the
ceiling is known before it hurts.

## Revisit triggers

Sustained >70% DB utilization attributable to queue operations at target load, or write
latency P99 breaching dispatch budgets → first partitioning, then external broker evaluation
(new ADR required, with measurements attached).
