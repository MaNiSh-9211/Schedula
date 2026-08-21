# FAILURE MODES

Status: Phase 0 proposal. Covers requirements §3, §46, §62, §96, §105. This matrix is
living documentation: expanded after every implemented fault test with real observed behavior.

---

## 1. Failure matrix

| # | Failure | Detection | Recovery | Guarantee outcome |
| --- | --- | --- | --- | --- |
| F-01 | Worker crash mid-execution | execution lease expiry (sweeper) | message redelivered; new attempt row | at-least-once; duplicate possible in effect window |
| F-02 | Lost ACK (worker finished, finalize lost) | lease expiry without terminal state | redelivery; both attempts audited | duplicate execution possible; idempotency key identical; state consistent |
| F-03 | Duplicate delivery (queue-level) | deliver_count; handler dedup | second run no-ops if EffectLedger hit | harmless by design |
| F-04 | Stale worker writes after lease loss | fencing token mismatch → 0 rows | write discarded; `fenced_write_rejected_total` | stale mutation prevented; successor's outcome wins |
| F-05 | Scheduler leader crash | leader lease expiry | follower acquires ≤ lease duration | scheduling pause bounded (~15s default); measured in CHAOS-002 |
| F-06 | Leader partitioned (alive but cut off) | its renewals/writes fail | steps down on first rejected write | dual-belief window exists; dual-*action* neutralized by fencing |
| F-07 | PostgreSQL unavailable | connection/statement errors | control plane fails CLOSED (no admissions, no dispatch); running jobs continue locally; completions commit after recovery | zero acknowledged-loss; explicit degraded mode, documented |
| F-08 | DB slow (high latency, not down) | statement timeouts, pool saturation metrics | backoff + bounded retries; admission throttles | backpressure surfaces to clients as 429/503, not silent queueing |
| F-09 | Process pause (GC/VM freeze) past lease | same as F-01/F-05 paths | ownership reassigned; paused process fenced on wake | correct under arbitrary pause lengths |
| F-10 | Clock jump forward (node) | n/a (DB-time comparisons) | leases unaffected (compared DB-side); schedules evaluated per policy | ADR-008 neutralizes node-clock lies |
| F-11 | Clock jump backward (node) | n/a | same — durations from monotonic clocks | no mass lease-expiry storm |
| F-12 | Network partition worker↔DB | heartbeats stop; renewals fail | worker enters degraded: stops claiming, tries graceful cancel of handlers | no new work into a black hole; existing handled per F-01 |
| F-13 | Network partition scheduler↔DB | renewal failure | step down (F-06) | leadership fails closed |
| F-14 | Queue growth unbounded (hot tenant) | depth gauges vs quota | admission rejects 429; fairness bounds others' wait | backpressure explicit; no silent collapse |
| F-15 | Retry storm (dependency outage) | retry-rate metrics per type/class | exponential+jitter backoff; per-type caps; classification stops non-retryables | storms self-limiting |
| F-16 | Poison job (crashes worker repeatedly) | deliver_count hits max_deliveries | DEADLETTERED → DLQ; never re-dispatched automatically | fleet protected; visible + retryable manually |
| F-17 | Workflow engine crash mid-fan-out | process death; state all in PG | restart recomputes READY tasks from persisted deps | resume-exactly; no lost/duplicated task starts beyond at-least-once contract |
| F-18 | Durable timer missed during downtime | timer sweep on recovery | fires once post-recovery (coalesce semantics) | WAIT survives restarts; no thread held |
| F-19 | Split brain (two leaders believe) | fencing rejections on old leader | old leader steps down; metrics alert | zero corrupted state; tested by CHAOS-002 |
| F-20 | Cancellation stuck (CANCELLING forever) | stuck-state sweeper + metric | lease expiry path closes it CANCELLED | cancellation always terminates |

## 2. Deep dives on the hard cases

### Lost ACK / duplicate execution window (F-01–F-03)
The dangerous sequence: effect lands externally → crash before ACK → redelivery. The system
cannot shrink the external-effect duplication below "one extra execution per occurrence of
this failure" — it makes it *bounded, visible, and harmless*: identical idempotency key
delivered to handler, EffectLedger checkpoint available, attempts audited. Handlers that
cannot deduplicate must be flagged in their capability metadata so submitters see the risk.

### Stale owner (F-04, F-06, F-19)
Belief vs authority is separated: belief = in-memory leader/owner flag; authority =
lease row + token match. All mutating SQL carries the token predicate, so the database —
not any process's opinion — decides whose actions count. Step-down is triggered by
rejection, never by timeout-guessing.

### Database failure (F-07, F-08)
Chosen posture: **fail closed** for anything that would mutate state without full
durability. Rationale: a scheduler's worst outcome is silently accepting work it cannot
track ("accepted then vanished"), worse than a visible outage. Clients get clean errors;
recovery drains backlog automatically; MTTR measured in CHAOS-003.

### Clock events (F-10, F-11)
Because instants are compared where they were written (PG) and durations use monotonic
clocks (ADR-008), node clock jumps degrade nothing. Residual exposure: PG host's own clock
(NTP-managed; operational requirement documented). Far-future schedule materialization is
avoided by lazy next-fire computation, absorbing tzdb/clock corrections gracefully.

## 3. Incident drill catalog (§96 — executed in later phases, docs written from real runs)

INCIDENT-001..008 map to: leader crash (F-05), Redis/dep unavailability (n/a until such dep
exists — drill becomes PG blip), fleet loss 30% (CHAOS-007), DB latency 5s (F-08),
tenant submits 10M jobs (CHAOS-008), clock forward (F-10), clock backward (F-11),
duplicate execution (F-03). Each drill produces: symptoms, detection signal, root cause,
recovery timeline, prevention note — attached to this file as `incidents/` entries.
