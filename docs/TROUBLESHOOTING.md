# Troubleshooting Guide

Decision trees for the most common operational issues.

## My job is stuck in QUEUED

```mermaid
flowchart TD
    A[Job stuck QUEUED] --> B{Any HEALTHY workers?}
    B -- No --> C[Start workers or check worker status]
    B -- Yes --> D{Worker subscribed to this queue?}
    D -- No --> E[Update subscribed_queues or resubmit to correct queue]
    D -- Yes --> F{Job requires capabilities?}
    F -- Yes --> G{Worker has matching capabilities?}
    G -- No --> H[Add capabilities to worker or remove requirement]
    G -- Yes --> I{Resource requirements met?}
    I -- No --> J[Free worker resources or lower job requirements]
    I -- Yes --> K{Type at concurrency cap?}
    K -- Yes --> L[Wait for running jobs or raise limit]
    K -- No --> M[Check schedula_queue_depth metric and worker logs]
```

## My job is stuck in RUNNING

```mermaid
flowchart TD
    A[Job stuck RUNNING] --> B{Lease still valid?}
    B -- Yes --> C[Worker is executing. Check handler logs.]
    B -- No --> D[Sweeper should reclaim within visibility_timeout + sweep_interval]
    D --> E{Reclaimed?}
    E -- No --> F[Check recovery sweeper logs for errors]
    E -- Yes --> G[Job redelivered to another attempt]
```

## My workflow is stuck

```mermaid
flowchart TD
    A[Workflow stuck] --> B{Which tasks are open?}
    B -->|SIGNAL task| C{Signal delivered via API?}
    C -- No --> D[POST /v1/workflows/executions/id/signals]
    C -- Yes --> E[Check driver tick logs for signal consumption]
    B -->|CHILD task| F{Child execution completed?}
    F -- No --> G[Debug child workflow independently]
    F -- Yes --> H[Check reconcileChildWorkflows logs]
    B -->|JOB task| I{Backing job status?}
    I -- DEAD --> J[Task exhausted retries → FAILED_PERMANENT]
    I -- RUNNING --> K[Check worker claim loop logs]
```

## Leader keeps flapping

```mermaid
flowchart TD
    A[Leader changes frequently] --> B{DB latency high?}
    B -- Yes --> C[Fix DB performance — renewals failing due to timeouts]
    B -- No --> D{Multiple coordinators on same node?}
    D -- Yes --> E[Ensure one Coordinator bean per JVM]
    D -- No --> F[Check network partition between nodes and DB]
```

---

## Common Error Messages

| Error | Cause | Fix |
|-------|-------|-----|
| `no handler registered for type X` | Job submitted with unknown type | Register handler or fix type name |
| `operator does not exist: text = text[]` | Queue SQL parameter mismatch | Update PostgresQueue to latest |
| `variable might not have been initialized` | Constructor field assignment missing | Check all final fields assigned |
| `Could not obtain JDBC Connection` | PostgreSQL unreachable | Check Docker/PG status, verify connection URL |
| `fencing_token mismatch` | Stale owner attempted write | Expected during failover; alert if persistent |
