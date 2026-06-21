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

# HAProxy Runtime API socket - match GID so pibase can send commands
HAPROXY_SOCK="/opt/pibase/haproxy/run/haproxy.sock"
if [ -S "$HAPROXY_SOCK" ]; then
  HA_GID=$(stat -c '%g' "$HAPROXY_SOCK")
  addgroup -g "$HA_GID" haproxy 2>/dev/null || true
  addgroup pibase haproxy 2>/dev/null || true
fi

exec su-exec pibase java $JAVA_OPTS -jar app.jar