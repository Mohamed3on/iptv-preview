#!/usr/bin/env bash
# Build the web app, sync the server to the Hetzner box and restart it.
# Run from the repo root:  SERVER=root@65.108.153.112 ./deploy/sync.sh
#   /opt/iptv-preview/      ← server.ts, api/, scripts/, dist/ (this rsync target)
#   /opt/iptv-preview/.env  ← XTREAM_*, PLAYLIST_TOKEN, EPG_URLS, TYPESAFE_API_KEY; never synced
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
APP_DIR=/opt/iptv-preview

bun run build
rsync -az --delete --exclude competition-cache.ts --exclude quality-cache.ts server.ts api scripts dist "$SERVER:$APP_DIR/"
# The box's own jobs (iptv-learn, iptv-quality@) keep these two caches current, so the
# repo's copies only seed a fresh box and never overwrite what the box has learned.
rsync -a --ignore-existing api/competition-cache.ts api/quality-cache.ts "$SERVER:$APP_DIR/api/"
rsync -a deploy/*.service deploy/*.timer "$SERVER:/etc/systemd/system/"
ssh "$SERVER" "chown -R iptv:iptv $APP_DIR && systemctl daemon-reload && systemctl enable --now iptv-learn.timer && systemctl enable iptv-preview && systemctl restart iptv-preview"
