package com.mohamed3on.iptv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/*
 * Materials and depth. API 30 has no blur, and nothing can blur a video SurfaceView, so the
 * "glass" is a tinted translucent layer plus gradient scrims that darken the picture only
 * where chrome sits. Bigger surfaces read heavier (more opaque, rounder); the focused row is
 * the lightest layer, so it reads as closest. Never stack one translucent surface on another.
 */

/** A floating chrome panel: translucent material with a faint lit edge. */
fun Modifier.material(radius: Dp = Radius.panel): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(Palette.material).border(1.dp, Palette.edge, shape)
}

/** Darkens the video behind the channel list, strongest at the left edge where the list is. */
fun Modifier.leftScrim(): Modifier = background(
    Brush.horizontalGradient(0f to Color(0xD9000000), 0.55f to Color(0x8C000000), 1f to Color.Transparent),
)

/** Darkens the video behind the banner, from the bottom edge up. */
fun Modifier.bottomScrim(): Modifier = background(
    Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x80000000), 1f to Color(0xE6000000)),
)

/** Dims everything behind a modal surface (quick panel, still-watching prompt). */
val Scrim = Color(0x99000000)

/**
 * Focus as depth: the focused item lifts toward the viewer (scale + the raised surface),
 * with an accent ring at ≥3:1 contrast. It springs on the same beat as the key press and
 * redirects smoothly when focus moves on mid-animation.
 */
@Composable
fun Modifier.focusLift(focused: Boolean, radius: Dp = Radius.row): Modifier {
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (focused && !reduced) Motion.FOCUS_SCALE else 1f, Motion.focus(), label = "lift")
    val fill by animateColorAsState(if (focused) Palette.raised else Color.Transparent, Motion.focus(), label = "fill")
    val ring by animateColorAsState(if (focused) Palette.accent else Color.Transparent, Motion.focus(), label = "ring")
    val shape = RoundedCornerShape(radius)
    return graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape).background(fill).border(2.dp, ring, shape)
}

/** A quality badge (4K, FHD, 50fps, Atmos): an outlined capsule, quiet next to the name. */
@Composable
fun Badge(label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.chip)
    Text(
        label,
        style = Type.caption.copy(color = Palette.textSecondary),
        modifier = modifier.border(1.dp, Palette.textTertiary.copy(alpha = 0.6f), shape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "LIVE" marker for programmes airing now. */
@Composable
fun LiveMark(modifier: Modifier = Modifier) {
    Text(
        "LIVE",
        style = Type.caption.copy(color = Color.White),
        modifier = modifier.clip(RoundedCornerShape(Radius.chip)).background(Palette.live).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Programme progress. [fraction] is read inside the draw phase, so the shared minute ticker
 * repaints only this bar, never recomposes the row.
 */
@Composable
fun ProgressLine(fraction: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.track)
            .drawWithContent {
                drawRect(Palette.accent, size = size.copy(width = size.width * fraction().coerceIn(0f, 1f)))
            },
    )
}

/**
 * Scroll edge effect for lists under floating chrome: content fades out at the top and
 * bottom edges instead of meeting a hard divider. [top]/[bottom] are the fade heights.
 */
fun Modifier.edgeFade(top: Dp = 24.dp, bottom: Dp = 48.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, (top.toPx() / h) to Color.Black,
                (1f - bottom.toPx() / h) to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
