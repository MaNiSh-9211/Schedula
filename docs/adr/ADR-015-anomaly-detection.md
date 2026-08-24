# ADR-015: Statistical Anomaly Detection

## Context
Schedulers detect timeouts but miss "slow but successful" executions. A REPORT that normally takes 30s suddenly taking 300s is a problem nobody notices.

## Decision
Apply Welford's online algorithm to learn per-job-type duration distributions using O(1) memory. Flag executions > 3 sigma from baseline. No training set, no ML model, pure streaming statistics.

## Why novel
Statistical process control has never been applied to job execution monitoring.

## Consequences
- Needs ~10 samples before anomaly detection activates
- Welford is numerically stable but loses precision over very large sample counts
- Anomalous samples still update the baseline (they might be the new normal)
