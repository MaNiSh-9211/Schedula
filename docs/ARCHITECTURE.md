# Architecture

Schedula follows a **three-plane model** — Control (what should run), Coordination (who decides), Data (what actually runs) — inside a modular monolith that splits into separate processes via configuration flags.

```mermaid
graph TB
    subgraph ControlPlane["Control Plane"]
        UI["Admin UI :8080"]
        CLI["schedula.sh CLI"]
        subgraph APIModule["API Module"]
            AUTH["ApiKeyAuthFilter<br/>X-API-Key / X-Admin-Key"]
            SEC["SecurityHeadersFilter<br/>CSP · nosniff · DENY"]
            QUOTA["QuotaStore<br/>backlog + rate limits"]
        end
    end

    subgraph CoordPlane["Coordination Plane"]
        COORD["Coordinator<br/>probe → acquire → renew → stepDown"]
        LEASE["scheduler_leases<br/>(CAS + fencing token)"]
        NODES["scheduler_nodes<br/>(membership heartbeat)"]
    end

    subgraph DataPlane["Data Plane"]
        SCHED["SchedulerLoop<br/>cron · interval · backfill"]
        DISPATCH["DispatchService<br/>claim → create execution"]
        WORKER["WorkerLoop ×N<br/>virtual threads per handler"]
        WFD["WorkflowDriver<br/>DAG reconciliation"]
        WEBHOOK["WebhookDispatcher<br/>signed · circuit-breaker"]
        FW["CascadeFirewall<br/>auto-quarantine dead deps"]
        RET["RetentionService<br/>purge terminal history"]
        PRED["PressurePredictor<br/>trend detection"]
        ORACLE["RetryOracle<br/>adaptive delay learning"]
        ANOM["AnomalyDetector<br/>Welford 3-sigma SPC"]
    end

    subgraph Store[("PostgreSQL 16")]
        direction LR
        T1["jobs"]
        T2["queue_messages"]
        T3["workflow_*"]
        T4["scheduler_leases"]
        T5["retry_oracle"]
        T6["audit_events"]
    end

    UI --> AUTH
    CLI --> AUTH
    AUTH --> QUOTA
    QUOTA --> PG
    COORD --> LEASE
    COORD --> NODES
    SCHED --> PG
    DISPATCH --> PG
    WORKER --> PG
    WFD --> PG
    ORACLE --> PG
    ANOM --> PG
```

---

## Job State Machine

Every transition is a **guarded database UPDATE** — the database is the arbiter. Two racing writers cannot both succeed.

```mermaid
stateDiagram-v2
    [*] --> CREATED : POST /v1/jobs

    CREATED --> SCHEDULED : validation passed
    CREATED --> REJECTED : validation failed

    SCHEDULED --> QUEUED : scheduler tick (leader-only)
    SCHEDULED --> PAUSED : operator pause
    SCHEDULED --> CANCELLED : cancel before dispatch

    QUEUED --> DISPATCHED : SKIP LOCKED claim
    QUEUED --> PAUSED : operator pause
    QUEUED --> CANCELLED : cancel before claim

    DISPATCHED --> RUNNING : worker starts handler
    DISPATCHED --> QUEUED : sweeper requeue (claim expired)
    DISPATCHED --> DEAD : max deliveries exceeded
    DISPATCHED --> CANCELLING : cooperative cancel

    RUNNING --> COMPLETED : handler returned successfully
    RUNNING --> RETRY_WAIT : TRANSIENT/THROTTLED error, attempts remain
    RUNNING --> DEAD : PERMANENT error or attempts exhausted
    RUNNING --> FAILED_TERMINAL : non-retryable classification
    RUNNING --> QUEUED : sweeper redelivery (lease expired)
    RUNNING --> CANCELLING : cooperative cancel requested

    CANCELLING --> CANCELLED : worker acknowledges cancel
    CANCELLING --> DEAD : lease expired during cancellation

    RETRY_WAIT --> QUEUED : retry due (next_attempt_at reached)
    RETRY_WAIT --> DEAD : attempts exhausted during wait
    RETRY_WAIT --> CANCELLED : cancelled while waiting

    PAUSED --> SCHEDULED : resume
    PAUSED --> CANCELLED : cancelled while paused

    COMPLETED --> [*]
    FAILED_TERMINAL --> [*]
    DEAD --> [*]
    CANCELLED --> [*]
    REJECTED --> [*]
```

### Key invariant

> COMPLETED jobs can never execute again. Only `POST /v1/jobs/{id}/retry` creates a NEW job linked to the original by idempotency lineage.

---

## Worker Lifecycle

