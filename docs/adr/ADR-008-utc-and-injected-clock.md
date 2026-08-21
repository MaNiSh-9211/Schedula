# ADR-008: UTC storage everywhere + injected Clock abstraction

Status: Accepted (Phase 0)

## Context

§12/§78: time is a distributed-systems problem. Naïve systems store local times, compare
app-server clocks, and compute durations from wall clocks — then break on DST, clock jumps,
and node skew.

## Problem

How should the platform represent, store, and measure time so scheduling is correct under
DST, skew, and jumps?

## Options considered

1. **UTC `timestamptz` storage + DB-authoritative instants + injected monotonic Clock (chosen).**
2. **Store local wall times with timezone column (civil-time-first).**
3. **Epoch-millis longs everywhere.**
4. **App-server clocks as authority.**

## Decision

- All persistence in UTC (`timestamptz`). Timezone exists only at schedule *definition*
  edges: a cron defined in `Europe/Berlin` is evaluated to UTC instants via tzdb.
- **Instant decisions (lease expiry, claim visibility) are computed by PostgreSQL**
  (`now()` inside statements) — nodes cannot lie about their own clocks to gain/lose leases.
- **Duration measurements** (lag, timeouts within a process) use an injected
  `MonotonicClock`; tests substitute a controllable fake — this is also the fault-injection
  seam for time-based chaos (TESTING.md).
- DST/cron evaluation via java.time + tzdb with property-based tests across transition days.

## Why this is best

- One unambiguous canonical representation; civil-time quirks stay where they belong
  (human-facing schedule definitions), not in storage or comparisons.
- DB-authoritative instants close the "skewed node manipulates leases" hole cheaply.
- Monotonic durations are immune to NTP steps; wall-clock duration math is a documented
  anti-pattern (§78).

## Why alternatives were rejected

- **Local-time storage:** every comparison needs tz context; historical tz rules make stored
  local times ambiguous during DST overlaps; bugs guaranteed.
- **Epoch longs:** loses timezone intent for schedules, invites wall-clock arithmetic,
  unreadable in SQL during incidents.
- **App clocks as authority:** one skewed node shortens competitors' leases or extends its
  own — a split-brain vector we refuse to ship.

## Trade-offs accepted

- Dependence on DB clock sanity (NTP on DB hosts = operational requirement, documented).
- tzdb updates can shift future civil-time occurrences → schedules re-evaluate occurrences
  lazily (next_fire_at recomputed near fire time) rather than materializing far-future runs,
  which both bounds recompute cost and self-corrects after tzdb upgrades.

## Consequences

Every component takes a Clock in its constructor (no static `Instant.now()` in logic code —
enforced by review/archunit rule); time-jump chaos scenarios are trivially scriptable;
INCIDENT-006/007 drills test forward/backward jumps end-to-end.

## Revisit triggers

None foreseen; this is foundational hygiene, not a tunable bet.
