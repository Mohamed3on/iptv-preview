# 002 — Fade a title page in when a movie or show is opened

- **Status**: DROPPED — measured on the stick 2026-11-07: worst title-open frame median 58 ms with the fade vs 40 ms without (6 runs each, alternating installs), runs over 100 ms 2/6 vs 1/6
- **Commit**: 8ad9d6d
- **Severity**: MEDIUM
- **Category**: Missed opportunities
- **Estimated scope**: 1 file (`ui/Movies.kt`), ~20 lines

All paths below are relative to `firetv/app/src/main/java/com/mohamed3on/iptv/`.

## Problem

Pressing OK on a card (or a "More like this" card, or a person) pushes a title page. In one frame, the browse screen disappears and the full title page appears. Pressing the card already gives feedback: it scales to 0.96 on `Motion.focus()` (`ui/Movies.kt:1271`). What's missing is the bridge between the two screens.

Two things snap.

The browse screen hides itself as soon as the stack isn't empty:

```kotlin
// ui/Movies.kt:470 — MoviesLayer, current
hidden = playing != null || pages.isNotEmpty(),
```

```kotlin
// ui/Movies.kt:728 — MoviesBrowse, current
// Undrawn under a title page or a film: an alpha-0 layer costs the GPU nothing.
.graphicsLayer { alpha = if (hidden) 0f else 1f }
```

The title page draws at full opacity from its first frame:

```kotlin
// ui/Movies.kt:1918 — TitleFrame, current
private fun TitleFrame(backdrop: String?, hidden: Boolean, onBack: () -> Unit, onFocus: (Boolean) -> Unit, onMenu: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
```

Pages below the top one also hide at once:

```kotlin
// ui/Movies.kt:479 — MoviesLayer, current
pages.forEachIndexed { i, page ->
    key(i, page) {
        val top = i == pages.lastIndex
        val active = open && playing == null && top
        val hidden = playing != null || !top
        val back = { pages.removeAt(pages.lastIndex).also { if (pages.isEmpty()) graph.prefs.put(Check.LAST_SCREEN, "movies") } }
```

```kotlin
// ui/Movies.kt:335 — current
fun push(page: LibraryPage) {
    if (pages.size >= MAX_PAGES) pages.removeAt(0)
    pages.add(page)
```

If you only faded the title page in, it would fade in **over black**, because the screen under it is already hidden. The screen under it has to stay drawn until the fade finishes. The live channel list does the same thing under Movies (`ui/PlayerScreen.kt:430`: `alpha = ... * (1f - moviesShown.value)`).

## Target

- **Opening** any title page (film, show, person) fades it in on `Motion.focus()`: a spring with no bounce, about 0.22 s response, defined in `ui/theme/Motion.kt:45`. It's a fade only, with no slide or scale, because the page fills the screen.
- With reduced motion on, it uses `tween(Motion.FADE_MS)` (160 ms). That's still a fade, which is fine under reduced motion.
- The screen under the new page (the browse screen, or the previous title page) stays drawn until the fade finishes, then hides as it does today.
- **Back stays instant.** It's the most-pressed key on a title page and already lands on the card you came from. Don't animate leaving.
- Returning from a film, or popping back to a page that's already open, never fades again.
- Focus, `active`, and key handling are unchanged. The new page takes keys from its first frame, as it does today.

## Repo conventions to follow

- Springs come from `Motion` (`ui/theme/Motion.kt`): `Motion.focus()` and `Motion.FADE_MS`. Don't add new values.
- Use the alpha style from `slideFade` (`ui/theme/Motion.kt:95-98`): `compositingStrategy = CompositingStrategy.ModulateAlpha`, so a full-screen page fades without an offscreen copy, which the stick's GPU can't afford.
- `Animatable` is already imported in `ui/Movies.kt:36`. The exemplar for an `Animatable` fade is `ui/Movies.kt:912-934` (the backdrop fade).

## Steps

1. **`ui/Movies.kt`: add a composition local** next to the other top-level `private val`s near `MAX_PAGES` (line 248):

   ```kotlin
   /** Called by the top title page once it has faded in, so the screen under it can stop drawing. */
   private val LocalPageSettled = staticCompositionLocalOf<() -> Unit> { {} }
   ```

   Import `androidx.compose.runtime.staticCompositionLocalOf` if it's missing.
