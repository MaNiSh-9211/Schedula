# ARCHITECTURE

Status: Phase 0 proposal. Covers requirements §1–§6, §64, §83.

---

## 1. Executive overview

The platform is a **distributed job scheduling and workflow execution system** with three
planes kept conceptually separate (§83):

- **Control plane** — accepts work (jobs, workflows), owns definitions and desired state.
  Answering "what should exist and when should it run?"
- **Coordination plane** — decides *who* is allowed to act (leader election, leases,
  fencing). Answering "who owns this decision right now?"
- **Data plane** — actually executes work (queues, workers). Answering "run this now,
  report back, survive failure."

### Design goals (in priority order)

1. **No lost work.** A submitted, acknowledged job is never silently dropped. It either
   executes or lands in a visible failed/dead state with an audit trail.
2. **Bounded duplicate execution.** At-least-once delivery means duplicates are possible;
   the window is bounded by lease duration and every effect is made idempotency-safe.
3. **No stale-owner corruption.** A partitioned or paused scheduler/worker can never
   overwrite the decisions of its successor (fencing tokens).
4. **Explainability.** For any job, the system can answer "why is it in this state?" from
   persisted events — without attaching a debugger.
5. **Horizontal scaling of the data plane.** Workers scale out linearly until the database
   becomes the bottleneck; the bottleneck is measured, documented, and has an escape path.

### Non-goals (explicitly)

- Exactly-once execution of arbitrary side effects (impossible in general; see ADR-002).
- Sub-millisecond scheduling latency. This is a durable scheduler, not a low-latency
  trading system. Target: P99 scheduler lag in the low hundreds of milliseconds at moderate load.
