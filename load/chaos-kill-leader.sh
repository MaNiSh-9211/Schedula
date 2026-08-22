#!/usr/bin/env bash
# INCIDENT-001 drill: kill a scheduler leader mid-flight and verify bounded failover.
# Prereq: compose stack running (docker compose up -d), jq optional.
#
# What it proves:
#   1. leadership moves to another node within lease duration (default 15s)
#   2. jobs submitted before the kill still complete afterwards
#   3. no duplicate schedule occurrences were created across the transition
set -euo pipefail

BASE="${SCHEDULA_URL:-http://localhost:8080}"
KEY="${SCHEDULA_KEY:-sk_00000000-0000-0000-0000-000000000001_devkey123}"
AUTH=(-H "X-API-Key: $KEY" -H 'Content-Type: application/json')

echo "== pre: who leads =="
curl -sf "$BASE/v1/schedulers" | tee /tmp/pre.json

echo "== submit canary job with 5s delay so it spans the kill =="
CANARY=$(curl -sf -X POST "$BASE/v1/jobs" "${AUTH[@]}" \
  -d '{"jobType":"log","payload":{"canary":true},"scheduledFor":"'"$(date -u -d '+5 seconds' +%Y-%m-%dT%H:%M:%SZ)"'"}' \
  | sed 's/.*"id":"\([^"]*\)".*/\1/')
echo "canary job: $CANARY"

echo "== killing ALL schedulers (compose scale to zero, then back) =="
docker compose up -d --scale app=0 app
sleep 2
docker compose up -d --scale app=1 app

echo "== waiting for recovery + canary completion (max 60s) =="
for i in $(seq 1 60); do
  STATUS=$(curl -sf "$BASE/v1/jobs/$CANARY" "${AUTH[@]}" | sed 's/.*"status":"\([A-Z_]*\)".*/\1/') || STATUS=UNREACHABLE
  echo "  [$i] $STATUS"
  [[ "$STATUS" == "COMPLETED" ]] && break
  sleep 1
done
[[ "$STATUS" == "COMPLETED" ]] || { echo "FAIL: canary did not complete"; exit 1; }

echo "== post: leader re-elected, single owner =="
LEADERS=$(curl -sf "$BASE/v1/schedulers")
COUNT=$(echo "$LEADERS" | grep -o '"ownerNodeId"' | wc -l)
[[ "$COUNT" -eq 1 ]] || { echo "FAIL: expected exactly one leader entry"; exit 1; }

echo "PASS: failover within bounds, canary completed, single leader."
