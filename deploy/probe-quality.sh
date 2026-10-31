#!/usr/bin/env bash
# Re-probe feed quality off the box and hand the result to the box, which owns
# api/quality-cache.ts. It runs elsewhere (nightly on GitHub Actions, or a Mac) because
# the provider refuses streams from the box's IP (HTTP 511). It holds the account's one
# connection slot, so the probe skips/stops when someone is watching. From the repo root:
#   SERVER=root@<server-ip> ./deploy/probe-quality.sh [minutes, default 30]
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
CACHE=/opt/iptv-preview/api/quality-cache.ts
before=$(mktemp) seed=$(mktemp)
# The probe works on the box's copy; the repo's seed copy is put back when it's done.
cp api/quality-cache.ts "$seed"
trap 'mv "$seed" api/quality-cache.ts; rm -f "$before"' EXIT

rsync -a "$SERVER:$CACHE" "$before"
cp "$before" api/quality-cache.ts
bun scripts/probe-quality.ts 8 refresh "mins=${1:-30}"
if cmp -s "$before" api/quality-cache.ts; then
  echo "No changed readings; the box keeps its cache."
else
  rsync -a api/quality-cache.ts "$SERVER:$CACHE"
  ssh "$SERVER" "chown iptv:iptv $CACHE && systemctl restart iptv-preview"
  echo "Pushed the new readings to the box and restarted the server."
fi
