# ADR-012: Cascade Failure Firewall

## Context
When a downstream dependency dies, all schedulers keep retrying queued jobs into the void. This wastes worker threads, pollutes the DLQ, and burns retry attempts that could be used for healthy work.

## Decision
Extract hostname from error messages. Track failure counts per host. When threshold exceeded, auto-quarantine all queued jobs targeting that dependency. Sweeper TCP-checks for recovery and auto-releases.

## Why novel
Airflow has sensors (manual), Celery has circuit breakers (per-task code). Nobody AUTO-DETECTS dependency death and quarantines affected work automatically.

## Consequences
- False positives possible if error extraction matches wrong host
- Quarantined jobs are CANCELLED not deleted; they can be manually retried
- TCP health check is basic; HTTP health endpoints would be better
