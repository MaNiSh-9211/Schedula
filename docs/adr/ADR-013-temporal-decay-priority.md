# ADR-013: Temporal Decay Priority

## Context
Priority-based scheduling causes starvation: low-priority jobs wait forever behind high-priority floods.

## Decision
Modify claim SQL ORDER BY to compute effective priority as `priority + age_seconds / decay_half_life`. Old jobs self-promote without human intervention. Zero schema changes, zero configuration.

## Why novel
Airflow/Celery/Temporal all use static priorities. Self-healing anti-starvation via pure math is genuinely new.

## Consequences
- A P0 job eventually gets outranked by an old P3 — this is by design
- Half-life of 60s means priority effectively doubles every minute
- No explicit "boost" API needed
