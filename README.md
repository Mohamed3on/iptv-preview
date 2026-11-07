# IPTV Preview

Turns an Xtream Codes IPTV subscription into a trimmed sports playlist and guide:

- **Playlist and guide** for TiviMate or any IPTV player: sports channels grouped by region, a 🏆 group per league and cup learned from the guide, feeds sorted by the quality they really deliver (measured, not the provider's labels), spares kept, dead slots dropped, plus 4K films and UFC replays.
- **Fire TV app** (`firetv/`): a native player for the same channels, with multi-view and a Movies & Shows library (TMDB, IMDb and Letterboxd scores, Trakt, Real-Debrid and TorBox). It installs once over adb and updates itself from the server.
- **Web viewer** at `/` for any M3U playlist and XMLTV guide (`bun install && bun dev`).

**To run your own, follow [docs/SETUP.md](docs/SETUP.md).** Every setting is listed in [.env.example](.env.example). The rest of this README covers how the original deployment runs.

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
- `/api/app/version`, `/api/app/apk` and `/api/app/dm`: the latest build and its dex metadata (the baseline profile, which the stick compiles at install), served from `/opt/iptv-preview/app/` (outside `dist/`, so deploys never prune it).
- `/api/app/movies`: the on-demand library (4K movies, UFC replays), read from the copy the playlist build saves in `.cache/vod-last-good.json`, so the app never adds a pull of the 74 MB catalogue.
- `POST /api/app/quality`: what the app measured on a stream it played (resolution, frame rate, codec, Atmos), merged into the quality cache with the probe's rules (`api/_quality.ts`).
- `POST /api/app/events`: tunes with their time to picture, stalls, feed switches and busy / wrong-route / offline problems, appended to `.cache/app-events.jsonl` on the box (rolls over at 20 MB). Read it to see which feeds hold up and how fast zaps are.

`.github/workflows/firetv.yml` runs on pushes touching `firetv/**`. It runs the unit tests, builds a release-signed APK and copies it to the box with a key that can only write `/opt/iptv-preview/app/`: user `iptvapp`, whose `authorized_keys` entry is `restrict,command="rrsync -wo -no-del /opt/iptv-preview/app"`. Secrets: `ANDROID_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `APP_TOKEN`, `APP_SERVER_URL`, `APP_DEPLOY_SSH_KEY` and `SERVER_HOST`. The APK carries the token, so it is never uploaded as a GitHub artifact. Local builds read the same values from `~/.gradle/gradle.properties` (`IPTV_KEYSTORE_FILE`, `IPTV_KEYSTORE_PASSWORD`, `IPTV_KEY_ALIAS`, `IPTV_KEY_PASSWORD`, `IPTV_APP_TOKEN`, `IPTV_SERVER_URL`). Keep an offline backup of the keystore: without it, the next update needs an uninstall and a fresh adb install.

To rotate the app token:

1. Add the new token to `APP_TOKENS` (`old,new`) and restart `iptv-preview`.
2. Set the `APP_TOKEN` secret to the new token and push a `firetv/` change.
3. Once the stick has self-updated, remove the old token from `APP_TOKENS` and restart again.

First install: on the stick, enable Settings → My Fire TV → Developer options → ADB debugging. Then run `adb connect <stick-ip>` and `cd firetv && ./gradlew installRelease`. The first install must be the release build, or the first self-update fails on a signature mismatch. Grant the app "Install unknown apps" when it first offers an update.

**Baseline profile.** Release builds ship one (`firetv/app/src/main/generated/baselineProfiles/`), so the hot paths (the channel list, zaps, the panels) run compiled from the first frame after an update instead of warming up in the JIT. CI turns it into a dex metadata file (`firetv/baselineprofile/dex-metadata.sh`) that the self-update installs as `base.dm` beside the APK; a missing or failed one never blocks an update. Regenerate it after big UI changes with `firetv/baselineprofile/generate.sh` (needs `bun`; Gradle boots its own headless API 34 emulator, so a connected stick is never used) and commit the result. The profiling builds talk to `firetv/baselineprofile/fixture-server.ts`, a local copy of the channels and guide whose provider route plays a generated test clip (needs `ffmpeg`): the emulator plays, so playback, health and the info view are profiled, but never takes the account's stream slot or writes to the event log.

## Tech Stack

- React + TypeScript + Vite
- HLS.js / mpegts.js for streaming
- @tanstack/react-virtual for list virtualization
- Web Workers for EPG parsing
