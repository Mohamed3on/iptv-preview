# 003 — Let the rows rise with the band when moving from Up next to the rows

- **Status**: DONE
- **Commit**: 8ad9d6d
- **Severity**: LOW
- **Category**: Missed opportunities / Physicality
- **Estimated scope**: 1 file (`ui/Movies.kt`), ~15 lines

All paths below are relative to `firetv/app/src/main/java/com/mohamed3on/iptv/`.

## Problem

On the Movies browse screen, Down from Up next goes to the rows, and Up from the first row comes back. The dark band behind the content already rises from the bottom on the panel spring:

```kotlin
// ui/Movies.kt:930 — Backdrop, current, correct
// Up next to the rows: the ground rises from the bottom to [solidFrom].
val cover = animateFloatAsState(if (solidFrom != null) 1f else 0f, Motion.panel(), label = "cover")
```

The content above it switches in a single frame, so the band moves while the content jumps:

```kotlin
// ui/Movies.kt:743 — MoviesBrowse, current
when {
    kind == 1 -> Box(Modifier.padding(top = Space.xxxl)) { Unavailable(content, onRetry) }
    kind == 0 -> Box(Modifier.padding(top = Space.xxxl)) { LoadingMovies(content) }
    kind != 2 -> {}
    shownPage == BrowsePage.UpNext -> UpNextPage(
        entries = upNext,
        ...
        onDown = { page = BrowsePage.Rows },
        modifier = Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.UpNext },
    )
    else -> Column(Modifier.fillMaxSize().padding(top = Space.safeY)) {
        val search = shownPage == BrowsePage.Search
        Hero(...)
        if (search) Box(...) { SearchGrid(...) } else Box(...) { Rows(...) }
    }
}
```

## Target

- **Up next → rows (Down):** the rows rise from below by a third of their height and fade in, on `Motion.panel()` (no bounce, about 0.35 s response). That's the same spring as the band, so the two move together. Up next fades out on `Motion.spring(0.2f)`.
- **Rows → Up next (Up, or Back):** the reverse. The rows sink a third of their height and fade out on `Motion.panel()`, and Up next fades in on `Motion.spring(0.3f)`.
- **Rows ↔ Search doesn't animate.** It stays one content branch, because the Fire TV keyboard covers the screen as Search opens.
- With reduced motion on, both directions use `fadeIn(tween(Motion.FADE_MS)) togetherWith fadeOut(tween(Motion.FADE_MS))`, with no slide.
- Focus moves exactly as today. The existing `LaunchedEffect(active, kind, shownPage)` at `ui/Movies.kt:680` already retries for `FOCUS_FRAMES` frames until focus lands.

## Repo conventions to follow

- Direction follows the key, as on the live banner's zaps (`Motion.zapIn`/`zapOut`, `ui/theme/Motion.kt:60-66`). An entrance moves by a third of its size (`it / 3`), and an incoming element fades on `Motion.spring(0.3f)` while the outgoing one fades on `Motion.spring(0.2f)`. Up next's own hero already crossfades this way (`ui/Movies.kt:1037-1041`).
- `reduced` is already in scope in `MoviesBrowse` (`ui/Movies.kt:626`).

## Steps

1. **`ui/Movies.kt:747-790`.** Replace the two branches `shownPage == BrowsePage.UpNext -> UpNextPage(...)` and `else -> Column(...) { ... }` with one branch:

   ```kotlin
   else -> AnimatedContent(
       targetState = shownPage == BrowsePage.UpNext,
       transitionSpec = {
           when {
               reduced -> fadeIn(tween(Motion.FADE_MS)) togetherWith fadeOut(tween(Motion.FADE_MS))
               // Back to Up next: the rows sink back under the band.
               targetState -> fadeIn(Motion.spring(0.3f)) togetherWith
                   (slideOutVertically(Motion.panel()) { it / 3 } + fadeOut(Motion.panel()))
               // Down to the rows: they rise with the band.
               else -> (slideInVertically(Motion.panel()) { it / 3 } + fadeIn(Motion.panel())) togetherWith
                   fadeOut(Motion.spring(0.2f))
           }
       },
       modifier = Modifier.fillMaxSize(),
       label = "browse",
   ) { upNextShown ->
       if (upNextShown) UpNextPage(
           /* every argument exactly as before */
       ) else Column(Modifier.fillMaxSize().padding(top = Space.safeY)) {
           /* the Column's body exactly as before */
       }
   }
   ```

   Move the existing `UpNextPage(...)` call and `Column(...) { ... }` in **verbatim**.
2. Import `androidx.compose.animation.slideInVertically` and `androidx.compose.animation.slideOutVertically` if they're missing. `AnimatedContent`, `fadeIn`, `fadeOut`, `togetherWith`, and `tween` are already imported (lines 3, 10, 38-40).

## Boundaries

- Do NOT touch `Backdrop` or its `cover` spring, `Hero`, `SearchGrid`, `Rows`, `UpNextPage`, or the focus `LaunchedEffect`.
- Do NOT animate Rows ↔ Search.
- Do NOT add new springs or durations.
- If a quoted excerpt doesn't match the code you find (it may have drifted since commit 8ad9d6d), STOP and report instead of improvising.

## Verification

- **Mechanical**: `cd firetv && ./gradlew -q assembleRelease` succeeds, then `./dev.sh`.
- **Feel check** on the stick (adb: `~/Library/Android/sdk/platform-tools/adb -s 192.168.178.25:5555`, wrapped in `perl -e 'alarm(20); exec @ARGV'`). Open Movies with something in Up next:
  - Down: the rows rise **together with** the dark band, at the same time and speed, and Up next fades away. Record it (`adb shell screenrecord --time-limit 4 /sdcard/b.mp4`, then `ffmpeg -i b.mp4 -vf fps=60 f_%03d.png`) and check frame by frame that the band's edge and the top of the rows travel together.
  - Up from the first row, and Back from the rows: the reverse.
  - Press Down and then Up within 0.2 s: it reverses from where it is, with no snap or restart.
  - After Down, the first row's card is focused as quickly as before. Hold Down from Up next: focus still walks down the rows.
  - Rows → Search → Back: no slide, exactly as today.
  - **Feel can't be judged from code.** The two pages overlap for a moment while they cross. If that looks double-exposed, report it. Don't add blur or retune.
  - Frame cost: `adb shell dumpsys gfxinfo com.mohamed3on.iptv reset`, Down/Up five times, then `dumpsys gfxinfo com.mohamed3on.iptv`. The worst frame must be ≤ 32 ms. The rows build on Down either way, so compare against a run on the current build. **If it's worse, stop and report.**
  - Reduced motion: `adb shell settings put global animator_duration_scale 0`, force-stop and reopen; it should only fade. **Restore with `settings put global animator_duration_scale 1`.**
- **Done when**: the rows and band move as one, reversal is smooth, focus is unchanged, there are no frames over 32 ms, and the stick is left on HOME.