```mermaid
stateDiagram-v2
    [*] --> HEALTHY : register(capabilities, queues, cpu, mem)

    HEALTHY --> DRAINING : operator drain or graceful shutdown
    HEALTHY --> UNHEALTHY : silent > unhealthyAfterMs (15s default)
    UNHEALTHY --> DEAD : silent > deadAfterMs (60s default)
    DRAINING --> DEAD : silent > deadAfterMs

    note right of DRAINING
        Draining workers receive NO new claims.
        Existing jobs finish or expire naturally.
    end note

    DEAD --> [*] : purged after 7 days by retention sweeper
```

---

## Distributed Locking (Three Layers)

```mermaid
flowchart TD
    subgraph L1["Layer 1: Leadership Lock"]
        A1["scheduler_leases row"] -->|"CAS takeover if expired"| A2["One active leader per cluster"]
        A1 -->|"fencing_token incremented per grant"| A3["Monotonic counter: token N+1 > token N"]
    end

    subgraph L2["Layer 2: Execution Ownership"]
        B1["execution.fencing_token"] -->|"only current holder can write outcome"| B2["Stale writes match 0 rows = discarded"]
        B1 -->|"renewal every lease/3 extends claim + lease"| B3["Ownership stays alive"]
    end

    subgraph L3["Layer 3: Claim Mutex"]
        C1["FOR UPDATE SKIP LOCKED"] -->|"Postgres row-level mutex"| C2["N workers compete, zero double-claims"]
    end

    L2 -.->|"if renewal fails, token is stale"| L1
```

---

## Leader Election Sequence

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Node B
    participant DB as scheduler_leases

    Note over A,DB: Startup: both nodes register in scheduler_nodes

    A->>DB: CAS acquire (lease missing or expired?)
    DB-->>A: fencing_token=42, expires_at=now()+15s
    Note over A: A becomes LEADER (token=42)

    B->>DB: CAS acquire (lease held by A, not expired)
    DB-->>B: 0 rows → stay FOLLOWER

    Note over A: A crashes (no renewal sent)

    DB->>DB: Lease expires at now+15s

    B->>DB: CAS acquire (expires_at < now ✓)
    DB-->>B: fencing_token=43 (> 42 ✓)
    Note over B: B becomes LEADER (token=43)

    A wakes up and tries to renew
    A->>DB: renew WHERE owner=A AND token=42
    DB-->>A: 0 rows (owner changed to B) → STEP DOWN

    A attempts fenced write with old token 42
    A->>DB: UPDATE ... AND EXISTS(token=42 AND not expired)
    DB-->>A: 0 rows → WRITE INERT (cannot corrupt)
```

---

## Workflow Engine Execution

```mermaid
sequenceDiagram
    participant API
    participant Driver as WorkflowDriver (leader only)
    participant Jobs as Platform Jobs
    participant Worker as WorkerLoop
    participant Timer as workflow_timers

    API->>Driver: POST /v1/workflows/{name}/executions
    Driver->>Driver: Parse definition, validate DAG (no cycles)
    Driver->>Driver: Insert all tasks as BLOCKED

    loop Every driver tick until all tasks terminal
        Driver->>Timer: Fire due WAIT timers (ACTIVE → FIRED)
        Driver->>Driver: Reconcile finished backing jobs
        Driver->>Driver: Check consumed signals for SIGNAL tasks
        Driver->>Driver: Check completed child workflows for CHILD tasks

        loop For each BLOCKED task with satisfied deps
            alt JOB task
                Driver->>Jobs: Create platform job (inherits retries/timeouts/DLQ)
            else WAIT task
                Driver->>Timer: Insert durable timer row
            else SIGNAL task
                Note over Driver: Stays RUNNING until signal consumed
            else CHILD task
                Driver->>Driver: Start child workflow execution
            end
        end
    end

    Driver->>Driver: All tasks SUCCEEDED → workflow COMPLETED
```

---

## Compensation Flow

```mermaid
sequenceDiagram
    participant Driver as WorkflowDriver
    participant Jobs as Platform Jobs

    Note over Driver: Task 'blast' FAILED_PERMANENT

    Driver->>Driver: Workflow status → FAILING
    Driver->>Driver: For each succeeded task with undo spec (reverse order)
    Driver->>Jobs: Create compensation job (undo payload wraps original)
    Jobs->>Jobs: Compensation executes (at-least-once)

    alt All compensations succeed
        Driver->>Driver: Workflow FAILED compensated=true
    else Any compensation fails after retries
        Driver->>Driver: Workflow FAILED compensated=false
        Note over Driver: Operator must manually resolve
    end
