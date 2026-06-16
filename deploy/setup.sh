#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "— PiBase MVP Setup ——————————————————"

# 1. Create .env from template if missing
if [ ! -f .env ]; then
  cp .env.example .env
  echo "✓ Created .env from .env.example"
  echo ""
  echo "⚠ IMPORTANT: Edit .env now and set your values:"
  echo "  nano $SCRIPT_DIR/.env"
  echo ""
  echo "  Required:"
  echo "  - DOCKERHUB_USERNAME"
  echo "  - JWT_SECRET (generate: python3 -c \"import secrets; print(secrets.token_urlsafe(48))\")"
  echo "  - PUBLIC_HOST"
  echo ""
  echo "Run this script again after editing .env."
  exit 0
fi

# 2. Source .env and validate
set -a
source .env
set +a

if [ "${DOCKERHUB_USERNAME:-}" = "your-dockerhub-username" ] || [ -z "${DOCKERHUB_USERNAME:-}" ]; then
  echo "× DOCKERHUB_USERNAME is not set in .env – edit it first."
  exit 1
fi

if [ "${JWT_SECRET:-}" = "CHANGE_ME_generate_with_command_above" ] || [ -z "${JWT_SECRET:-}" ]; then
  echo "× JWT_SECRET is not set in .env – edit it first."
  exit 1
fi

# 3. Ensure the external 'proxy' network exists (created by Traefik stack)
if ! docker network inspect proxy >/dev/null 2>&1; then
  echo "⚠ Docker network 'proxy' not found."
  echo "  Traefik must be running first. Start your Traefik stack, then re-run this script."
  exit 1
fi
echo "✓ proxy network exists"

# 4. Pull latest image
echo "Pulling ${DOCKERHUB_USERNAME}/pibase-api:latest ..."
docker pull "${DOCKERHUB_USERNAME}/pibase-api:latest"
echo "✓ Image pulled"

# 5. Start services
echo "Starting PiBase API ..."
docker compose up -d
echo ""
echo "✓ PiBase is running!"
echo "  Health: curl http://localhost/api/health  (via Traefik)"
echo "  Direct: docker exec pibase-api wget -qO- http://localhost:8080/actuator/health"
echo "  Logs:   docker compose logs -f"