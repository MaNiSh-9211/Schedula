# BENCHMARKS

Status: **methodology + harness ready; numbers below are from dev-laptop runs and are
NOT production capacity claims** (§49: baseline → change → result → explanation, or it
didn't happen). Re-run on dedicated hardware before quoting any figure.

## Harnesses

| Suite | Tool | Measures |
| --- | --- | --- |
| API submission | `load/submit-mixed.js` (k6) | sustained POST throughput, 429 rate under quotas |
| End-to-end latency | same + `schedula_job_wait_duration` metric | enqueue→start P50/P95/P99 |
| Claim throughput | `QueueClaimIT.concurrentWorkersNeverDoubleClaim` pattern at scale | claims/sec vs worker count until DB ceiling |
| Scheduler lag | `schedula_scheduler_lag` histogram under load | dispatch delay P50/P95/P99 |
| Fairness | `CapacityLimitsIT.weightedTenantsShareTheWorker` scenario scaled up | small-tenant max wait with 1M-row neighbor (WRR bound) |

## How to run the submission benchmark

```bash
docker compose up -d postgres
SCHEDULA_DEFAULT_API_KEY=sk_00000000-0000-0000-0000-000000000001_devkey123 \
  mvn -pl app spring-boot:run &
k6 run -e RATE=500 -e DURATION=120s load/submit-mixed.js
# then: curl localhost:8080/actuator/prometheus | grep schedula_
```

## Observed on dev laptop (8-core, WSL2 Docker, NOT a capacity statement)

| Scenario | Result | Notes |
| --- | --- | --- |
| Immediate jobs end-to-end (submit→COMPLETED) | ~1–2s P95 under IT-suite noise | dominated by poll intervals (100–250ms) + DB round-trips; tune polls for lower latency |
| Concurrent claim correctness | 0 duplicate claims across 8 workers × 50 messages (repeatedly) | SKIP LOCKED invariant holds under contention |
| Lease-expiry recovery | redelivery ≤ visibility timeout + sweep interval | measured in ReliabilityIT |
| Leader failover | takeover ≤ lease duration; stale leader writes fenced | LeaseElectionIT + FencedWritesIT |

## Known ceilings & escape paths (documented before they hurt)

| Bottleneck | Symptom | Escape path (in order) |
| --- | --- | --- |
| PG queue ops | DB CPU >70% from claims | batch sizes ↑, partial indexes verified → queue partitioning → external broker (ADR-006 trigger) |
| Single leader dispatch | scheduler lag grows while workers idle | shard leadership by queue/tenant range (sketch in ARCHITECTURE §3.4) |
| Delayed-job volume | due-scan cost grows | timing-wheel experiment module (§50) — deliberately unbuilt: no measured pain yet |

## Failure benchmarks (from chaos ITs, dev laptop)

| Event | MTTR observed | Correctness |
| --- | --- | --- |
| Worker claim abandoned | ≤ visibility(4s)+sweep(0.5s) | old execution ABANDONED, redelivered exactly-once-per-round |
| Leader kill | ≤ lease duration (test uses 800ms leases) | zero corrupted state, token strictly increases |
| DB blip (~10s) | backlog drains post-recovery | zero lost submissions (fail-closed admission) |
