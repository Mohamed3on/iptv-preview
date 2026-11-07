#!/usr/bin/env bash
# Re-probe feed quality off the box and merge the results into the box's store
# (/opt/sofa/.cache/quality.json). It runs elsewhere (nightly on GitHub Actions, or a Mac)
# because the provider refuses streams from the box's IP (HTTP 511). It holds the account's
# one connection slot, so the probe skips/stops when someone is watching. The probe works on
# a copy and sends back only what it changed; the box merges that entry by entry, so readings
# the app records during the run are kept, and the server picks them up without a restart.
# From the repo root:
#   SERVER=root@<server-ip> ./deploy/probe-quality.sh [minutes, default 30]
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A fresh box has no readings yet.
rsync -a "$SERVER:/opt/sofa/.cache/quality.json" "$work/quality.json" 2>/dev/null || echo '{}' > "$work/quality.json"
QUALITY_FILE="$work/quality.json" PROBED_FILE="$work/probed.json" bun scripts/probe-quality.ts 8 refresh "mins=${1:-30}"
if [ ! -f "$work/probed.json" ] || [ "$(cat "$work/probed.json")" = "{}" ]; then
  echo "No changed readings."
else
  ssh "$SERVER" "cd /opt/sofa && runuser -u sofa -- /usr/local/bin/bun scripts/merge-quality.ts" < "$work/probed.json"
fi
