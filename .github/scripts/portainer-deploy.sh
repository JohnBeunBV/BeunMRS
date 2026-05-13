#!/usr/bin/env bash
# .github/scripts/portainer-deploy.sh
#
# Updates an existing Portainer stack using the Portainer HTTP API.
# Reads the current stack file from the repo and pushes it along with
# environment variable overrides.
#
# Required env vars (set in the workflow):
#   PORTAINER_URL       — e.g. https://portainer.example.com
#   PORTAINER_API_KEY   — Portainer API key (Settings → Users → API keys)
#   STACK_ID            — Portainer stack numeric ID
#   IMAGE_TAG           — Docker image tag to deploy
#   IMAGE_OWNER         — GitHub org/user (e.g. my-org)
#   *_PASSWORD vars     — secrets for each service

set -euo pipefail

PORTAINER_URL="${PORTAINER_URL:?}"
PORTAINER_API_KEY="${PORTAINER_API_KEY:?}"
STACK_ID="${STACK_ID:?}"
IMAGE_TAG="${IMAGE_TAG:?}"

echo "──────────────────────────────────────"
echo "Deploying stack #${STACK_ID} @ ${PORTAINER_URL}"
echo "Image tag: ${IMAGE_TAG}"
echo "──────────────────────────────────────"

# Read the compose file content
STACK_FILE_CONTENT=$(cat docker-compose.yml)

# Build the env list that Portainer will inject into the stack
ENV_VARS=$(cat <<EOF
[
  {"name": "IMAGE_TAG",                  "value": "${IMAGE_TAG}"},
  {"name": "IMAGE_OWNER",                "value": "${IMAGE_OWNER}"},
  {"name": "OPENMRS_DB_PASSWORD",        "value": "${OPENMRS_DB_PASSWORD}"},
  {"name": "OPENMRS_DB_ROOT_PASSWORD",   "value": "${OPENMRS_DB_ROOT_PASSWORD}"},
  {"name": "OPENMRS_API_PASSWORD",       "value": "${OPENMRS_API_PASSWORD}"},
  {"name": "RABBITMQ_PASSWORD",          "value": "${RABBITMQ_PASSWORD}"},
  {"name": "NOTIFICATION_DB_PASSWORD",   "value": "${NOTIFICATION_DB_PASSWORD}"}
]
EOF
)

# Call Portainer stack update API
HTTP_STATUS=$(curl -s -o /tmp/portainer_response.json -w "%{http_code}" \
  -X PUT \
  "${PORTAINER_URL}/api/stacks/${STACK_ID}" \
  -H "X-API-Key: ${PORTAINER_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{
    \"stackFileContent\": $(echo "$STACK_FILE_CONTENT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
    \"env\": ${ENV_VARS},
    \"prune\": false,
    \"pullImage\": true
  }")

echo "Portainer API response (HTTP ${HTTP_STATUS}):"
cat /tmp/portainer_response.json | python3 -m json.tool 2>/dev/null || cat /tmp/portainer_response.json

if [[ "$HTTP_STATUS" -ge 200 && "$HTTP_STATUS" -lt 300 ]]; then
  echo "✅ Stack update submitted successfully."
else
  echo "❌ Stack update failed with HTTP ${HTTP_STATUS}."
  exit 1
fi