```

> **Compensation is NOT rollback.** It is forward-running undo logic whose effects are themselves at-least-once and must be idempotent.

---

## Adaptive Retry Oracle Learning Loop

```mermaid
flowchart TD
    A[Job attempt fails with error_class E] --> B{Oracle has ≥5 samples for this type+class+attempt?}
    B -- Yes --> C[Use empirically optimal delay bucket]
    B -- No --> D[Use configured exponential backoff]

    C --> E[Nack message: available_at = now + delay]
    D --> E

    E --> F[Message becomes READY after delay]
    F --> G[Worker claims and executes retry]
    G --> H{Attempt succeeded?}

    H -- Yes --> I["Oracle.record(type, E, attempt, delay, SUCCESS)"]
    H -- No --> J["Oracle.record(type, E, attempt, delay, FAILURE)"]

    I --> K[Oracle learns: this bucket works]
    J --> L[Oracle learns: this bucket doesn't work]
    L --> M{Next retry: pick different bucket}
```

---

## Cascade Failure Firewall

```mermaid
flowchart TD
    A[Job execution fails] --> B{Extract hostname from error_detail}
    B -->|found| C["dependency_health.failure_count += 1"]
    C --> D{count ≥ threshold within window?}
    D -- Yes --> E{Already quarantined?}
    E -- No --> F[🔥 QUARANTINE: cancel all READY messages targeting host]
    F --> G[Sweeper TCP-checks host every sweepMs×4]
    E -- Yes --> G
    G --> H{Host reachable?}
    H -- Yes --> I[Release quarantined messages to READY]
    H -- No --> G
```

---

## Webhook Circuit Breaker

```mel
stateDiagram-v2
    [*] --> CLOSED : webhook registered on job

    CLOSED --> OPEN : 5 consecutive delivery failures
    OPEN --> HALF_OPEN : cooldown expires (60s)
    HALF_OPEN --> CLOSED : next delivery succeeds
    HALF_OPEN --> OPEN : next delivery fails
```

---

## Predictive Health Scoring Gate

```mermaid
flowchart TD
    A[WorkerLoop poll cycle] --> B{Health score ≥ 15?}
    B -- No --> C[Park 4× longer than normal backoff]
    C --> A
    B -- Yes --> D[Claim batch via dispatcher]

    subgraph "Score factors"
        F1["Historical success rate (±30)"]
        F2["Queue pressure (−20 max)"]
        F3["Error velocity (−25 max)"]
        F4["Worker pool health (+15)"]
    end

    subgraph "Score ranges"
        S1["80-100: excellent → normal claiming"]
        S2["50-79: normal → normal claiming"]
        S3["15-49: degraded → reduced claiming"]
        S4["0-14: critical → worker backs off"]
    end
```

---

## Module Dependency Graph

```mermaid
graph TD
    APP["app<br/>(bootable assembly)"]

    APP --> API["api<br/>(REST controllers, auth filters)"]
    APP --> ENGINE["engine<br/>(scheduler, recovery, webhooks, oracle)"]
    APP --> WR["worker-runtime<br/>(handler loop, affinity)"]
    APP --> DISP["dispatcher<br/>(claim-and-dispatch)"]

    API --> PERS["persistence<br/>(stores, Flyway migrations)"]
    API --> QUEUE["queue<br/>(PostgresQueue: claim/ack/nack/DLQ)"]
    API --> COORD["coordination<br/>(leader election, fencing)"]

    ENGINE --> PERS
    ENGINE --> COORD
    ENGINE --> QUEUE

    DISP --> PERS
    DISP --> QUEUE

    WR --> DISP
    WR --> PERS
    WR --> QUEUE

    PERS --> COMMON["common<br/>(models, state machines, retry math)"]
    COORD --> COMMON
    QUEUE --> COMMON

    subgraph "External Dependencies"
        SPRING["Spring Boot 3.5"]
        PG["PostgreSQL 16"]
        MICROMETER["Micrometer + OTel"]
    end
```

---

## Deployment Topologies

### Development (single JVM)

All roles in one process. Demo profile seeds sample data.

```
java -jar app.jar --spring.profiles.active=demo
```

### Production (Kubernetes, 3 roles split)

| Component | Replicas | Scaling | Notes |
|-----------|----------|---------|-------|
| API | 2 | manual | Stateless behind load balancer |
| Scheduler | 3 | fixed | Leader-elected via PG lease; only leader acts |
| Workers | 3→HPA | queue-depth gauge | Subscribed to named queues; capability-matched |

Graceful shutdown: SIGTERM → stop claiming → finish inflight (≤30s) → release leadership → deregister → exit.
