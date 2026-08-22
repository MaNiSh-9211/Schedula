# OPERATIONS

Quick operator reference. Full semantics live in `docs/`.

## Running locally

```bash
docker compose up -d postgres
mvn -pl app spring-boot:run
# app prints a bootstrapped default-tenant API key on first start (or set SCHEDULA_DEFAULT_API_KEY)
```

## CLI

```bash
export SCHEDULA_URL=http://localhost:8080
export SCHEDULA_KEY=<the key from startup log>
./schedula.sh submit log '{"msg":"hi"}'
./schedula.sh status <jobId>
./schedula.sh list RUNNING
./schedula.sh dlq && ./schedula.sh dlq-retry <messageId>
./schedula.sh schedulers        # leader + fencing token + nodes
./schedula.sh workers           # fleet health/utilization
./schedula.sh metrics           # prometheus scrape (schedula_*)
```

## Auth model

- Every `/v1/**` call needs `X-API-Key: sk_<tenantId>_<secret>` (or admin key).
- Tenant scope is forced from the key; admins use `X-Admin-Key` for cross-tenant ops.
- `POST /v1/admin/tenants {"name":...}` mints a tenant; the returned key is shown ONCE.
- Disable with `SCHEDULA_AUTH_ENABLED=false` (dev only).

## Key operational signals

| Signal | Healthy | Investigate when |
| --- | --- | --- |
| `schedula_scheduler_lag` P99 | < ~1s | sustained growth ⇒ scheduler can't keep up |
| `schedula_queue_depth` gauge | drains to 0 | monotonic growth ⇒ add workers / check caps |
| `schedula_fenced_write_rejected_total` | 0 in steady state | >0 outside failovers ⇒ stale owner active |
| `leader_changes_total` | rare spikes | frequent flips ⇒ DB latency or clock issues |
| `schedula_tenant_throttled_total` | per design | spikes ⇒ tenant hitting backlog quota (by design) |
| jobs stuck `CANCELLING` > lease+sweep | none | handler ignoring cancellation token |

## Autoscaling (k8s)

Workers scale on **queue depth**, never CPU (§40): `schedula_queue_depth` gauge is
exported per instance; wire prometheus-adapter to it (`k8s/worker.yaml` includes the
deployment shape; HPA rule ships with your monitoring stack).

## Failure handling quick reference

| Event | What happens automatically |
| --- | --- |
| Worker dies mid-job | claim expires → redelivered; old execution ABANDONED; fencing blocks late writes |
| Scheduler leader dies | followers take over ≤ lease duration; stale leader's writes rejected |
| DB outage | control plane fails CLOSED; no silent work loss; recovers and drains backlog |
| Poison job | DEADLETTERED after max deliveries → inspect/replay/delete via `/v1/dlq` |
| Clock jumps | harmless by design: instants compared DB-side, durations monotonic (ADR-008) |