2. **MoviesLayer state.** Next to `val pages = ...` (line 311), add:

   ```kotlin
   // Whether the top title page has finished fading in; until then the screen under it stays drawn.
   var settled by remember { mutableStateOf(true) }
   ```

   In `push` (line 335), add `settled = false` after `pages.add(page)`.
3. **Browse hidden (line 470).** Change it to:

   ```kotlin
   hidden = playing != null || pages.size > 1 || (pages.isNotEmpty() && settled),
   ```
4. **Page loop (line 479).** Change `val hidden = playing != null || !top` to:

   ```kotlin
   // The page right under the top one stays drawn while the top one fades in.
   val hidden = playing != null || i < pages.lastIndex - 1 || (!top && settled)
   ```

   Change `val back = { pages.removeAt(pages.lastIndex)...` so it also sets `settled = true` (the page under is already fully in):

   ```kotlin
   val back = { settled = true; pages.removeAt(pages.lastIndex).also { if (pages.isEmpty()) graph.prefs.put(Check.LAST_SCREEN, "movies") } }
   ```

   Wrap the `when (page) { ... }` inside `key(i, page) { ... }` in `CompositionLocalProvider(LocalPageSettled provides { if (top) settled = true }) { ... }`.
5. **TitleFrame (line 1918).** At the top of the body, before `Box(`:

   ```kotlin
   val reduced = LocalReducedMotion.current
   val settledNow = LocalPageSettled.current
   val entrance = remember { Animatable(0f) }
   LaunchedEffect(Unit) {
       entrance.animateTo(1f, if (reduced) tween(Motion.FADE_MS) else Motion.focus())
       settledNow()
   }
   ```

   Change `.graphicsLayer { alpha = if (hidden) 0f else 1f }` to:

   ```kotlin
   .graphicsLayer {
       alpha = if (hidden) 0f else entrance.value
       compositingStrategy = CompositingStrategy.ModulateAlpha
   }
   ```

   Import `androidx.compose.ui.graphics.CompositingStrategy` if it's missing.

## Boundaries

- Do NOT animate Back or `stopped()` / `pages.clear()`.
- Do NOT touch `MoviePlayback`, `PlayerScreen.kt`, `Motion.kt`, or any focus or `active` logic.
- Do NOT change how `MAX_PAGES` evicts pages. Known side effect: when the 7th page evicts the first, `key(i, page)` rebuilds every page, so the new top page and the one under it fade in from black together. That only happens six pages deep, so leave it.
- If a quoted excerpt doesn't match the code you find (it may have drifted since commit 8ad9d6d), STOP and report instead of improvising.

## Verification

- **Mechanical**: `cd firetv && ./gradlew -q assembleRelease` succeeds, then `./dev.sh`.
- **Budget check**: run `./check.sh` from `firetv/`. It refuses to run 01:50–02:50 UTC. The "worst frame opening a title" row must stay ≤ 32 ms, and "title page → Plays:" must not get slower. Two screens are drawn at once for about 0.22 s, so this is the real risk. **If the worst frame exceeds 32 ms, stop and report. Don't tune.**
- **Feel check** on the stick (adb: `~/Library/Android/sdk/platform-tools/adb -s 192.168.178.25:5555`, wrapped in `perl -e 'alarm(20); exec @ARGV'`):
  - Open a film from the rows: the page fades in over the browse screen, with **no dip to black** at any point. Record it with `adb shell screenrecord --time-limit 4 /sdcard/t.mp4`, pull it, split it with `ffmpeg -i t.mp4 -vf fps=60 f_%03d.png`, and step through the frames.
  - The browse backdrop already shows the focused title's art, so the backdrop should barely change and mostly the text fades. **This can't be confirmed from code.** If the two backdrops differ visibly (crop or position), say so in the report. Don't change the backdrops.
  - Open "More like this" from a title page: it fades in over the first title page, not over black.
  - Press Back: instant, and focus lands on the card as before.
  - Press OK on a card and then Down within 0.2 s: the new page's buttons respond, and the fade doesn't block input.
  - Play a film, then press Back out of the player: the title page shows at once, with no second fade.
  - Reduced motion: `adb shell settings put global animator_duration_scale 0`, force-stop and reopen; it should be a short 160 ms fade. **Restore with `settings put global animator_duration_scale 1`.**
- **Done when**: opening fades with no black frame, Back is instant, check.sh passes, and the stick is left on HOME.
