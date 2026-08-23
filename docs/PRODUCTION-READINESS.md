# PRODUCTION READINESS AUDIT

Full codebase scan: 66 source files, 16 test files, 9 migrations, 27 REST endpoints,
23 indexes. This document lists every feature, every gap, and every issue found.

---

## PART 1 — COMPLETE FEATURE INVENTORY

### Job Lifecycle ✅
| Feature | Status | Notes |
| --- | --- | --- |
| Immediate execution | ✅ | born SCHEDULED, picked up next tick |
| Delayed execution | ✅ | `scheduledFor` any future ISO instant |
| Fixed-interval recurrence | ✅ | COALESCE / SKIP_TO_LATEST / RUN_ALL (capped) |
| Cron recurrence + timezone + DST | ✅ | Spring CronExpression, tz-aware, catch-up capped at 10k |
| Schedule → workflow trigger | ✅ | `targetWorkflow` field fires DAG executions |
| Backfill (RUN_ALL) | ✅ | materializes every missed window up to 10k |
| Priority levels | ✅ | integer priority, claim ORDER BY priority DESC |
| Named queues | ✅ | submit with `queueName`, workers subscribe via config |
| Capability matching | ✅ | `requiredCapabilities` ⊆ worker capabilities |
| Resource requirements (CPU/MEM) | ✅ | worker capacity minus running jobs = free floor |
| Per-type concurrency caps | ✅ | `job_type_limits` table, enforced per accepted message |
| Per-tenant concurrency caps | ✅ | `tenant_quotas.max_concurrent_executions` |
| Per-tenant backlog quota | ✅ | `max_pending_jobs` → 429 on breach |
| Submission rate limiting | ✅ | `max_submit_per_min` sliding window |
| Weighted fair dispatch | ✅ | DRR across tenants by `tenants.weight` |
| Retry engine | ✅ | FIXED/EXPONENTIAL/EXPONENTIAL_JITTERED, error classification |
| Dead-letter queue | ✅ | after max deliveries; list/retry/delete via API+UI |
| Job timeouts | ✅ | per-job `timeoutMs`, virtual-thread future.get |
| Cooperative cancellation | ✅ | CANCELLING state, renewal-channel token delivery |
| Pause/resume | ✅ | PAUSED state, atomic message removal |
| Bulk submission | ✅ | POST /v1/jobs/batch, up to 500 in one tx |
| Idempotency keys | ✅ | UNIQUE constraint, replay returns original |
| Keyset pagination | ✅ | `before=<ISO>` param + X-Next-Cursor header |

### Workflow Engine ✅
| Feature | Status | Notes |
| --- | --- | --- |
| DAG definitions (versioned) | ✅ | immutable versions, cycle detection at registration |
| Parallel branch execution | ✅ | tasks fan out when deps satisfied |
| Task-level retries | ✅ | independent of workflow retries (§37) |
| Durable wait timers | ✅ | rows in `workflow_timers`, survive restarts |
| Compensation (undo) | ✅ | reverse-order undo jobs on failure |
| Crash-resume from rows | ✅ | driver reconciles purely from persisted state |
| Workflow cancellation | ✅ | cascades to backing jobs |
| Schedule→workflow trigger | ✅ | cron/interval schedules fire executions |

### Coordination & Fault Tolerance ✅
| Feature | Status | Notes |
| --- | --- | --- |
| Leader election | ✅ | PG lease row, CAS takeover, bounded failover |
| Fencing tokens | ✅ | monotonic per resource, stale writes rejected |
| Split-brain neutralization | ✅ | dual-belief harmless, actions fenced |
| Execution leases + renewal | ✅ | lease/3 interval, extends claim AND execution |
| Worker heartbeats | ✅ | 5s interval, UNHEALTHY/DEAD detection |
| Graceful drain | ✅ | stop claiming → finish inflight → deregister |
| Clock-jump resilience | ✅ | DB-time comparisons, monotonic durations |
| Transactional enqueue | ✅ | job+message commit together (no dual-write) |

### Observability ✅
| Feature | Status | Notes |
| --- | --- | --- |
| Prometheus metrics (30+) | ✅ | counters, timers, histograms, gauges |
| Scheduler lag P50/P95/P99 | ✅ | histogram with percentile publishing |
| Queue depth gauge | ✅ | autoscaling signal (not CPU) |
| Structured logging with MDC | ✅ | `[job=X exec=Y]` on every worker log line |
| Grafana dashboard JSON | ✅ | `k8s/grafana-dashboard.json` |
| Prometheus alert rules | ✅ | `k8s/prometheus-alerts.yaml` (6 rules) |
| Health/readiness/liveness probes | ✅ | actuator endpoints |

