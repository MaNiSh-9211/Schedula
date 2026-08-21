# ADR-006: Database-backed queue instead of an external message broker (initially)

Status: Accepted (Phase 0) — revisit trigger defined

## Context

Dispatched work must reach workers durably, with priority, delay, redelivery, and
dead-lettering (§31). §7 forbids introducing Kafka/RabbitMQ/Redis Streams/NATS "simply
because it exists" and demands documented semantics for whatever queue we run.

## Problem

Build queue semantics on the database we already trust, or adopt a dedicated broker?

## Options considered

1. **Postgres tables as the queue (chosen).**
2. **Kafka.**
3. **RabbitMQ.**
4. **SQS / cloud queues.**
5. **Redis Streams / Lists.**

## Decision

`queue_messages` table with: transactional enqueue (same tx as state change), atomic batch
claims via `FOR UPDATE SKIP LOCKED`, visibility timeout via `claim_expires_at` reclamation,
priority + per-queue FIFO sequence, delivery counting → DEADLETTERED, DLQ as queryable
state. Full semantics contract documented in EXECUTION-GUARANTEES.md §3.

## Why this is best

- **Atomic producer:** "job became eligible" and "message exists" commit together — no
  outbox relay, no publish-after-commit loss window, no dual-write.
- **Consumer semantics we control exactly:** claim = one statement; redelivery = expiry
  sweep; ordering = explicit columns; all inspectable in SQL during incidents.
- **Backpressure is natural:** queue depth is a SELECT away for admission control; no
  lag-translation layer between broker metrics and our quotas.
- **Operational honesty:** this project's value is demonstrating queue/delivery reasoning;
  owning the semantics (rather than inheriting a broker's) is pedagogically and
  architecturally on-mission, at a scale where it remains correct.

## Why alternatives were rejected

- **Kafka:** partition-log model fits event streaming; work-queue use needs manual
  assignment/commit gymnastics to get visibility-timeout-like behavior; heavy ops; wrong
  default tool despite popularity (§7).
- **RabbitMQ:** solid classic queues, but splits truth from job state (outbox needed),
  adds a cluster, and its priority/redelivery semantics would still need DB-side state to
  be authoritative — paying for a broker without removing the database work.
- **SQS:** cloud-coupled (dev/test story suffers), visibility cap 15min vs long jobs,
  per-request cost at poll-heavy patterns, same dual-write issue.
- **Redis Streams:** persistence/AOF trade-offs under crash, another failure domain, weaker
  durability guarantees than PG for exactly the ack/expiry logic we depend on.

## Trade-offs accepted

- Throughput ceiling (~thousands/sec/node class, to be benchmarked not asserted).
- Poll load on PG (partial indexes + backoff + future LISTEN/NOTIFY experiment mitigate).
- Very long jobs hold claims via expiry windows rather than broker sessions — fine for our
  lease-based model, unusual for broker veterans; documented.

## Consequences

Queue-layer interface (`Queue`) is built clean from day one so a broker can slot in behind
it if a measured ceiling demands it. Partitioning (§32) precedes any broker move in the
escape path. All §31-required semantics are documented for OUR implementation — satisfying
§7's documentation demand regardless of backend.

## Revisit triggers

Benchmark shows queue operations dominating DB capacity at target load after indexing/batch
tuning → ADR for partitioning; if still insufficient → broker evaluation with numbers.
