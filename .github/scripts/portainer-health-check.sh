#!/usr/bin/env bash
# .github/scripts/portainer-health-check.sh
#
# After a deploy, polls Portainer until all containers in the stack
# are in a running/healthy state, or times out.
#
# Required env vars:
#   PORTAINER_URL
#   PORTAINER_API_KEY
#   STACK_ID

set -euo pipefail

PORTAINER_URL="${PORTAINER_URL:?}"
PORTAINER_API_KEY="${PORTAINER_API_KEY:?}"
STACK_ID="${STACK_ID:?}"

MAX_WAIT=300   # seconds
POLL_INTERVAL=10
elapsed=0

echo "Waiting for stack #${STACK_ID} containers to become healthy..."

while true; do
  # Fetch containers for this stack
  CONTAINERS=$(curl -sf \
    "${PORTAINER_URL}/api/stacks/${STACK_ID}/containers" \
    -H "X-API-Key: ${PORTAINER_API_KEY}" || echo "[]")

  TOTAL=$(echo "$CONTAINERS" | python3 -c "import json,sys; c=json.load(sys.stdin); print(len(c))" 2>/dev/null || echo 0)
  RUNNING=$(echo "$CONTAINERS" | python3 -c "
import json, sys
c = json.load(sys.stdin)
print(sum(1 for x in c if x.get('State') == 'running'))
" 2>/dev/null || echo 0)
  UNHEALTHY=$(echo "$CONTAINERS" | python3 -c "
import json, sys
c = json.load(sys.stdin)
print(sum(1 for x in c if x.get('Status','').startswith('unhealthy')))
" 2>/dev/null || echo 0)

  echo "[${elapsed}s] Containers: ${RUNNING}/${TOTAL} running, ${UNHEALTHY} unhealthy"

  if [[ "$UNHEALTHY" -gt 0 ]]; then
    echo "❌ One or more containers are unhealthy."
    exit 1
  fi

  if [[ "$TOTAL" -gt 0 && "$RUNNING" -eq "$TOTAL" ]]; then
    echo "✅ All ${TOTAL} containers are running."
    exit 0
  fi

  if [[ "$elapsed" -ge "$MAX_WAIT" ]]; then
    echo "❌ Timed out after ${MAX_WAIT}s waiting for containers."
    exit 1
  fi

  sleep "$POLL_INTERVAL"
  elapsed=$((elapsed + POLL_INTERVAL))
done
