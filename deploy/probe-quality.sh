#!/usr/bin/env bash
# Re-probe feed quality from this Mac and hand the result to the box, which owns
# api/quality-cache.ts. It runs here because the provider refuses streams from the
# box's IP (HTTP 511). It holds the account's one connection slot, so run it when
# nobody is watching. From the repo root:
#   SERVER=root@65.108.153.112 ./deploy/probe-quality.sh [minutes, default 30]
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
CACHE=/opt/iptv-preview/api/quality-cache.ts
before=$(mktemp)
trap 'rm -f "$before"' EXIT

rsync -a "$SERVER:$CACHE" "$before"
cp "$before" api/quality-cache.ts
bun scripts/probe-quality.ts 8 refresh "mins=${1:-30}"
if cmp -s "$before" api/quality-cache.ts; then
  echo "No better readings; the box keeps its cache."
else
  rsync -a api/quality-cache.ts "$SERVER:$CACHE"
  ssh "$SERVER" "chown iptv:iptv $CACHE && systemctl restart iptv-preview"
  echo "Pushed the better readings to the box and restarted the server."
fi
