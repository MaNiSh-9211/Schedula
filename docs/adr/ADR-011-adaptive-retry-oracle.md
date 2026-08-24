# ADR-011: Adaptive Retry Oracle

## Context
Every scheduler uses developer-guessed retry policies. THROTTLED errors might need 4s while CONNECTION_REFUSED needs 30s, but the policy is one-size-fits-all.

## Decision
Record every retry outcome per (job_type, error_class, attempt, delay_bucket). After 5+ samples in a bucket, override configured backoff with the empirically optimal delay.

## Why novel
No scheduler learns from retry outcomes. They all use static configuration.

## Consequences
- Handlers must still declare max_attempts (Oracle tunes delay, not count)
- Cold-start falls back to configured policy
- Table grows but is bounded by type x error_class x attempt x 11 buckets
