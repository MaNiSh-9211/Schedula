# Architecture

Schedula follows a **three-plane model**: Control Plane (what should run), Coordination Plane (who decides), and Data Plane (what actually runs). Every component is a Spring-managed bean inside a modular monolith that splits into separate processes via configuration flags.

```mermaid
graph TB
    subgraph "Control Plane"
        UI[Admin UI :8080]
        CLI[schedula.sh CLI]
        GW[API Gateway<br/>external]

        subgraph "API Module"
            REST[REST /v1/**]
            AUTH[ApiKeyAuthFilter]
            SEC[SecurityHeadersFilter]
            RATE[QuotaStore Rate Limits]
        end
    end

    subgraph "Coordination Plane"
        COORD[Coordinator]
        LEASE[scheduler_leases table]
        FENCE[fence_counters table]
    end

    subgraph "Data Plane"
        SCHED[SchedulerLoop]
        DISPATCH[DispatchService]
        WORKER[WorkerLoop xN]
        WF[WorkflowDriver]
        RET[RetentionService]
        WHD[WebhookDispatcher]
        FW[CascadeFirewall]
        PRED[PressurePredictor]
    end

    subgraph "Persistence"
        PG[(PostgreSQL 16)]
        FLY[Flyway Migrations V1-V15]
    end

    UI --> REST
    CLI --> REST
    GW --> REST
    REST --> AUTH --> SEC --> RATE
    RATE --> PG
    COORD --- LEASE
    COORD --- FENCE
    SCHED --> PG
    DISPATCH --> PG
    WORKER --> PG
    WF --> PG
    RET --> PG
    WHD --> PG
```

## Three-Plane Separation

| Plane | Responsibility | Components | Failure behavior |
|-------|---------------|------------|------------------|
| Control | Accept work, validate, persist durably | API module | Returns errors if DB unreachable |
| Coordination | Decide WHO acts | Coordinator + lease/fence tables | Fail-closed: no leader = no scheduling |
| Data | Actually do work | WorkerLoop, SchedulerLoop, WorkflowDriver | Retry with backoff, fail-closed |

---

## Job Lifecycle

Every job follows this state machine. All transitions are **guarded database updates** — two racing writers cannot both succeed.

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED : submit (durable-before-ack)
    SCHEDULED --> QUEUED : scheduler tick + enqueue (same tx)
    QUEUED --> DISPATCHED : SKIP LOCKED claim + execution created
    DISPATCHED --> RUNNING : worker starts handler
    RUNNING --> COMPLETED : handler returns (fencing-guarded)
    RUNNING --> RETRY_WAIT : TRANSIENT/THROTTLED error
    RETRY_WAIT --> QUEUED : backoff elapsed
    RUNNING --> DEAD : PERMANENT error or attempts exhausted
    RETRY_WAIT --> DEAD : max attempts reached
    RUNNING --> CANCELLING : cooperative cancel requested
    CANCELLING --> CANCELLED : worker acknowledges
    SCHEDULED --> PAUSED : operator pause
    QUEUED --> PAUSED : operator pause
    PAUSED --> SCHEDULED : resume
    SCHEDULED --> CANCELLED : cancel before dispatch
    QUEUED --> CANCELLED : cancel before dispatch
    COMPLETED --> [*]
    DEAD --> [*]
    CANCELLED --> [*]
```

### Key invariant

> A COMPLETED job can never transition back to RUNNING. Only `POST /retry` creates a NEW job linked to the original.

---

## Distributed Locking (Three Layers)

```mermaid
flowchart LR
    subgraph "Layer 1: Leadership"
        L1[scheduler_leases row] -->|CAS takeover| L2[One active leader]
        L1 -->|fencing token per grant| L3[Monotonic counter]
    end

    subgraph "Layer 2: Execution Ownership"
        E1[execution.fencing_token] -->|only holder writes outcome| E2[Stale writes = 0 rows]
        E2 -->|renewal at lease/3| E3[Lease stays alive]
    end

    subgraph "Layer 3: Claim Mutex"
        Q1[FOR UPDATE SKIP LOCKED] -->|per-message mutex| Q2[N workers, zero double-claims]
    end
