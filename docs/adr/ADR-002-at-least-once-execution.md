# ADR-002: At-least-once execution + idempotent effects

Status: Accepted (Phase 0)

## Context

Handlers perform external side effects (HTTP calls, emails, payments). Processes crash;
acknowledgements get lost; leases expire while old owners still run.

## Problem

What execution guarantee can we honestly make? (§4 forbids casual "exactly-once".)

## Options considered

1. **At-most-once** — fire and forget; no redelivery.
2. **Best-effort** — try once or twice, lose things quietly under load.
3. **Exactly-once execution** — the marketing favorite.
4. **At-least-once delivery + idempotent effects (chosen)**.

## Decision

Default contract: **at-least-once delivery**, duplicates bounded by lease+sweep window,
with framework support making effects idempotent (idempotency keys propagated to handlers,
optional EffectLedger checkpoint helper).

## Why this is best

- Exactly-once *execution* is achievable only for effects inside our transaction boundary
  (state updates). For external effects it requires the external system to deduplicate —
  at which point the honest statement is "at-least-once + your cooperation", i.e., option 4
  with better PR. We say the true thing (§60, §104).
- At-most-once violates the primary goal (no lost work): a crash after effect-before-ACK
  would silently drop the job's completion record.
- Best-effort is at-most-once with extra steps and worse honesty.

## Why alternatives were rejected

- **At-most-once:** loses completed-work records on ACK loss — unacceptable for a scheduler
  whose entire value is reliability.
- **Exactly-once claim:** unfalsifiable promise for opaque side effects; §104 explicitly
  forbids claiming it without proven scope.

## Trade-offs accepted

- Handlers carry an idempotency obligation; enforced via contract tests and documented
  handler model, not wishful thinking.
- Duplicate executions occur during recovery windows; monitoring counts them
  (`duplicate_execution_total`) so the bound stays visible, not theoretical.

## Consequences

Submission APIs are durable-before-ack; retry engine redelivers aggressively after lease
expiry; EffectLedger gives handlers a transactional "did I already do this" check where the
effect's target can't deduplicate itself.

## Revisit triggers

None — this is physics, not preference. Documentation will keep stating it precisely.