### Security ✅
| Feature | Status | Notes |
| --- | --- | --- |
| API key authN | ✅ | SHA-256 hashed, prefix-indexed, shown once |
| Tenant isolation | ✅ | key scope forced, admin cross-tenant audited |
| Admin master key | ✅ | env-configured, platform-scope only |
| Key rotation | ✅ | POST /rotate, old key dies instantly |
| Audit trail (append-only) | ✅ | admin actions, DLQ ops, tenant changes |
| Rate limiting per tenant | ✅ | sliding 60s window |
| No arbitrary code execution | ✅ | typed handler registry only |

### Operations ✅
| Feature | Status | Notes |
| --- | --- | --- |
| Admin dashboard UI | ✅ | overview/jobs/schedules/workflows/DLQ/fleet/audits |
| Operator CLI (`schedula.sh`) | ✅ | submit/status/cancel/retry/dlq/workers/metrics |
| Docker Compose dev env | ✅ | postgres + app, fixed dev keys |
| Kubernetes manifests | ✅ | namespace/secrets/postgres/api/scheduler/worker/PDB |
| Retention sweeper | ✅ | terminal history purged after N hours, archival export |
| Chaos drill scripts | ✅ | leader-kill test with PASS/FAIL assertions |

---

## PART 2 — WHAT'S MISSING FOR PRODUCTION (ranked by severity)

### 🔴 CRITICAL — must fix before real traffic

**C1. No TLS anywhere.** API serves plain HTTP. DB connection is unencrypted.
K8s Service is ClusterIP without TLS termination. In production you need:
TLS cert on the ingress/API, `sslmode=require` on JDBC URL, and encrypted
internal traffic (mTLS or NetworkPolicy). Effort: ~2 days with cert-manager.

**C2. No CORS or security headers.** The admin UI is served same-origin so it
works, but any dashboard on a different origin will fail. Missing
`X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security`,
`Content-Security-Policy`. Effort: ~2h with a filter.

**C3. Single PostgreSQL instance = single point of failure.** No streaming
replication, no automatic failover, no PITR backup config. If the primary
dies, the entire platform stops (fail-closed by design). You need either
managed PG with HA (RDS/CloudSQL) or Patroni/pgBouncer setup.
Effort: infrastructure work, not code.

**C4. Connection pool not sized per role.** All roles share
`maximum-pool-size: 10`. A worker under load can starve the scheduler's
DB connections. Each role needs its own pool sizing (API: 20,
scheduler: 5, worker: 10). Effort: ~1h config split.

**C5. Webhook dispatcher uses System.out.println debug lines.** Four
leftover debug statements in production code path. Replace with proper
SLF4J logging. Effort: 15 minutes.

---

### 🟡 HIGH — should fix before production

**H1. No OpenAPI/Swagger documentation.** 27 endpoints, no machine-readable
spec. Add springdoc-openapi dependency for auto-generated docs.
Effort: ~30min dependency + annotations.

**H2. No request timeout middleware.** A slow query blocks a Tomcat thread
indefinitely. Statement timeout exists (5s) but no HTTP request timeout.
Add `spring.mvc.async.request-timeout` or server.tomcat.connection-timeout.

**H3. No graceful shutdown for scheduler leadership release.** On SIGTERM
the scheduler waits for lease expiry (up to 15s) instead of releasing
leadership proactively. This slows rolling deployments.
Fix: call `coordinator.stepDown()` in lifecycle stop().

**H4. No OTel tracing.** Metrics exist but there's zero distributed tracing.
You can't follow one job's journey across submit→schedule→dispatch→execute.
OpenTelemetry SDK + OTLP exporter needed. Effort: ~1 day.

**H5. No payload encryption at rest.** Job payloads are stored as plain
JSONB. Any DB access (including DBAs) sees sensitive data. Envelope
encryption per tenant needed for regulated industries. Effort: ~3 days.

**H6. No circuit breaker on webhook deliveries.** If a webhook endpoint goes
down, we keep retrying 5× then mark FAILED, but the next batch starts fresh.
A circuit breaker would stop hammering a dead endpoint. Effort: ~4h.

**H7. No bulk operations on DLQ.** Can retry/delete one dead letter at a time.
Bulk retry-by-filter and delete-by-filter are standard enterprise features.
Effort: ~2h.

**H8. Worker deregistration on crash leaves stale rows.** DEAD workers stay
in the table forever (no cleanup). Add retention sweep for workers dead > 7d.

**H9. No database migration rollback strategy.** Flyway forward-only. For
production you need documented rollback procedures per migration.

**H10. UI has no authentication.** The admin dashboard is accessible without
login. It sends whatever key you paste into localStorage, but the page itself
is public. Need at minimum a login gate that stores the key in an httpOnly cookie.

---

### 🟠 MEDIUM — improve before scale

**M1. Offset pagination still available alongside keyset.** Old `offset` param
still works; deprecate it to prevent deep-scan performance issues.

