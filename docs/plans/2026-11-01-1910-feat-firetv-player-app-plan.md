---
title: Fire TV Player App - Plan
type: feat
date: 2026-11-01
topic: firetv-player-app
artifact_contract: ce-unified-plan/v1
product_contract_source: ce-brainstorm
execution: code
---

# Fire TV Player App - Plan

## Goal Capsule

- **Objective:** Watching live TV on the Fire TV Stick 4K needs no paid player: an own app opens to the last channel, zaps and finds events at least as fast as TiviMate does today.
- **Means:** A native, sideloaded Fire TV app that consumes this repo's server, which does the heavy curation work.
- **Product authority:** The user (sole user of the app). v2 work (multi-view, 4K movies) is not active scope.
- **Open blockers:** None.

---

## Product Contract

### Summary

A small native Fire TV app, sideloaded onto the Fire TV Stick 4K, that plays the curated live channels from this repo's server. It opens straight into the last-watched channel, offers a grouped channel list with now/next, a per-channel schedule, and event search across the EPG. It updates itself from the server, so a push to `master` reaches the TV without adb.

### Problem Frame

The user watches live TV through TiviMate on a Fire TV Stick 4K, fed by this repo's trimmed playlist and EPG. They do not want to pay for TiviMate, but the parts they rely on are speed (startup, zapping, scrolling) and finding an event across channels: they mostly browse the channel list with now/next, and sometimes search for an event to see which channels' EPG shows it. Everything else TiviMate does (catch-up, recording, profiles) is unused. The server already curates channels, 🏆 competition groups, quality ordering and an EPG scoped to the kept channels, so the device-side work left is small.

### Key Decisions

- **Own native app over forking an open-source player or using a free player with server-side groups.** The server already removed the hard part (huge playlist/EPG), so a fresh app stays small and fast; a fork would mostly be deletion, and a free player gives no real search. (session-settled: user-directed — chosen over forking an open-source player and over a free player plus server groups: smallest fast client on top of existing server curation)
- **Channel list with now/next, no EPG grid in v1.** The user mostly browses the list; the grid is the most expensive piece to make smooth. Governs R5, R6. (session-settled: user-approved — chosen over a full TV-guide grid: list is how the user finds things today)
- **The server prepares a compact feed for the app; the TiviMate feeds stay unchanged.** Keeps the stick's work minimal and TiviMate usable as a fallback. Governs R1, R2.
- **Both provider routes, switchable inside the app.** The user switches between the plain and VPN playlists in TiviMate today. Governs R4. (session-settled: user-directed — chosen over plain-only or VPN-only: user uses both)
- **Self-update is in v1.** Sideloaded apps don't update themselves; this matches the push-to-deploy habit. Governs R15, R16. (session-settled: user-directed — chosen over leaving updates out of v1: user asked to keep it)
- **Multi-view and 4K movies are v2, but v1's player must not block them.** Governs R11. (session-settled: user-directed — chosen over multi-view/VOD in v1: ship the fast basics first, but multi-view is needed)
- **No favourites.** The server's groups already play that role.

### Requirements

**Data and setup**

- R1. The app gets channels, groups and guide data from this repo's server in a form made for it, already limited to the curated live channels.
- R2. The existing `/api/playlist` and `/api/epg` feeds keep working unchanged for TiviMate.
- R3. First-time setup never requires typing a long URL or token with the remote.
- R4. A quick switch toggles between the plain and VPN provider routes (the `&vpn=1` variant), replacing the two separate playlists the user keeps in TiviMate today.

**Browsing**

- R5. The channel list shows the server's groups in server order, and each channel row shows its logo, name, current programme with progress, and the next programme.
- R6. Opening a channel's details shows its schedule from now through the end of the available EPG.
- R7. Event search matches programme titles across all channels, accepts short partial words (e.g. "arse" finds Arsenal), and lists results live-now first, then upcoming up to about 24 hours ahead.
- R8. Picking a live search result tunes to that channel; an upcoming result shows its channel and start time.

**Playback**

