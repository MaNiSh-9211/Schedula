# ADR-009: Missed-execution policy = coalesce to one run (configurable per schedule)

Status: Accepted (Phase 0)

## Context

§11: schedulers must define behavior for missed schedules (leader down 2h, DB failover,
clock jumps, backlog). Policies in the space: skip, execute-once, execute-all-missed,
coalesce. §11 forbids choosing arbitrarily.

## Problem

A recurring schedule that missed N occurrences during downtime — run what, how many times,
and with what user-facing semantics?

## Options considered

1. **Skip to next future occurrence** — downtime silently drops runs.
2. **Execute all missed occurrences** — catch-up storm: 2h outage on a 1-min schedule = 120
   simultaneous runs; retry storms' big sibling.
3. **Execute once (latest semantics)** — one run after recovery.
4. **Coalesce to one run + explicit "missed_count" metadata (chosen default).**

## Decision

Default policy **COALESCE**: after a gap, exactly one occurrence fires (at recovery time),
carrying `missed_count` and the intended fire window in its context so handlers/observers
know it represents a coalesced period. Per-schedule override to SKIP or RUN_ALL is a
first-class field (`job_schedules.missed_policy`), because legitimate cases exist both ways
(polling-style jobs want SKIP; billing backfill wants RUN_ALL with bounded batch).

## Why this is best

- Matches the dominant real-world intent of recurring jobs ("this should have run roughly
  hourly; make sure it ran") without storms.
- Never silent (SKIP's sin): the run happens and says what it represents.
- Never explosive (RUN_ALL's sin) by default; bounded catch-up remains available explicitly,
  with batch caps when chosen.

## Why alternatives were rejected as defaults

- **Skip:** violates "no lost work" spirit — downtime converts into invisible gaps users
  discover weeks later.
- **Run-all:** turns availability incidents into load incidents (thundering herd against
  already-recovering systems); §21's anti-storm principle applied to schedules.
- **Plain execute-once:** right behavior, weaker contract — without missed_count/window
  metadata, handlers can't distinguish "normal tick" from "catch-up tick".

## Trade-offs accepted

- Coalesced runs may see stale-ish inputs for long gaps (handler's problem to inspect
  missed_count — documented handler contract).
- Slight complexity in occurrence bookkeeping (window tracking) — cheap vs the ambiguity it
  removes.

## Consequences

Occurrence creation transactionally advances `next_fire_at` past the gap (crash-safe);
`missed_occurrences_total{policy}` metric exposes gap frequency; DST/clock-jump chaos tests
assert policy behavior precisely (CHAOS-006).

## Revisit triggers

Real workload evidence that a different default dominates → flip default via config survey
at Phase 5+; mechanism unchanged.