**M2. No LISTEN/NOTIFY wakeups.** Polling adds up to `pollIntervalMs` latency
per hop. NOTIFY would cut P99 wait-latency significantly. Documented experiment.

**M3. No workflow signals or child workflows.** Temporal-class feature: send
events into running workflows, spawn sub-workflows. Currently impossible.

**M4. No job tags/labels for filtering.** Common pattern: tag jobs with
metadata (env=prod, team=payments), filter by tag. Missing.

**M5. No SLA tracking.** Define expected duration per job type; alert when
exceeded. Common enterprise requirement. Missing.

**M6. Connection pool monitoring missing.** HikariCP exposes metrics but none
are registered to Micrometer. Pool exhaustion is invisible.

**M7. No database index on `queue_messages.tenant_id`.** Tenant-scoped queue
depth queries scan all messages. One index fixes it.

**M8. Webhook HMAC secret is global.** All tenants share the same signing
secret. Per-tenant secrets with rotation needed.

**M9. No graceful shutdown for webhook HTTP client.** HttpClient has no
close/shutdown hook. Connection leak potential on restart.

**M10. No database connection health check between queries.** Hikari validates
on checkout but doesn't detect mid-transaction failures proactively.

---

### 🟢 LOW — nice-to-have polish

**L1. No DAG graph visualization in UI.** Workflows show task list, not visual
graph. Airflow/Temporal have this.

**L2. No dark/light theme toggle.** UI is dark-only.

**L3. No WebSocket live updates in UI.** Polling only.

**L4. No CLI autocomplete.**

**L5. No multi-language docs.**

**L6. Timing wheel experiment never built.** Deliberately deferred behind
measured trigger (§50).

---

## PART 3 — CODE QUALITY ISSUES FOUND IN SCAN

| File | Issue | Severity |
|---|---|---|
| WebhookDispatcher.java:58,72,86,102 | `System.out.println` debug lines left in prod code | 🔴 Must fix |
| docker-compose.yml:6,26-29 | Hardcoded credentials visible in git | ⚠️ Dev-only, but flag |
| k8s/secrets.yaml:9-11 | "change-me" placeholder secrets checked into git | ⚠️ Template file, ok if documented |
| QuotaStore.java | Rate-limit check does 2 DB queries per submit even when no quota configured | 🟡 Optimize: cache "has quota" boolean |
| PostgresQueue.java:133 | Constrained path reads `subscribed_queues` via subquery per candidate batch — fine now, may need caching at scale | 🟢 |
| BuiltInHandlers.HttpCallbackHandler | HttpClient instance created once but never closed on shutdown | 🟡 Minor leak on restart |
| WorkerLoop.java:485 | `Thread.sleep(ms)` in parkQuietly — acceptable for poll backoff, not a busy-wait | ✅ OK |
| No @Transactional on JobsController.batch | Batch loop calls createReturningFreshness per item without wrapping tx — partial batch possible on failure | 🟡 Acceptable: each job is independently durable |

---

## PART 4 — INFRASTRUCTURE GAPS (non-code)

| Gap | Impact |
|---|---|
| No CI/CD pipeline (GitHub Actions) | Tests only run locally |
| No staging environment | Untested deploys |
| No backup verification | Backups exist? Untested restores |
| No load balancer config for API replicas | Sticky sessions unclear |
| No service mesh / mTLS for internal traffic | Plain HTTP between pods |
| No secret rotation automation | Keys rotated manually |
| No log aggregation (ELK/Loki) | Logs scattered across pods |
| No distributed tracing collector (Jaeger/Zipkin) | Spans go nowhere |

---

## PART 5 — SUMMARY SCORECARD

| Category | Score | Notes |
|---|---|---|
| Core scheduling correctness | **9/10** | Thoroughly tested, fencing + leases solid |
| Workflow engine | **7/10** | Working DAGs, missing signals/child workflows |
| Multi-tenancy | **7/10** | Isolation + quotas done; RLS + encryption pending |
| Security | **5/10** | AuthN done; no TLS, no CORS, no security headers |
| Observability | **7/10** | Metrics+alerts done; tracing missing |
| Operations | **6/10** | K8s+UI+CLI done; no CI/CD, no staging |
| Scalability readiness | **6/10** | Architecture supports it; autovacuum/pool tuning needed |
| Code quality | **8/10** | Clean, tested; 4 System.out.debug lines remain |
| Documentation | **9/10** | Comprehensive ADR set + audit docs |
| Fault tolerance | **8/10** | Tested edge cases; fail-closed during DB outage is deliberate |

**OVERALL: 7.2/10 — production-ready for internal/staging use after fixing
Critical items C1–C5. Not ready for external/regulated traffic until High items
H1–H10 are also addressed.**