- R9. On launch the app plays the last-watched channel without passing through any menu.
- R10. In the full-screen player, D-pad up/down zaps to the previous/next channel in the current group, OK opens the channel list over the video, and Back jumps to the previously watched channel (Back closes an open overlay first).
- R11. The player handles one live stream in v1 but is built so that several simultaneous tiles (multi-view) and seekable VOD playback can be added later without a rewrite.
- R12. Switching channels never leaves the previous stream holding a provider connection.
- R13. The audio track can be switched from the player when a feed carries more than one track.
- R14. When a stream fails or stalls, the app retries on its own, and if it can't recover it says whether the channel is offline or the connection is busy.

**Updates**

- R15. At launch the app checks the server for a newer version and, if one exists, offers it through the system installer.
- R16. An update never interrupts a playing stream.

### Key Flows

- F1. Launch and watch
  - **Trigger:** User opens the app.
  - **Steps:** Last channel starts playing (R9); in parallel the app refreshes guide data and checks for an update (R15, R16).
  - **Outcome:** Picture on screen with no navigation.
- F2. Browse and zap
  - **Trigger:** User presses OK in the player.
  - **Steps:** Channel list opens over video at the current channel (R5); user moves through groups, picks a channel; or zaps with up/down without opening the list (R10).
  - **Outcome:** New channel playing; Back returns to the previous one.
- F3. Find an event
  - **Trigger:** User opens search and types part of a team, player or event name.
  - **Steps:** Results appear live-first (R7); user picks one (R8).
  - **Outcome:** Live result plays; upcoming result shows when and where it airs.

### Acceptance Examples

- AE1. **Covers R7, R8.** **Given** Arsenal plays now on two channels and later on one, **when** the user searches "arse", **then** the two live channels list first, the later one after with its start time, and picking a live one plays it.
- AE2. **Covers R10.** **Given** the user watched channel A then switched to B, **when** they press Back in the full-screen player, **then** A plays; **given** the channel list overlay is open, Back closes it instead.
- AE3. **Covers R15, R16.** **Given** a newer version is on the server and the app is playing, **then** no installer prompt interrupts playback; the update is offered at the next launch.
- AE4. **Covers R14.** **Given** another device holds the account's connection, **when** the user tunes a channel, **then** the app reports the connection is busy rather than calling the channel broken.

### Success Criteria

- Cold start to picture, channel zap to picture, and channel-list scrolling are at least as fast as TiviMate on the same Fire TV Stick 4K and the same channels, measured on the device.
- After a week of normal use, the user has not needed to open TiviMate for live viewing.

### Scope Boundaries

**Deferred for later (v2)**

- Multi-view with 2–4 tiles, audio from the focused tile.
- 4K movies (VOD) browsing and playback with seek and resume.

**Outside this product's identity**

- Catch-up, recording, profiles, reminders or auto-tune for upcoming events.
- EPG grid view.
- Subtitles and aspect-ratio controls.
- Favourites.
- Distribution through an app store or use by anyone but the user.

### Dependencies / Assumptions

- The provider allows several simultaneous streams from the same device even though the panel reports `max_connections: 1` (TiviMate multi-view works for the user); a second IP while watching gets HTTP 458. v2 multi-view depends on this.
- Fire OS lets the app install its own updates after the user grants it the install-unknown-apps permission once; installs still show a system confirmation.
- The server box stays reachable from the TV through the existing Cloudflare Tunnel hostname.

### Outstanding Questions

**Deferred to Planning**

- Concrete target numbers for the success criteria, from a TiviMate baseline measured on the stick.
- How R3's setup works (built-in config, pairing from a phone, or similar).
- How guide data stays fresh during long viewing sessions.
- Whether some feeds' audio codecs need extra decoder support on the stick.

### Sources / Research

- `api/playlist.ts`, `api/epg.ts` — token-gated M3U and XMLTV feeds used by TiviMate.
- `api/_lib.ts` — `buildM3U` writes direct provider stream URLs; `buildEventEpg` scopes the guide to curated channels; `withCompetitionGroups` builds the 🏆 groups; quality ordering via `api/quality-cache.ts` (not exposed in the M3U).
- `deploy/iptv-learn.timer`, `.github/workflows/deploy.yml` — daily competition relearn and push-to-deploy, the hooks R15's update channel can follow.
