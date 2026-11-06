# 001 — Slide the Versions and ☰ panels in from the right, like every other side panel

- **Status**: DONE
- **Commit**: 8ad9d6d
- **Severity**: MEDIUM
- **Category**: Missed opportunities / Cohesion
- **Estimated scope**: 3 files (`ui/Movies.kt`, `ui/Versions.kt`, `ui/Marks.kt`), ~40 lines

All paths below are relative to `firetv/app/src/main/java/com/mohamed3on/iptv/`.

## Problem

Every panel at the right edge of the live screen and the film player slides in from the right and back out on a spring. In Movies, the Versions panel and the ☰ (marks) panel appear and disappear in one frame. The same app shows the same kind of panel two different ways.

Exemplar that already does it right: `ui/PlayerScreen.kt:540` (Options):

```kotlin
// ui/PlayerScreen.kt:540 — current, correct
AnimatedVisibility(
    visible = layer == Layer.QuickPanel,
    enter = EnterTransition.None,
    exit = ExitTransition.None,
    modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
) {
    val shown = reveal()
    ...
    QuickPanel(
        modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
        open = layer == Layer.QuickPanel,
        ...
```

The five places that teleport today:

```kotlin
// ui/Movies.kt:1871 — FilmPage, current
if (versions) VersionsPanel(
    film.titleId,
    onPlay = { onPlay(false) },
    onClose = {
        closedVersions[0] = true
        versions = false
    },
    modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
)
if (marking) TitleActions(film, onClose = { marking = false }, modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe))
```

```kotlin
// ui/Movies.kt:2280 — ShowPage, current
versionsFor?.let { i ->
    VersionsPanel(
        episodes!![i].titleId,
        onPlay = {
            lastPlayed = i
            onPlay(episodes, i, false)
        },
        onClose = { versionsFor = null },
        modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
    )
}
marksFor?.let { i ->
    val close = { marksFor = null }
    val at = Modifier.align(Alignment.CenterEnd).padding(Space.safe)
    if (i < 0) {
        ... MarksPanel(..., onClose = close, modifier = at)
    } else {
        ... MarksPanel(..., onClose = close, modifier = at)
    }
}
```

```kotlin
// ui/Movies.kt:803 — MoviesBrowse, current
actions?.let { a ->
    TitleActions(
        a.movie,
        onClose = { actions = null; /* refocus after one frame */ ... },
        ...
        modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
    )
}
```

**A trap to avoid.** Both panels keep focus inside themselves for good:

```kotlin
// ui/Versions.kt:232 — current
.focusProperties { onExit = { cancelFocusChange() } }
// ui/Marks.kt:69 — current
.focusProperties { onExit = { cancelFocusChange() } }
```

Once the panels animate out, they stay on screen for about 0.35 s after closing. During that time this line would block the page from taking focus back. The page tries to refocus at once: FilmPage's `LaunchedEffect` at `ui/Movies.kt:1811` refocuses the Versions button, and the browse `onClose` above refocuses after one frame. QuickPanel already solves this with an `open` flag (`ui/QuickPanel.kt:91` and `:118`):

```kotlin
val stay by rememberUpdatedState(open)
...
.focusProperties { onExit = { if (stay) cancelFocusChange() } }
```

## Target

- Every Versions and ☰ panel in Movies enters from the right by a third of its width while fading in, and leaves the same way. It uses the panel spring `Motion.panel()`: no bounce, about 0.35 s response. This is exactly what `slideFade(..., Edge.Right, reduced)` with `reveal()` does.
- With reduced motion on, `slideFade` only fades and `reveal()` uses `tween(Motion.FADE_MS)` (160 ms). Nothing extra is needed.
- A closing panel lets focus go. Focus lands back where it does today, with no extra frames.
- While a panel leaves, it keeps showing what it showed (the same episode, the same title).

## Repo conventions to follow

- Motion primitives live in `ui/theme/Motion.kt`: `reveal()`, `slideFade()`, `Edge`, `Motion.panel()`. Don't add new springs or durations.
- Panels carry an `open: Boolean = true` parameter that only gates the focus trap. The exemplar is `QuickPanel` (`ui/QuickPanel.kt:88`, `:91`, `:118`).
- The `reduced` flag comes from `LocalReducedMotion.current` (already imported in `ui/Movies.kt:170`).

## Steps

