#!/bin/sh
set -e

# Match container's docker group GID to mounted socket's GID
if [ -S /var/run/docker.sock ]; then
  SOCK_GID=$(stat -c '%g' /var/run/docker.sock)
  if getent group docker >/dev/null 2>&1; then
    delgroup docker 2>/dev/null || true
  fi
  addgroup -g "$SOCK_GID" docker 2>/dev/null || true
  addgroup pibase docker 2>/dev/null || true
fi

exec su-exec pibase java $JAVA_OPTS -jar app.jar