```

### How stale owners are neutralized

When a worker loses its lease (GC pause, network partition), a new owner takes over. The old worker's fencing token no longer matches, so every UPDATE it attempts matches zero rows. The stale owner cannot corrupt state even though it still *believes* it owns the job.

```sql
-- This is what a stale worker's write looks like:
UPDATE job_executions SET status = 'COMPLETED'
WHERE id = ? AND fencing_token = 42   -- token is stale; new token = 43
-- Result: 0 rows affected. Write is silently discarded.
```

---

## Workflow Engine

Workflows are DAGs of tasks. Each task spawns a REAL platform job, inheriting retries, leases, timeouts, and DLQ handling.

```mermaid
sequenceDiagram
    participant API
    participant Driver as WorkflowDriver
    participant Jobs as Job Store
    participant Worker as WorkerLoop
    participant DB as PostgreSQL

    API->>DB: Register definition (versioned, immutable)
    API->>Driver: Start execution
    Driver->>DB: Insert all tasks as BLOCKED

    loop Every tick until done
        Driver->>DB: Reconcile finished jobs
        Driver->>DB: Unblock ready tasks (deps satisfied)

        Note over Driver: Signal task? Stay RUNNING until signal arrives
        Note over Driver: Wait task? Create durable timer row
        Note over Driver: Child workflow? Spawn child execution
        Note over Driver: JOB task? Create platform job

        Driver->>Jobs: Create backing job per unblocked task
        Worker->>DB: Claim job, execute handler, report result
        Worker->>DB: Task SUCCEEDED / FAILED
    end

    Driver->>DB: All tasks terminal → workflow COMPLETED
```

### Compensation flow

```mermaid
sequenceDiagram
    participant Driver
    participant DB

    Note over Driver: Task 'charge' FAILED_PERMANENT
    Driver->>DB: Workflow status → FAILING
    Driver->>DB: Insert UNDO tasks (reverse order) for succeeded tasks
    loop For each UNDO task
        Driver->>DB: Create compensation job
        Worker->>DB: Execute undo → SUCCEEDED
    end
    Driver->>DB: All undos done → workflow FAILED compensated=true
```

---

## Adaptive Retry Oracle

```mermaid
flowchart TD
    A[Job fails with THROTTLED] --> B{Oracle has ≥5 samples?}
    B -- Yes --> C[Use empirically best delay bucket]
    B -- No --> D[Use configured exponential backoff]
    C --> E[Nack message with delay]
    D --> E
    E --> F[Message becomes READY after delay]
    F --> G[Worker claims retry attempt]
    G --> H{Succeeded?}
    H -- Yes --> I[Record SUCCESS in bucket]
    H -- No --> J[Record FAILURE in bucket]
    J --> A
```

---

## Cascade Failure Firewall

```mermaid
flowchart TD
    A[Job fails] --> B{Extract host from error}
    B -->|found| C[Increment failure_count for host]
    C --> D{count >= 10 in 5min?}
    D -- Yes --> E[QUARANTINE: cancel all READY messages targeting host]
    D -- No --> F[Normal retry path]

    E --> G[Sweeper TCP-checks host every 20s]
    G --> H{Reachable?}
    H -- Yes --> I[Release quarantined jobs to READY]
    H -- No --> G
```

---

## Leader Election

```mermaid
sequenceDiagram
    participant F1 as Node A (Follower)
    participant F2 as Node B (Follower)
    participant DB as scheduler_leases table

    F1->>DB: CAS: acquire if expired or mine
    DB-->>F1: Token=42, expires in 15s
    Note over F1: A is LEADER with fence token 42

    F2->>DB: CAS: acquire (lease held by A, not expired)
    DB-->>F2: 0 rows → stay FOLLOWER

    A crashes (no renewal)

    DB->>DB: Lease expires after 15s
    F2->>DB: CAS: acquire (expired!)
    DB-->>F2: Token=43 (> 42), expires in 15s
    Note over F2: B is LEADER with fence token 43

    A wakes up, tries to renew token 42
    DB-->>A: 0 rows → STEP DOWN
    A tries fenced write with token 42
    DB-->>A: 0 rows → WRITE REJECTED
```

---

## Deployment Topologies

### Development (single JVM)

```
java -jar app.jar --schedula.roles.api=true --schedula.roles.scheduler=true --schedula.roles.worker=true
```

### Production (Kubernetes)

```
API Deployment (2 replicas)          ← readiness: /actuator/health/readiness
Scheduler Deployment (3 replicas)    ← leader-elected, PDB minAvailable=1
Worker Deployment (HPA-scaled)       ← subscribed_queues, capabilities, PDB
PostgreSQL StatefulSet or RDS        ← streaming replication recommended
Grafana + Prometheus                 ← dashboard JSON + alert rules included
OTel Collector                       ← OTLP traces endpoint
```

---

## Module Dependency Graph

```
app ──→ api ──→ persistence ──→ common
 │                │
 ├──→ engine ──→ coordination
 │         │         │
 │         ▼         │
 ├──→ worker-runtime
 │         │
 └──→ dispatcher ──→ queue
                      │
                      └──→ persistence
```

Each module has a single responsibility. Boundaries are enforced by Maven module scoping.
