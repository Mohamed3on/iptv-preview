#!/usr/bin/env bash
# Build the web app, sync the server to the Hetzner box and restart it.
# Run from the repo root:  SERVER=root@65.108.153.112 ./deploy/sync.sh
#   /opt/iptv-preview/      ← server.ts, api/, dist/ (this rsync target)
#   /opt/iptv-preview/.env  ← XTREAM_*, PLAYLIST_TOKEN, EPG_URLS; never synced
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
APP_DIR=/opt/iptv-preview

bun run build
rsync -az --delete server.ts api dist "$SERVER:$APP_DIR/"
rsync -a deploy/iptv-preview.service "$SERVER:/etc/systemd/system/"
ssh "$SERVER" "chown -R iptv:iptv $APP_DIR && systemctl daemon-reload && systemctl enable iptv-preview && systemctl restart iptv-preview"
