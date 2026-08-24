<div align="center">

# Schedula

**A distributed job scheduler built to survive chaos.**

[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.java.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)](https://spring.io/projects/spring-boot)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)

[Quick Start](#quick-start) | [Features](#features) | [Inventions](#inventions) | [Architecture](docs/ARCHITECTURE.md) | [API](docs/API.md) | [ADRs](docs/adr)

</div>

---

## Quick Start

```bash
git clone https://github.com/MaNiSh-9211/Schedula.git
cd Schedula

# Full Docker (zero setup, demo data auto-seeded)
docker compose up --build
# open http://localhost:8080
# paste key: sk_00000000-0000-0000-0000-000000000001_devkey123

# Or local JVM + Docker Postgres
docker compose up -d postgres
mvn -pl app spring-boot:run -Dspring-boot.run.profiles=demo
```

Submit your first job:

```bash
curl -X POST localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk_00000000-0000-0000-0000-000000000001_devkey123" \
  -d '{"jobType":"echo","payload":{"msg":"hello world"}}'
```

---

## Features

| Category | What it does |
|----------|-------------|
| **Scheduling** | Immediate, delayed, cron+DST, fixed-interval, backfill (RUN_ALL), temporal decay priority |
| **Execution** | Named queues, worker subscriptions, capability matching, resource-aware (CPU/MEM), virtual threads |
| **Reliability** | At-least-once delivery, adaptive retry oracle, poison pill detection, DLQ, cascade failure firewall |
| **Workflows** | DAGs, parallel branches, signals, child workflows, durable timers, compensation |
| **Coordination** | Leader election (PG lease + fencing tokens), split-brain protection |
| **Multi-tenancy** | API keys (SHA-256 hashed), per-tenant quotas, rate limits, fair dispatch, audit trail |
| **Observability** | 30+ Prometheus metrics, anomaly detection (Welford), pressure predictor, health scoring |
| **Operations** | Admin UI, CLI, k8s manifests, Docker Compose, chaos drills |

## Inventions

| Innovation | What it does | Why nobody else has it |
|------------|-------------|----------------------|
| Adaptive Retry Oracle | Learns optimal retry delays from actual outcomes | Everyone uses static policies |
| Temporal Decay Priority | Old jobs self-promote, starvation self-heals | Zero config anti-starvation |
| Cascade Failure Firewall | Auto-quarantines dead dependencies, auto-releases on recovery | Others burn retries into dead services |
| Statistical Anomaly Detection | Welford online algorithm detects >3-sigma deviations in real-time | Catches slow-but-not-failed jobs |
| Predictive Health Scoring | Credit score for job executions before dispatch | All schedulers are reactive |
| Queue Pressure Predictor | Linear regression on depth samples predicts backlog 5 min ahead | Leading indicator vs trailing alarm |
| Poison Pill Detection | Cross-worker crash patterns flagged automatically | One alert instead of three |

## Documentation

| Doc | Contents |
|-----|----------|
| [Getting Started](docs/GETTING-STARTED.md) | Step-by-step from zero to production |
| [Architecture](docs/ARCHITECTURE.md) | Mermaid diagrams, components, design decisions |
| [API Reference](docs/API.md) | All 33 endpoints with examples |
| [Data Model](docs/DATA-MODEL.md) | All tables, indexes, lifecycle |
| [State Machines](docs/STATE-MACHINES.md) | Job/workflow/worker/scheduler transitions |
| [Fault Tolerance Audit](docs/FAULT-TOLERANCE-AUDIT.md) | Honest edge-case assessment vs Temporal/Airflow |
| [Production Readiness](docs/PRODUCTION-READINESS.md) | Full gap analysis with severity ranking |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Decision trees for common issues |
| [Operations Runbook](docs/OPERATIONS.md) | Signals, alerts, failure handling |
| [Testing Strategy](docs/TESTING.md) | Chaos scenarios, fault injection |
| [ADR Set](docs/adr/) | 15 architecture decision records |

## Testing

```bash
mvn verify    # requires Docker for Testcontainers
# 84 tests across 10 IT suites
```

