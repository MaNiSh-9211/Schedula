# SECURITY

Status: Phase 0 proposal. Covers requirements §53, §54, §84, §89, §90. Full implementation
Phase 8; foundations (no-arbitrary-code, audit hooks, secret hygiene) apply from Phase 1.

---

## 1. Authentication & authorization

| Concern | Decision |
| --- | --- |
| AuthN (services/clients) | Static API keys per tenant initially (hashed at rest, prefix-visible only); JWT/OIDC swap-in behind auth interface Phase 8 |
| AuthZ model | RBAC roles: `viewer` (read), `operator` (submit/cancel/retry), `admin` (quotas, tenants, DLQ delete) |
| Tenant isolation | Hard boundary: principal ⇒ tenant scope; all queries tenant-scoped (see MULTI-TENANCY.md); admin cross-tenant access is separate audited role |
| Internal traffic | Worker/scheduler authenticate to API with workload identity (mTLS or bound token) when split across network; within compose, shared secret acceptable and documented as dev-only |

Security boundaries treated seriously (§84): API, scheduler, worker, DB, tenant. Workers do
NOT receive unrestricted data access: a worker sees only payloads of jobs it claimed;
capability registry limits which job types a worker may claim (a `python`-capable worker
never receives `PAYMENTS` jobs unless authorized).

## 2. Execution-model security (§89, §90 — the most important security decision)

**The platform never executes arbitrary user code inside its own processes.**

Supported handler models, in rollout order:
1. **Registered typed handlers** (in-process, platform-authored): `EMAIL`, `HTTP_CALLBACK`,
   `REPORT`, etc. Job type must exist in the capability registry or submission fails closed.
2. **HTTP callback tasks:** platform calls a customer-controlled URL with signed payload —
   arbitrary logic lives in *their* service, outside our trust boundary.
3. Containerized tasks (Kubernetes Jobs) — later phase, isolated runtime, documented pod
   failure semantics (§91).

Sandboxed user-code-in-process is explicitly out of scope (documented non-goal; sandboxing
JVM bytecode is not a promise we can keep honestly).

## 3. Secrets & payload handling

- DB credentials, API keys, signing keys via environment/secret store; never in code,
  config files in repo, logs, or traces (§53).
- Payload redaction: structured logging serializes allowlisted fields only; full payloads
  viewable via authenticated detail APIs with audit.
- Optional envelope encryption for payload-at-rest per tenant (Phase 8+; open question #9).
- TLS everywhere data leaves the host; Postgres over private network in k8s; scram-sha-256
  password auth minimum.

## 4. Audit logging (§54)

Immutable append-only `audit_events` for: job created/cancelled/retried, workflow
paused/resumed, worker disabled, tenant quota changed, DLQ bulk operations, config changes,
role grants. Record: actor principal, tenant, action, target, before/after digests, source
ip, timestamp. App DB role has INSERT-only grant on this table — deletion requires direct DBA
action, which is itself out-of-band auditable.

## 5. Threat notes (short list, expanded alongside implementation)

- Replay of submit API → neutralized by idempotency keys (also a correctness feature).
- Noisy-neighbor DoS → quotas + fairness (MULTI-TENANCY.md).
- Stale-component corruption → fencing (COORDINATION.md) — a security control implemented
  for correctness; documented as both.
- SQL injection → parameterized statements only; jOOQ/JdbcTemplate style enforced.
- SSRF from HTTP callback tasks → egress allowlist per tenant (Phase 8 requirement before
  that task type ships to untrusted tenants).
