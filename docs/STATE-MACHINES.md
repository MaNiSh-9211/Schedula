# STATE MACHINES

Status: Phase 0 proposal. Covers requirements §9, §10, §16, §24, §42.

State is never encoded as ad-hoc strings scattered in code. Each machine below has an
explicit transition table implemented as guarded database updates; illegal transitions fail
closed. Every transition emits a `job_events`/audit row with actor, timestamp, reason, and
(fencing token where applicable).

---

## 1. Job state machine

```
                       submit
                          |
                          v
                      CREATED ──(validation fails)──> REJECTED
                          |
                     scheduler marks eligible
                          v
                      SCHEDULED ──────────────┐
                          | due               | pause (admin)
                          v                   v
                      QUEUED <──────────> PAUSED
                          | ^                |
             dispatcher   | | retry requeue  | resume
             claims slot  v |________________|
                      DISPATCHED
                          | worker claims message,
                          | execution lease granted
                          v
                       RUNNING ────cooperative cancel request────> CANCELLING
                       | | \                                          |
          success      | |  \ handler error                           | worker confirms
                       | |   \                                         v
                       | |    +--> RETRY_WAIT (attempts remain)    CANCELLED
                       | |              |
                       | |        backoff elapsed
                       | |              └──> QUEUED
                       | |
                       | +--> FAILED_TERMINAL (non-retryable classification)
                       |         (attempts exhausted)
                       v
                    COMPLETED     FAILED_TERMINAL ──(attempts exhausted)──> DEAD
                                                                            (DLQ)
```

Terminal states: `COMPLETED`, `FAILED_TERMINAL`, `DEAD`, `CANCELLED`, `REJECTED`.

### Transition table (excerpt — authoritative list lives next to the code)

| From | To | Actor | Guard / concurrency rule | Notes |
| --- | --- | --- | --- | --- |
| CREATED | SCHEDULED | Scheduler | `scheduled_for` set or immediate | emits JOB_SCHEDULED |
| SCHEDULED | QUEUED | Dispatcher | leader+fencing token valid | enqueue + transition atomic |
| QUEUED | DISPATCHED | Dispatcher | queue message claimed | capacity checked first |
| DISPATCHED | RUNNING | Worker | valid execution lease + fencing token | heartbeat/lease renewal begins |
| RUNNING | COMPLETED | Worker | fencing token matches current | ack path |
| RUNNING | RETRY_WAIT | Worker/Sweeper | attempts < max_attempts AND error classified retryable | backoff computed at this moment |
| RUNNING/RETRY_WAIT | DEAD | Sweeper | attempts exhausted | DLQ entry + alert metric |
| RUNNING | CANCELLING | API | admin authorized | cooperative flag visible to worker |
| CANCELLING | CANCELLED | Worker or Sweeper | worker ack OR lease expired | never auto-executes afterwards |
| QUEUED | CANCELLED | API | message not yet claimed | dequeue + cancel atomic |
| PAUSED | QUEUED | API | resume | re-evaluates eligibility |

Invariants enforced (testable, §58):
- No transition out of `COMPLETED` except explicit admin `retry` (which creates a NEW job
  linked to the old one — completed history is never mutated).
- `DEAD` never auto-executes; only manual DLQ retry creates new work.
- `CANCELLED` cannot be dispatched; cancellation of a queued job wins races atomically
  (dequeue-and-cancel in one transaction).
- Only the current lease owner (matching fencing token) may move `RUNNING` anywhere.
- Every transition increments `jobs.version`; two racing transitions cannot both win.

---

## 2. Workflow execution state machine (Phase 6)

```
        submit
          v
       PENDING ──> RUNNING ──> COMPLETED
          |          |  \
          |          |   +--> FAILING ──> COMPENSATING ──> COMPENSATED / COMPENSATION_FAILED
          |          v                        (run compensations for completed tasks)
          |       (all policies exhausted on a failed branch)
          +--> CANCELLED (no tasks started)      TIMED_OUT (workflow deadline)
```

Task-level machine (per node):

```
BLOCKED (deps unmet) -> READY -> RUNNING -> SUCCEEDED
                                 |   \-> FAILED_RETRYABLE -> BACKOFF -> READY
                                  \-> FAILED_PERMANENT -> (drives workflow FAILING)
RUNNING -> SKIPPED (conditional branch not taken) | CANCELLED (workflow cancelling)
```

Invariants:
- A task cannot enter READY until all `depends_on` tasks are SUCCEEDED (or explicitly
  SKIPPED under conditional semantics).
- Workflow state is derivable from persisted task states; engine restart recomputes, never
  guesses.
- Timers are rows (`workflow_timers`); a waiting workflow holds no thread (§36).
- Task retries are independent of workflow-level retries (§37): a task failing permanently
  triggers workflow failure handling, not whole-workflow restart.
- Compensation is NOT rollback (§38): it is forward-running undo logic whose effects are
  themselves at-least-once and must be idempotent.

---

## 3. Worker lifecycle

```
REGISTERING ──> HEALTHY <────────────────> HEALTHY
                   |  ^   drain requested      |  failed health checks /
                   |  |________________________|  missed heartbeats > threshold
                   v                             v
                DRAINING <─────────────────── UNHEALTHY
                   |  finish/stop jobs,           |
                   | renew leases till done       | operator decision or
                   v                              | sustained failure
                DEREGISTERED                     DEAD
```

Rules:
- `DRAINING` workers receive no NEW claims; existing leases keep being renewed until jobs
  finish or a bounded deadline passes, then leases are released cleanly (fast recovery).
- `UNHEALTHY` is a *signal* state (heartbeats late, error rate high); it does not itself
  revoke leases — expiry does. This avoids mass reassignment from transient network blips (§17).
- `DEAD` is entered when the failure detector proves silence beyond threshold; its running
  executions become recoverable via lease expiry, not via this transition directly.
- Heartbeat interval (default 5s) < lease duration (default 15s) < failure-detection
  threshold effects; relationship formalized in COORDINATION.md §4.

---

## 4. Scheduler node lifecycle

```
STARTING ──> FOLLOWER ──(acquire lease)──> LEADER ──(renews ok)──> LEADER
                ^  |                          |
                |  +────(lease lost/expired)──+
                |                             |
                +<──────(step down: lost renewal race / fencing rejected)
                |
             SHUTDOWN (graceful: release leadership if held, deregister)
```

Rules:
- A node only performs leader duties while it holds the lease AND its writes carry the
  current fencing token. Rejection of a fenced write ⇒ immediate step-down (the token check
  is the ground truth, not belief).
- Followers run non-mutating duties (metrics, readiness) and probe the lease to take over
  quickly on leader death.
- Failover bound: new leader acts within ~lease duration after leader death (default 15s);
  measured in Phase 3 tests (INCIDENT-001).

---

## 5. Implementation notes

- Transitions live in one module (`statemachine`) as data (allowed-from sets per target
  state), reused by every writer; no component hand-rolls its own `if (status == ...)`.
- The DB is the final arbiter: application checks are advisory; the guarded UPDATE with
  `WHERE status IN (...) AND version = ?` (and `AND fencing_token = ?` where ownership
  applies) is authoritative. Zero-trust between processes.
- Illegal-transition attempts are bugs or attacks either way → log loudly + metric
  `illegal_transition_total{from,to,actor}`.
