# TECHNOLOGY CHOICES, ALTERNATIVES, TRADE-OFFS

Status: Phase 0. Covers requirements §7, §23 (alternatives), §24 (trade-offs). Each row
follows the master rule: what we picked, why it wins, and why each alternative lost.

---

## 1. Stack decisions

### Language & runtime: Java 21

- **Why:** requirement-preferred; virtual threads give cheap per-job concurrency without
  reactive complexity; strong typing + mature ecosystem for transactional backend work.
- **Alternatives rejected:**
  - *Go* — excellent fit for this domain (goroutines, single-binary deploys), loses on the
    stated stack preference and on ORM/migration maturity for a schema-heavy system.
  - *Node/TypeScript* — weak story for CPU-bound scheduling loops and heavy DB concurrency.
  - *Rust* — best raw performance, worst iteration velocity for an evolving design; not
    justified before any benchmark exists.

### Framework: Spring Boot 3.x

- **Why:** requirement-preferred; battle-tested tx management, connection pooling, metrics/
  tracing integrations (Micrometer/OTel) reduce glue code that would otherwise carry bugs.
- **Rejected:** Quarkus/Micronaut (fine, no decisive advantage here); plain JDK+custom wiring
  (we'd rebuild Spring's tx/pooling badly — exactly the "hidden complexity" §65 forbids).

### Database: PostgreSQL 16 — sole durable store (ADR-001)

- **Why:** transactions + `FOR UPDATE SKIP LOCKED` + partial indexes cover state machine,
  queue, and coordination needs in ONE system with one failure domain and one consistency
  model. Correctness-first choice.
- **Rejected:**
  - *MySQL* — comparable, but SKIP LOCKED arrived later and PG's isolation/locking semantics
    are better documented for this pattern.
  - *MongoDB/DynamoDB* — document stores weaken the relational integrity our state machines
    depend on; multi-item tx constraints awkward.
  - *Kafka-as-state* — log ≠ mutable state; job lifecycle needs transactions across entities.

### Queue: database-backed (ADR-006)

- **Why:** enqueue is transactional with state changes (no dual-write/outbox needed);
  delivery semantics fully under our control; one less distributed system to operate.
- **Rejected (for now):** Kafka (wrong abstraction for work queues + ops weight),
  RabbitMQ/SQS (visibility/ack semantics good but state split across systems → dual-write
  problems), Redis Streams (persistence/AOF trade-offs, another failure domain).
- **Escape path documented:** measured ceiling triggers partitioning first, broker second.

### Coordination: PostgreSQL lease + fencing (ADR-005)

- **Rejected:** etcd/ZooKeeper (real consensus but new always-on cluster + client complexity
  before need is proven), Kubernetes Lease API (couples control plane to k8s, breaks compose
  dev parity), Redlock (documented safety controversy under partitions — exactly the failure
  mode we care most about).

### Observability: Micrometer/Prometheus + OpenTelemetry + structured JSON logs

- **Why:** requirement-named, industry-default, vendor-neutral.
- **Rejected:** proprietary APM-first approaches (lock-in, cost) — OTLP exports anywhere.

### Testing: JUnit 5, Mockito, Testcontainers (+ k6 later)

- **Why:** Testcontainers gives real Postgres in integration tests — mocking SQL hides the
  exact races we exist to test (§51). k6 for HTTP load; JVM harness for scheduler-loop
  benchmarks where HTTP is not the bottleneck under test.

### Build: Gradle (multi-module), Flyway migrations, Docker Compose dev env

- **Why:** module boundaries enforce architecture (§64); Flyway = reviewable SQL history;
  compose gives the §71 environment (3 schedulers, 5 workers, 1 API, PG).

## 2. Major trade-offs accepted (and their exit conditions)

| Trade-off | What we gain | What we accept | Exit/revisit trigger |
| --- | --- | --- | --- |
| PG as queue | atomicity, simplicity | throughput ceiling (~k/sec/node), poll load | sustained >70% DB CPU from queue ops at target load → partition/broker (ADR-006) |
| Leader-based scheduling | simple reasoning, no per-row election everywhere | failover gap ≤ lease duration; leader is a hotspot by design | dispatch loop can't keep up → shard leadership by queue/tenant range (design sketch exists) |
| At-least-once | no impossible promises | duplicate-effect handling burden on handlers | none — permanent, by physics |
| Modular monolith | fast iteration, single deploy early | shared failure domain | divergent scaling/ownership needs per component (§64 criteria) |
| Polling consumers | simple, robust | latency up to poll interval, empty-poll cost | LISTEN/NOTIFY wakeups when P99 wait-latency matters measurably |
| Full-jitter backoff | storm safety | slightly slower recovery than optimal | benchmark showing head-of-line blocking |

## 3. Things deliberately NOT chosen yet (with trigger conditions)

Redis (hot coordination/rate-limit counters when DB round-trips measurably hurt admission),
timing wheel (delayed-job volume where heap/poll benchmarks show O(n) pain — §50 experiment),
work stealing (idle-worker + contended-queue measurements), gRPC internal APIs (when REST
serialization shows up in profiles), RLS enforcement (defense-in-depth decision Phase 7).
