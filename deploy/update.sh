#!/usr/bin/env bash
# Pulls the latest published image and recreates the container only when the image
# changed. Meant to run from cron / a systemd timer on the server (see DEPLOY.md):
#   */5 * * * * /home/<user>/boxvault/update.sh >> /home/<user>/boxvault/update.log 2>&1
set -euo pipefail
cd "$(dirname "$0")"

before=$(docker compose images -q boxvault 2>/dev/null || true)
docker compose pull --quiet boxvault
after=$(docker compose images -q boxvault 2>/dev/null || true)

if [ "$before" != "$after" ]; then
  echo "$(date -Is) new image $after (was ${before:-none}); restarting boxvault"
  docker compose up -d boxvault
  docker image prune -f >/dev/null
else
  # nothing new; make sure the service is up anyway
  docker compose up -d boxvault >/dev/null
fi
