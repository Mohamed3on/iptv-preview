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

The app and its API — the trimmed playlist (`/api/playlist?t=…`) and EPG (`/api/epg?t=…`) for TiviMate — run at **https://<your-domain>** on the Hetzner box shared with another app (`root@<server-ip>`). `server.ts` runs under systemd (`deploy/iptv-preview.service`, `127.0.0.1:8790`), and the Cloudflare Tunnel `iptv-preview` (`cloudflared.service`) routes the hostname to it. Secrets live in `/opt/iptv-preview/.env` (`XTREAM_HOST/USER/PASS`, `PLAYLIST_TOKEN`, `EPG_URLS`, `TYPESAFE_API_KEY`).

The box owns the two generated caches, and the server restarts when one changes:

- `iptv-learn.timer` relearns `api/competition-cache.ts` from the EPG daily at 05:23 UTC.
- Feed quality (`api/quality-cache.ts`) is probed nightly at 03:30 UTC by GitHub Actions (`.github/workflows/probe-quality.yml`, secrets `XTREAM_HOST/USER/PASS` + `HETZNER_SSH_KEY`; run it by hand from the Actions tab), because the provider refuses streams from the box's IP. It runs `SERVER=root@<server-ip> ./deploy/probe-quality.sh 30`, which pulls the box's cache, re-probes for 30 minutes and pushes it back (the same command works from a Mac with `.env.local`). Each run first re-checks readings older than 14 days, then chases feeds below their label. It holds the account's one connection slot, so it won't start while someone is watching and stops as soon as someone starts.

The repo's copies of those two files only seed a fresh box; a deploy never overwrites the box's. Every push to `master` deploys (`.github/workflows/deploy.yml`, needs the `HETZNER_SSH_KEY` secret); by hand: `SERVER=root@<server-ip> ./deploy/sync.sh`. Logs: `ssh root@<server-ip> 'journalctl -u iptv-preview -f'` (or `-u iptv-learn`).

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