- Multi-region active-active in early phases (open question #7).
- A general-purpose compute platform (Kubernetes-native job execution is a later, optional mode).

### Scale targets (initial, to be validated by benchmarks — §48, §76)

| Dimension | Initial target |
| --- | --- |
| Job submission throughput | 1,000/sec sustained per API node |
| Dispatch throughput | 1,000–5,000/sec per scheduler node (Postgres-bound; benchmark will pin this) |
| Scheduled (future) jobs | 1M rows with stable poll cost via indexes |
| Concurrent workers | 100+ |
| Tenants | 100+ with fairness enforced |
| Scheduler failover time | < lease duration (default 15s), bounded, tested |

These are hypotheses until Phase 9 benchmarks exist. Nothing in the docs claims more than
that.

---

## 2. Component diagram

```
        Clients (CLI / dashboards / services)
                        |
                        v
              +------------------+
              |   API Gateway    |   authn, rate limit (Phase 8)
              +------------------+
                        |
                        v
              +------------------+
              |   API / Control  |   REST: submit, query, cancel, retry
              |      Plane       |
              +--------+---------+
                       |
         +-------------+--------------+
         v                            v
  +-------------+             +---------------+
  | Job Service |             | Workflow Svc  |   definitions, lifecycle,
  |             |             |               |   idempotency, quotas
  +------+------+             +-------+-------+
         |                            |
         +------------+---------------+
                      v
        =========================================
          PostgreSQL  (source of truth: jobs,
          executions, schedules, queues, leases,
          workflow state, audit)
        =========================================
                      ^
        +-------------+-------------+
        |     Scheduler Cluster     |   leader-elected; only leader
        |   S1        S2        S3  |   evaluates schedules w/ fencing token
        +-------------+-------------+
                      |
                      v
              +----------------+
              |   Dispatcher   |   eligible -> queue routing,
              | (leader-delegated) | capacity checks, fairness
              +----------------+
                      |
                      v
        +---------------------------+
        |        Queue Layer        |   durable queues in PG:
        |  Q0 | Q1 | ... | QN      |   priority, delay, retry, DLQ
        +---------------------------+
                      |
                      v
        +---------------------------+
        |     Worker Fleet          |   register, heartbeat, lease,
        |  W-A   W-B   ...   W-N    |   execute, renew, ack/nack
        +---------------------------+

  Observability: OpenTelemetry traces, Prometheus metrics, structured logs
  (sidecar/collector processes, out of band).
```

Notes:

- The dispatcher begins as a **role inside the scheduler process**, not a separate service
  (§64). It becomes deployable independently when its scaling need diverges (Phase 4+).
- Workers talk **only to the database and the API**, never directly to schedulers. This keeps
  the data plane decoupled from control-plane leadership changes.
- Redis appears nowhere in Phase 0–3. It is introduced only if a measured need appears
  (see ADR-005 discussion and OPEN-QUESTIONS #3).

---

## 3. Logical component boundaries

Each component lists: responsibility, owned tables, exposed interface, and explicitly what
it must NOT do. Boundaries are enforced as build modules so extraction into services later
is mechanical, not archaeological.

### 3.1 API Layer
- **Owns:** request validation, authentication, tenant resolution, quota admission,
  idempotency-key handling, HTTP semantics.
- **Tables touched:** `idempotency_records`, `audit_events`; writes jobs/workflows via services.
- **Interface:** REST (see API.md).
- **Must NOT:** contain scheduling logic, execute jobs, or bypass quota checks.

### 3.2 Job Service
- **Owns:** job definitions and lifecycle metadata; the authoritative job state machine.
- **Tables:** `jobs`, `job_executions`, `job_events`.
- **Interface:** `JobStore` (create/get/list/transitions guarded by optimistic version).
- **Must NOT:** decide *when* things run (scheduler's job) or *where* (dispatcher's job).

### 3.3 Scheduler
- **Owns:** deciding when jobs become eligible: delayed jobs due, recurring/cron occurrences,
  missed-execution policy application.
- **Tables:** reads `jobs`, `job_schedules`; writes eligibility transitions + `job_events`.
- **Interface:** `SchedulingEngine` (pure logic over a `Clock`) + `ScheduleStore`.
- **Must NOT:** touch queues directly (dispatcher's job); run concurrently in more than one
  instance without fencing (coordination's job).

### 3.4 Coordinator
- **Owns:** cluster membership (`scheduler_nodes`), leader lease, fencing token generation.
- **Tables:** `scheduler_nodes`, `scheduler_leases`, `fence_counters`.
- **Interface:** `LeaderElector`, `FencingTokenSource`.
- **Must NOT:** make scheduling decisions itself; it only arbitrates *permission*.

### 3.5 Dispatcher
- **Owns:** moving eligible jobs into execution queues; worker selection; capacity checks;
  queue routing; fairness between tenants/queues.
- **Tables:** `queue_messages`, reads `workers`, `jobs`.
- **Interface:** `Dispatcher`, `QueueProducer`, `RoutingStrategy`, `FairnessStrategy`.
- **Must NOT:** mutate job business state except QUEUED/DISPATCHED transitions.

### 3.6 Queue Layer
- **Owns:** durable message storage, claim/visibility semantics, redelivery, DLQ routing.
- **Tables:** `queue_messages` (+ DLQ represented as terminal states / dead-letter rows).
- **Interface:** `Queue` (enqueue, claim, ack, nack, extend, requeue-expired).
- **Must NOT:** know about cron, tenants' business meaning, or worker identity beyond claims.

### 3.7 Worker Manager
- **Owns:** worker registry, heartbeats, health transitions, capacity accounting, lease grants.
- **Tables:** `workers`, lease columns on executions/messages.
- **Interface:** `WorkerRegistry`, `LeaseManager`.
- **Must NOT:** trust heartbeats as proof of health (they are a liveness *signal*, §17).

### 3.8 Worker Runtime
- **Owns:** executing job handlers, renewing leases, cooperative cancellation, reporting results.
- **Tables:** updates own executions (guarded by fencing token), heartbeats `workers`.
- **Interface:** `JobHandler` registry (typed handlers; no arbitrary code execution, §89),
  `ExecutionClient`.
- **Must NOT:** write any state without presenting a valid fencing token.

### 3.9 Workflow Engine
- **Owns:** DAG definitions/versions, execution planning, dependency resolution, durable
  timers, task retries, compensation orchestration.
- **Tables:** `workflows`, `workflow_versions`, `workflow_executions`,
  `workflow_task_executions`, `workflow_timers`.
- **Interface:** `WorkflowEngine`, `WorkflowStore`, `TimerService`.
- **Must NOT:** keep runnable state in memory across restarts; everything resumable from DB.

### 3.10 Persistence Layer
- **Owns:** schema/migrations, transactional helpers, guarded-update primitives
  ("compare-state-and-version in one UPDATE"), retention/archival jobs.
- **Must NOT:** leak SQL semantics upward in ways that prevent later store swaps where
  variation is genuine (§92) — but also must not create pointless abstractions over SQL
  we deliberately chose (ADR-001).

---

## 4. Deployment architecture

### 4.1 Phase 1–2: single node, modular monolith

One JVM process containing all modules, roles toggled by configuration:

```
java -jar platform.jar --roles=api,scheduler,dispatcher,worker
```

- Single PostgreSQL instance (Docker Compose).
- Rationale: correctness first. Distributed coordination is meaningless before single-node
  behavior is right. But module boundaries exist from day one so splitting is config, not surgery.

### 4.2 Phase 3+: split processes

```
docker-compose (dev):
  postgres:1        api:1        scheduler:3 (leader-elected)        worker:5
```

- Schedulers run identical binaries; leadership arbitrated by Postgres lease (ADR-005).
- Workers scale horizontally; each registers with capabilities/capacity.
- Graceful shutdown on SIGTERM: stop fetching → DRAINING → finish/renew current leases →
  deregister → exit (§42). Kubernetes will not wait forever: termination grace period must
  exceed worst-case drain time; documented in DEPLOYMENT notes at Phase 8.

### 4.3 Kubernetes (Phase 8)

- Deployments: api, scheduler (3 replicas), worker (HPA on queue depth — not CPU, §40).
- StatefulSet or operator-managed Postgres initially; managed DB acceptable.
- Readiness = registered + heartbeat fresh; Liveness = process responsive (NOT "has leases" —
  losing leases must not trigger restart loops; recovery handles it).
- PodDisruptionBudget on workers sized so drain capacity exists during voluntary disruptions.
- ConfigMaps for tunables (all timeouts configurable, §70); Secrets for credentials.

### 4.4 Failure containment expectations

| Boundary | Isolates |
| --- | --- |
| Process | scheduler crash vs worker crash vs API crash |
| Module | bugs in workflow engine cannot corrupt job state machine |
| Tenant | noisy tenant throttled at admission, fair-shared at dispatch |
| Queue | pathological job type cannot starve other types (per-type caps) |

---

## 5. What we deliberately did NOT architect yet

Per §101 (do not over-engineer early): no timing wheel, no work stealing, no queue
partitioning, no external broker, no Redis, no multi-region, no plugin sandbox. Each has a
documented trigger condition in ROADMAP.md / OPEN-QUESTIONS.md describing when it earns its
complexity.
