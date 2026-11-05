# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The owner and their household, on the sofa with a Fire TV Stick (2nd gen, Fire OS 8) and its voice remote, on a TV with a Sonos Beam (a 77" LG OLED is planned). Mostly live sport (football, tennis, UFC) in English, Spanish, German, Italian and Arabic; films and series in the evening. Other household members watch too (Russian channels, kids' channels), so controls must be findable without being taught.

## Product Purpose

A personal TiviMate replacement for one IPTV subscription: live channels with a guide and competition groups, plus an on-demand library (4K films, UFC replays, series in five languages). Success is getting to the right picture in as few presses as possible, and it staying on screen.

## Positioning

Built around one account and one person's habits: channels ranked by measured quality, 🏆 groups per competition learned from the guide, Live now for matches, failover between feeds, and a library trimmed to 4K films, UFC replays and series in the household's languages.

## Operating Context

- Ten-foot UI, D-pad remote only (Up/Down/Left/Right, OK, Back, Menu ☰, Play/Pause, ⏪/⏩, Home, mic). No pointer, no touch.
- The account allows one stream: browsing Movies or Shows keeps the live channel playing underneath until something plays.
- Movies and Shows: the user mostly resumes or goes straight to a title they know; browsing for something new is secondary. OK on a film or show opens a title page (art, plot, runtime, Resume / Play from start, episodes), not instant play. Browse shows still backdrops, never autoplaying trailers.
- Server: Bun app on a Hetzner box (`/api/app/*`) feeding the app channels, guide, movies, shows, episodes and per-film info (plot, runtime, genres, rating, backdrops from the provider).

## Capabilities and Constraints

- Jetpack Compose for TV, Media3/ExoPlayer, release builds with R8 and a baseline profile; self-updates from CI.
- Weak hardware: every screen must hold frame time on the stick (rows built a few per frame, nothing heavy on focus moves).
- Provider metadata is uneven: some titles lack plot, runtime or backdrop; episode titles are often "Episode N". Design for missing data.
- Out of scope by the user's choice: catch-up, match reminders, autoplaying trailers.

## Product Principles

1. Fewest presses to the picture; the common path (resume) is one press from opening.
2. Never leave focus nowhere: Back always peels one layer, and focus lands somewhere sensible after every change.
3. Say what a key does where it matters, in the app's plain words.
4. Missing data degrades quietly; nothing shows an empty frame or a placeholder claim.
5. Smooth on the stick beats rich on paper.
