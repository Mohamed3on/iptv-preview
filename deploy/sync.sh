#!/usr/bin/env bash
# Build the web app, sync the server to the Hetzner box and restart it.
# Run from the repo root:  SERVER=root@<server-ip> ./deploy/sync.sh
#   /opt/sofa/      ← server.ts, api/, scripts/, dist/ (this rsync target)
#   /opt/sofa/.env  ← XTREAM_*, PLAYLIST_TOKEN, EPG_URLS, TYPESAFE_API_KEY; never synced
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
APP_DIR=/opt/sofa

bun run build
rsync -az --delete --exclude competition-cache.ts --exclude quality-cache.ts server.ts api scripts dist "$SERVER:$APP_DIR/"
# The box's own jobs (sofa-learn, the quality probe) keep these two caches current, so the
# repo's copies only seed a fresh box and never overwrite what the box has learned.
rsync -a --ignore-existing api/competition-cache.ts api/quality-cache.ts "$SERVER:$APP_DIR/api/"
rsync -a deploy/*.service deploy/*.timer "$SERVER:/etc/systemd/system/"
ssh "$SERVER" "chown -R sofa:sofa $APP_DIR && systemctl daemon-reload && systemctl enable --now sofa-learn.timer sofa-library.timer && systemctl enable sofa && systemctl restart sofa"
