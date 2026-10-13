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

The app and its API — the trimmed playlist (`/api/playlist?t=…`) and EPG (`/api/epg?t=…`) for TiviMate — run at **https://iptv.mohamed3on.com** on the Hetzner box shared with truescore (`root@65.108.153.112`). `server.ts` runs under systemd (`deploy/iptv-preview.service`, `127.0.0.1:8790`), and the Cloudflare Tunnel `iptv-preview` (`cloudflared.service`) routes the hostname to it. Secrets live in `/opt/iptv-preview/.env` (`XTREAM_HOST/USER/PASS`, `PLAYLIST_TOKEN`, `EPG_URLS`, `TYPESAFE_API_KEY`).

The box also keeps the generated caches current, restarting the server when one changes:

- `iptv-learn.timer` relearns `api/competition-cache.ts` from the EPG daily at 05:23 UTC.
- `systemctl start --no-block iptv-quality@30` re-probes feed quality into `api/quality-cache.ts` for 30 minutes. It holds the account's one connection slot, so run it when nobody is watching.

The repo's copies of those two files only seed a fresh box; a deploy never overwrites the box's. Deploy with `SERVER=root@65.108.153.112 ./deploy/sync.sh`. Logs: `ssh root@65.108.153.112 'journalctl -u iptv-preview -f'` (or `-u iptv-learn`, `-u 'iptv-quality@*'`).

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
