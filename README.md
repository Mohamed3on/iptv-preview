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

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
