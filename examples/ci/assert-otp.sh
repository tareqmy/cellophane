#!/usr/bin/env bash
# Sends a message through Cellophane and asserts it arrived, the way an integration test would.
# Usage: CELLOPHANE=http://localhost:8025 ./assert-otp.sh
set -euo pipefail
CELLOPHANE="${CELLOPHANE:-http://localhost:8025}"
TO="8801711111111"

curl -sf -X DELETE "$CELLOPHANE/api/v1/messages" >/dev/null

# Your application would send this over SMPP; here we use the HTTP shortcut.
curl -sf -X POST "$CELLOPHANE/api/v1/send" -H 'content-type: application/json' \
  -d "{\"from\":\"MyApp\",\"to\":\"$TO\",\"text\":\"Your OTP is 482913\"}" >/dev/null

# Assert: exactly one message to that number containing "OTP" in the last 30 seconds.
result=$(curl -sf "$CELLOPHANE/api/v1/messages?to=$TO&text=OTP&since=30s")
total=$(printf '%s' "$result" | python3 -c 'import sys,json; print(json.load(sys.stdin)["total"])')
if [ "$total" != "1" ]; then
  echo "expected 1 message, got $total: $result" >&2
  exit 1
fi

# Wait for the operator's delivery receipt (default rules: DELIVRD after 500ms).
id=$(printf '%s' "$result" | python3 -c 'import sys,json; print(json.load(sys.stdin)["messages"][0]["id"])')
for _ in $(seq 1 20); do
  status=$(curl -sf "$CELLOPHANE/api/v1/messages/$id" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])')
  [ "$status" = "DELIVRD" ] && break
  sleep 0.25
done
[ "$status" = "DELIVRD" ] || { echo "expected DELIVRD, got $status" >&2; exit 1; }

echo "ok: message $id to $TO delivered"
