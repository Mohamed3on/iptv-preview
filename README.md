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

The app and its API — the trimmed playlist (`/api/playlist?t=…`) and EPG (`/api/epg?t=…`) for TiviMate — run at **https://<your-domain>** on the Hetzner box shared with another app (`root@<server-ip>`). `server.ts` runs under systemd (`deploy/iptv-preview.service`, `127.0.0.1:8790`), and the Cloudflare Tunnel `iptv-preview` (`cloudflared.service`) routes the hostname to it. Secrets live in `/opt/iptv-preview/.env` (`XTREAM_HOST/USER/PASS`, `PLAYLIST_TOKEN`, `EPG_URLS`).

Every push to `master` deploys (`.github/workflows/deploy.yml`, needs the `HETZNER_SSH_KEY` secret). To deploy by hand: `SERVER=root@<server-ip> ./deploy/sync.sh`. Logs: `ssh root@<server-ip> 'journalctl -u iptv-preview -f'`.

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
