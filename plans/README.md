# Animation plans

These came from an animation review of the Fire TV app on 2026-11-06 (commit 8ad9d6d). Each plan is self-contained.

| # | Plan | Severity | Status |
|---|---|---|---|
| 001 | [Slide the Versions and ☰ panels in from the right](001-slide-title-panels.md) | MEDIUM | DONE |
| 002 | [Fade a title page in when a movie or show is opened](002-fade-title-page-in.md) | MEDIUM | DROPPED (frame cost) |
| 003 | [Let the rows rise with the band from Up next](003-browse-rows-rise-with-band.md) | LOW | DONE |
| 004 | [Fade "Still watching?" and the idle stop screen](004-fade-idle-prompts.md) | LOW | DONE (not seen on the stick) |

## Order

Do them one after another, in the order 001 → 002 → 003 → 004. Plans 001–003 all edit `ui/Movies.kt`, so running them in parallel would conflict. 004 is independent.

- **001** first: it's the safest, and it copies an existing pattern exactly.
- **002** is the one you'll notice most. It's gated on `firetv/check.sh` ("worst frame opening a title" ≤ 32 ms).
- **003** depends on nothing, but its frame cost should be compared against a build that already has 002.
- **004** can't be fully checked on the stick (the idle timeout is 4 hours).

## Deliberately not planned

- A crossfade on a channel change: it happens hundreds of times a day and would add to the tune time.
- An animation on Back from a title page: Back must feel instant.
- An animation when a film starts: the display-mode switch blanks HDMI anyway.
- An animation when Search opens in Movies: the Fire TV keyboard covers the screen.
- An animation from the first-run loading screen: almost never seen.

## Learnt while executing

- 001: a panel that animates out still holds focus while it leaves, and the title pages' "focus is back with me" checks then do nothing, leaving focus nowhere. `SlidePanel` clears focus as soon as a panel starts closing.
- 002: two full screens drawn at once cost the stick ~18 ms on the worst frame of opening a title (median 58 vs 40 ms). Opening a title is already over 32 ms about half the time without it.
