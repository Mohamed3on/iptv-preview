# IPTV Preview

A web-based IPTV playlist viewer with EPG (Electronic Program Guide) support. Load M3U playlists, browse channels, and watch live streams directly in the browser.

## Features

- **M3U Playlist Support** — Load via URL or file upload
- **EPG / XMLTV** — Program guide with current/next show, progress bar, descriptions
- **HLS & MPEG-TS Playback** — Handles `.m3u8` (HLS.js) and `.ts` (mpegts.js) streams
- **Gzip Support** — Transparently decompresses `.gz` EPG files
- **20k+ Channels** — Virtualized list handles massive playlists smoothly
- **Off-thread EPG Parsing** — Web Worker with regex parser for large XMLTV files
- **Search & Filter** — Full-text search and group filtering
- **Persistent URLs** — Saves playlist/EPG URLs to localStorage

## Getting Started

```bash
bun install
bun dev
```

## Hosting

The app and its API — the trimmed playlist (`/api/playlist?t=…`) and EPG (`/api/epg?t=…`) for TiviMate — run on a Hetzner box shared with another app (`root@<server-ip>`; GitHub secret `SERVER_HOST`). `server.ts` runs under systemd (`deploy/iptv-preview.service`, `127.0.0.1:8790`), and the Cloudflare Tunnel `iptv-preview` (`cloudflared.service`) routes the hostname to it. Secrets live in `/opt/iptv-preview/.env` (`XTREAM_HOST/USER/PASS`, `PLAYLIST_TOKEN`, `EPG_URLS`, `TYPESAFE_API_KEY`).

The box owns the two generated caches, and the server restarts when one changes:

- `iptv-learn.timer` relearns `api/competition-cache.ts` from the EPG daily at 05:23 UTC.
- Feed quality (`api/quality-cache.ts`) is probed nightly at 02:23 UTC by GitHub Actions (`.github/workflows/probe-quality.yml`, secrets `XTREAM_HOST/USER/PASS` + `HETZNER_SSH_KEY` + `SERVER_HOST`; run it by hand from the Actions tab), because the provider refuses streams from the box's IP. It runs `SERVER=root@<server-ip> ./deploy/probe-quality.sh 30`, which pulls the box's cache, re-probes for 30 minutes and pushes it back (the same command works from a Mac with `.env.local`). Each run first re-checks readings older than 14 days, then chases feeds below their label. It holds the account's one connection slot, so it won't start while someone is watching and stops as soon as someone starts.

The repo's copies of those two files only seed a fresh box; a deploy never overwrites the box's. Every push to `master` deploys (`.github/workflows/deploy.yml`, needs the `HETZNER_SSH_KEY` and `SERVER_HOST` secrets), except pushes that only touch Markdown or `firetv/**`; by hand: `SERVER=root@<server-ip> ./deploy/sync.sh`. Logs: `ssh root@<server-ip> 'journalctl -u iptv-preview -f'` (or `-u iptv-learn`).

## Fire TV app

`firetv/` is a native player app for the Fire TV Stick, sideloaded and self-updating. It reads its own endpoints, each gated by `?t=` against `APP_TOKENS` in the box's `.env` (comma-separated; never `PLAYLIST_TOKEN`):

- `/api/app/channels`: groups, channels, feed families, quality badges and both provider fronts, as JSON.
- `/api/app/guide`: the guide as a gzip SQLite file.
- `/api/app/version` and `/api/app/apk`: the latest build, served from `/opt/iptv-preview/app/` (outside `dist/`, so deploys never prune it).

`.github/workflows/firetv.yml` runs on pushes touching `firetv/**`. It runs the unit tests, builds a release-signed APK and copies it to the box with a key that can only write `/opt/iptv-preview/app/`: user `iptvapp`, whose `authorized_keys` entry is `restrict,command="rrsync -wo -no-del /opt/iptv-preview/app"`. Secrets: `ANDROID_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `APP_TOKEN`, `APP_SERVER_URL`, `APP_DEPLOY_SSH_KEY` and `SERVER_HOST`. The APK carries the token, so it is never uploaded as a GitHub artifact. Local builds read the same values from `~/.gradle/gradle.properties` (`IPTV_KEYSTORE_FILE`, `IPTV_KEYSTORE_PASSWORD`, `IPTV_KEY_ALIAS`, `IPTV_KEY_PASSWORD`, `IPTV_APP_TOKEN`, `IPTV_SERVER_URL`). Keep an offline backup of the keystore: without it, the next update needs an uninstall and a fresh adb install.

To rotate the app token:

1. Add the new token to `APP_TOKENS` (`old,new`) and restart `iptv-preview`.
2. Set the `APP_TOKEN` secret to the new token and push a `firetv/` change.
3. Once the stick has self-updated, remove the old token from `APP_TOKENS` and restart again.

First install: on the stick, enable Settings → My Fire TV → Developer options → ADB debugging. Then run `adb connect <stick-ip>` and `cd firetv && ./gradlew installRelease`. The first install must be the release build, or the first self-update fails on a signature mismatch. Grant the app "Install unknown apps" when it first offers an update.

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
