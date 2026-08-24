# ADR-014: Predictive Health Scoring

## Context
All schedulers are reactive: submit, run, fail, alert. None predict whether a job will succeed BEFORE dispatching it.

## Decision
Compute a 0-100 health score before claiming, based on: historical success rate per (type,hour), queue pressure, error velocity, worker pool health. Low scores trigger worker backoff instead of dispatching into a failing environment.

## Why novel
This is a credit score for job executions. No existing system predicts success before execution.

## Consequences
- Requires enough historical data (>5 samples per hour bucket)
- Conservative scoring may add latency during degraded conditions
- Score computation adds 4 DB queries per claim cycle
