#!/usr/bin/env bash
# Schedula operator CLI (thin wrapper over the REST API).
# Usage:
#   SCHEDULA_URL=http://localhost:8080 SCHEDULA_KEY=sk_... ./schedula.sh <command> [args]
# Commands:
#   submit <type> <payload-json> [scheduledForISO]   submit a job
#   status <jobId>                                   job + current state
#   executions <jobId>                               attempt history
#   cancel <jobId>                                   cancel queued/running job
#   retry <jobId>                                    re-submit from terminal state
#   list [status]                                    recent jobs (optionally filtered)
#   dlq                                              list dead letters
#   dlq-retry <messageId>                            replay a dead letter
#   workers / queues / schedulers                    fleet views
#   metrics                                          prometheus scrape
set -euo pipefail

BASE="${SCHEDULA_URL:-http://localhost:8080}"
KEY="${SCHEDULA_KEY:-}"
ADMIN="${SCHEDULA_ADMIN_KEY:-}"

auth=(-H "X-API-Key: $KEY")
[[ -n "$ADMIN" ]] && auth+=(-H "X-Admin-Key: $ADMIN")

cmd="${1:-help}"; shift || true

case "$cmd" in
  submit)      curl -sf -X POST "$BASE/v1/jobs" -H 'Content-Type: application/json' \
                 "${auth[@]}" -d "{\"jobType\":\"$1\",\"payload\":$2${3:+,\"scheduledFor\":\"$3\"}}"; echo ;;
  status)      curl -sf "$BASE/v1/jobs/$1" "${auth[@]}"; echo ;;
  executions)  curl -sf "$BASE/v1/jobs/$1/executions" "${auth[@]}"; echo ;;
  cancel)      curl -sf -X POST "$BASE/v1/jobs/$1/cancel" "${auth[@]}"; echo ;;
  retry)       curl -sf -X POST "$BASE/v1/jobs/$1/retry" "${auth[@]}"; echo ;;
  pause)       curl -sf -X POST "$BASE/v1/jobs/$1/pause" "${auth[@]}"; echo ;;
  resume)      curl -sf -X POST "$BASE/v1/jobs/$1/resume" "${auth[@]}"; echo ;;
  list)        curl -sf "$BASE/v1/jobs?limit=20${1:+&status=$1}" "${auth[@]}"; echo ;;
  dlq)         curl -sf "$BASE/v1/dlq" "${auth[@]}"; echo ;;
  dlq-retry)   curl -sf -X POST "$BASE/v1/dlq/$1/retry" "${auth[@]}"; echo ;;
  workers)     curl -sf "$BASE/v1/workers" "${auth[@]}"; echo ;;
  queues)      curl -sf "$BASE/v1/queues" "${auth[@]}"; echo ;;
  schedulers)  curl -sf "$BASE/v1/schedulers" "${auth[@]}"; echo ;;
  metrics)     curl -sf "$BASE/actuator/prometheus" | grep -E "schedula_|queue_depth" ;;
  *)           grep '^#' "$0" | sed 's/^# \{0,1\}//' ;;
esac
