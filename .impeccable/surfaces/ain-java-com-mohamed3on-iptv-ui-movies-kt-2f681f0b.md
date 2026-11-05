---
version: 1
slug: "ain-java-com-mohamed3on-iptv-ui-movies-kt-2f681f0b"
primary_target: "firetv/app/src/main/java/com/mohamed3on/iptv/ui/Movies.kt"
related_targets: ["firetv/app/src/main/java/com/mohamed3on/iptv/ui/MoviePlayback.kt"]
---

# Movies & Shows (Fire TV)

Scope: the Movies and Shows libraries (ui/Movies.kt), their title pages and browsing. Visitor mode: Operate. Audience/job: the household on the sofa, mostly resuming or going to a known title; browsing is secondary. OK on a title opens its title page; browse shows still backdrops, never trailers. Constraints: D-pad only, Fire TV Stick 2nd gen frame budget, one stream (live keeps playing under browse), uneven provider metadata.

## Direction contract

THESIS: Up Next first. The library opens on what you were watching with Resume already focused, one press to the picture; it refuses the category default of a wall of poster rows where resuming is one card among many.

OWN-WORLD: The app's own world: ink #0B0E12 ground, translucent blue-black material, raised #2C3744 focus lift with a soft white ring, one accent #3D9BFF for progress and what is playing, Inter at the 10-foot scale. Titles bring their colour through full-bleed still backdrops darkened by left and bottom scrims.

STORY: The viewer sees their show and where they are in it (S1 E2 · 38 min left), presses OK and is watching. Down shows the rest of what they are part-way through as wide cards; Down again leaves for the library rows. Any card opens a title page with Resume or Play and Start over, and a show's page lists its episodes with stills and progress.

FIRST VIEWPORT: Full-bleed backdrop of the newest Continue watching title. Low-left at safe inset: title in display type, meta line, episode line with a progress bar, three lines of plot, then buttons [Resume S1 E2] (focused, filled light) [Episodes or Start over]. Along the bottom: "Continue watching" as 16:9 backdrop cards with progress. Search sits top-right, reached with Up.

FORM: Up Next first, position 3 of my ordered list of 7, dealt as the lead; seed:2dc022bdbdb225a5.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
