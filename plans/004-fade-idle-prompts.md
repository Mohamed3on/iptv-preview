# 004 — Fade "Still watching?" and the idle stop screen in and out

- **Status**: DONE (built; not seen on the stick, the prompt needs 4 idle hours)
- **Commit**: 8ad9d6d
- **Severity**: LOW
- **Category**: Missed opportunities
- **Estimated scope**: 1 file (`MainActivity.kt`), ~10 lines

All paths below are relative to `firetv/app/src/main/java/com/mohamed3on/iptv/`.

## Problem

After 4 hours with no key presses, a full-screen scrim and a card appear over the picture in one frame. They vanish the same way when you answer. The screen that replaces them once playback stops ("Stopped after 4 hours…") also appears in one frame. These are rare moments, which is exactly where a fade is welcome. The problem card right beside them already fades.

```kotlin
// MainActivity.kt:313 — Root, current
when (idle) {
    Idle.Asking -> StillWatching()
    Idle.TimedOut -> IdleStopped()
    else -> {}
}
```

Exemplar, the problem card (`ui/PlayerScreen.kt:386`):

```kotlin
AnimatedContent(
    targetState = problem.takeIf { layer != Layer.ChannelList },
    transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
    modifier = Modifier.fillMaxSize(),
    label = "problem",
) { shown ->
    if (shown != null) ProblemCard(...)
}
```

## Target

```kotlin
// MainActivity.kt:313 — target
AnimatedContent(
    targetState = idle?.takeIf { it != Idle.Watching },
    transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
    modifier = Modifier.fillMaxSize(),
    label = "idle",
) { shown ->
    when (shown) {
        Idle.Asking -> StillWatching()
        Idle.TimedOut -> IdleStopped()
        else -> {}
    }
}
```

`Motion.panel()` is the shared panel spring: no bounce, about 0.35 s response (`ui/theme/Motion.kt:47`). It's opacity only, so reduced motion needs nothing more, the same as the problem card.

## Repo conventions to follow

- Copy the problem card at `ui/PlayerScreen.kt:386` exactly: same spec, same `fillMaxSize`.
- `Motion` is already imported in `MainActivity.kt:76`.

## Steps

1. **`MainActivity.kt:313-317`.** Replace the `when (idle) { ... }` block with the target above.
2. Add the imports `androidx.compose.animation.AnimatedContent`, `androidx.compose.animation.fadeIn`, `androidx.compose.animation.fadeOut`, and `androidx.compose.animation.togetherWith`.

## Boundaries

- Do NOT touch `dispatchKeyEvent`, `IdleWatch`, `StillWatching`, or `IdleStopped`. Key handling stays in the activity, where any key answers the prompt.
- Do NOT add a slide or scale. The card is centered, like a modal.
- If a quoted excerpt doesn't match the code you find (it may have drifted since commit 8ad9d6d), STOP and report instead of improvising.

## Verification

- **Mechanical**: `cd firetv && ./gradlew -q assembleRelease` succeeds, then `./dev.sh`.
- **Feel check**: four hours idle can't be waited out. Look for a debug hook or a constant for the idle timeout in `player/IdleWatch.kt`. If none exists, don't add one: confirm the change by reading the code, and say in the report that the prompt wasn't triggered on the stick.
  - If you can trigger it: the scrim and card fade in over the playing picture, and any key fades them out while the channel keeps playing. On the timeout, the prompt crossfades into "Stopped after 4 hours…".
- **Done when**: it builds, the code matches the target, and the report says whether the fade was seen on the stick.