1. **`ui/Versions.kt`, `VersionsPanel` (line 213).** Add a last parameter `open: Boolean = true`. Add `val stay by rememberUpdatedState(open)` at the top of the body. Change line 232 to `.focusProperties { onExit = { if (stay) cancelFocusChange() } }`. Import `androidx.compose.runtime.rememberUpdatedState` and `getValue` if they're missing.
2. **`ui/Marks.kt`, `MarksPanel` (line 47).** Make the same three changes: add `open: Boolean = true` as the last parameter, add `val stay by rememberUpdatedState(open)`, and change line 69 to `if (stay) cancelFocusChange()`.
3. **`ui/Movies.kt`, `TitleActions` (line 833).** Add an `open: Boolean = true` parameter and pass `open = open` to `MarksPanel`.
4. **`ui/Movies.kt`: add this helper** directly above `TitleFrame` (line ~1916):

   ```kotlin
   /**
    * A panel at the right edge that slides in from the right and back out to it, as Options and Recent do on the
    * live screen. A non-null [value] shows it; while it leaves, [content] keeps the last value. [content] gets
    * whether the panel is still open (pass it on, so focus can leave a closing panel) and the modifier for the panel.
    */
   @Composable
   private fun <T : Any> BoxScope.SlidePanel(value: T?, content: @Composable (value: T, open: Boolean, modifier: Modifier) -> Unit) {
       val reduced = LocalReducedMotion.current
       var last by remember { mutableStateOf(value) }
       SideEffect { if (value != null) last = value }
       AnimatedVisibility(
           visible = value != null,
           enter = EnterTransition.None,
           exit = ExitTransition.None,
           modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
       ) {
           val shown = reveal()
           (value ?: last)?.let { content(it, value != null, Modifier.slideFade({ shown.value }, Edge.Right, reduced)) }
       }
   }
   ```

   Add these imports: `androidx.compose.animation.AnimatedVisibility`, `androidx.compose.animation.EnterTransition`, `androidx.compose.animation.ExitTransition`, `androidx.compose.runtime.SideEffect`, and `com.mohamed3on.iptv.ui.theme.reveal`. Skip any that already exist.
5. **FilmPage (`ui/Movies.kt:1871-1880`).** Replace both lines with:

   ```kotlin
   SlidePanel(Unit.takeIf { versions }) { _, open, m ->
       VersionsPanel(
           film.titleId,
           onPlay = { onPlay(false) },
           onClose = {
               closedVersions[0] = true
               versions = false
           },
           modifier = m,
           open = open,
       )
   }
   SlidePanel(Unit.takeIf { marking }) { _, open, m -> TitleActions(film, onClose = { marking = false }, modifier = m, open = open) }
   ```
6. **ShowPage (`ui/Movies.kt:2280` onward).** Change `versionsFor?.let { i -> VersionsPanel(... modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe)) }` to `SlidePanel(versionsFor) { i, open, m -> VersionsPanel(... modifier = m, open = open) }`, keeping the other arguments. Change `marksFor?.let { i ->` to `SlidePanel(marksFor) { i, open, m ->`. Inside it, change `val at = Modifier.align(Alignment.CenterEnd).padding(Space.safe)` to `val at = m`, and add `open = open` to **both** `MarksPanel(...)` calls.
7. **MoviesBrowse (`ui/Movies.kt:803`).** Change `actions?.let { a -> TitleActions(...) }` to `SlidePanel(actions) { a, open, m -> TitleActions(..., modifier = m, open = open) }`. Keep every other argument, including the `onClose` refocus code, exactly as it is.

## Boundaries

- Do NOT touch `PlayerScreen.kt`, `QuickPanel.kt`, `MoviePlayback.kt`, or `Motion.kt`.
- Do NOT change what the panels contain, their width, their key handling, or any focus target. Motion and the `open` gating only.
- Do NOT add dependencies, new springs, or new durations.
- If a quoted excerpt doesn't match the code you find (it may have drifted since commit 8ad9d6d), STOP and report instead of improvising.

## Verification

- **Mechanical**: `cd firetv && ./gradlew -q assembleRelease` succeeds. Then `./dev.sh` installs the build on the stick with its profile.
- **Feel check** on the stick. Use adb at `~/Library/Android/sdk/platform-tools/adb -s 192.168.178.25:5555` and wrap each call in `perl -e 'alarm(20); exec @ARGV'`. Don't stream between 01:50 and 02:50 UTC.
  - On a film page, press Versions: the panel slides in from the right. Press Back: it slides back out to the right, and the **Versions button is focused** as soon as you press Back. A press of Down right after Back must move on the page, not get stuck.
  - On a film page, press ☰ and then Back: same as above.
  - On a browse card, press ☰ and then Back: focus is back on the same card.
  - On a show, press ☰ on an episode and choose "Versions": the marks panel slides out while Versions slides in at the same spot. Watch for a jarring overlap. **Feel can't be judged from code here.** If the crossing looks busy, report it rather than retuning anything.
  - Press Back while the panel is still sliding in: it reverses smoothly from where it is, with no jump.
  - Record and step through frames: `adb shell screenrecord --time-limit 4 /sdcard/p.mp4`, `adb pull`, then `ffmpeg -i p.mp4 -vf fps=60 f_%03d.png`. While the panel leaves, it should show the same title or episode in every frame.
  - Reduced motion: `adb shell settings put global animator_duration_scale 0`, force-stop and reopen the app. Panels now only fade, with no slide. **Restore with `settings put global animator_duration_scale 1`.**
  - Frame cost: `adb shell dumpsys gfxinfo com.mohamed3on.iptv reset`, open and close Versions three times, then `dumpsys gfxinfo com.mohamed3on.iptv`. No frame should take over 32 ms.
- **Done when**: all five places slide in and out, focus returns as before, reduced motion only fades, and there are no frames over 32 ms. Leave the stick on HOME.
