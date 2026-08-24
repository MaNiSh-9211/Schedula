# Getting Started

## Prerequisites
- Docker Desktop (for PostgreSQL)
- Java 21+ and Maven 3.9+
- curl or any HTTP client

## 1. Start the database

```bash
docker compose up -d postgres
```

## 2. Start Schedula

```bash
mvn -pl app spring-boot:run
```

On first boot, a default tenant API key is generated and printed to the log:

```
WARN: bootstrapped default tenant API key (change or disable): sk_00000000-..._abc123
```

Copy this key. Or set a fixed one via env: `SCHEDULA_DEFAULT_API_KEY=sk_..._mykey`

## 3. Open the Admin UI

Navigate to http://localhost:8080 and paste your API key.

## 4. Submit jobs via API

```bash
export KEY="sk_00000000-0000-0000-0000-000000000001_devkey123"

# Immediate job
curl -X POST localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"jobType":"log","payload":{"msg":"hello"}}'

# Delayed job (runs at specific time)
curl -X POST localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"jobType":"log","payload":{},"scheduledFor":"2026-12-25T09:00:00Z"}'

# With retries, timeout, capabilities
curl -X POST localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{
    "jobType":"http",
    "payload":{"url":"https://api.example.com/process"},
    "maxAttempts":5,
    "timeoutMs":30000,
    "retryPolicy":{"backoff":"EXPONENTIAL_JITTERED","initialDelayMs":2000},
    "requiredCapabilities":["python"],
    "webhookUrl":"https://your-service.com/notify"
  }'

# Batch submission
curl -X POST localhost:8080/v1/jobs/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"jobs":[
    {"jobType":"log","payload":{"n":1}},
    {"jobType":"log","payload":{"n":2}},
    {"jobType":"log","payload":{"n":3}}
  ]}'
```

## 5. Create recurring schedules

```bash
# Cron with timezone
curl -X POST localhost:8080/v1/schedules \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{
    "name":"daily-report",
    "jobType":"report",
    "cronExpr":"0 0 9 * * *",
    "timezone":"Europe/Berlin",
    "missedPolicy":"COALESCE"
  }'

# Fixed interval
curl -X POST localhost:8080/v1/schedules \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"name":"health-check","jobType":"http","intervalMs":60000}'
```

## 6. Define workflows (DAGs)

```bash
# Register a workflow definition
curl -X POST localhost:8080/v1/workflows \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{
    "name":"order-processing",
    "definition":{
      "tasks":[
        {"key":"validate","jobType":"log","payload":{"step":"validate"}},
        {"key":"charge","jobType":"log","payload":{"step":"charge"},"dependsOn":["validate"],
          "undo":{"jobType":"log","payload":{"refund":true}}},
        {"key":"ship","jobType":"log","payload":{"step":"ship"},"dependsOn":["charge"],
          "undo":{"jobType":"log","payload":{"unship":true}}},
        {"key":"notify","jobType":"echo","payload":{"notified":true},"dependsOn":["charge","ship"]}
      ]
    }
  }'

# Start an execution
curl -X POST localhost:8080/v1/workflows/order-processing/executions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"input":{"orderId":"12345"}}'

# Check progress
curl localhost:8080/v1/workflows/executions/<id> \
  -H "X-API-Key: $KEY"
```

## 7. Monitor

```bash
# Admin UI
open http://localhost:8080

# Prometheus metrics
curl localhost:8080/actuator/prometheus | grep schedula_

# Fleet status
./schedula.sh workers
./schedula.sh schedulers
./schedula.sh queues

# Dead letters
./schedula.sh dlq
./schedula.sh dlq-retry <messageId>
```

## Built-in Handlers

| Type | Behavior | Result |
|------|----------|--------|
| `log` | Logs payload to console | none |
| `sleep` | Sleeps for `payload.ms` milliseconds (cooperative cancellation) | none |
| `echo` | Returns payload as result | payload JSON |
| `http` | POSTs to `payload.url` with idempotency headers | callback status |

Register custom handlers by implementing `JobHandler` and adding to `HandlerConfig`.

