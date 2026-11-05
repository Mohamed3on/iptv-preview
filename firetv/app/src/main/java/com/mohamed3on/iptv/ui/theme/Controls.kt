package com.mohamed3on.iptv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.R

/**
 * The one button style: a label that lifts on focus and presses in to 0.96 on OK, so the
 * press is felt on the same frame as the key. Weight never changes with state.
 */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.focus(), label = "press")
    Text(
        label,
        style = Type.headline,
        modifier = modifier
            .graphicsLayer { scaleX = press; scaleY = press }
            .focusLift(focused)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.m),
    )
}

/** True inside the focused row or card: the [FitText] lines in it scroll. */
val LocalFocused = compositionLocalOf { false }

/**
 * One line that never ends in "…". Past its edge it clips behind a short fade; while [active] (its row or
 * card has focus, or a fixed slot on a page at rest) it scrolls the rest into view after a second's pause,
 * and starts over from the beginning when focus leaves. Only a line that overflows draws the fade, in a
 * layer the size of the line; with reduced motion it never scrolls.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, active: Boolean = LocalFocused.current) {
    val reduced = LocalReducedMotion.current
    val width = remember { mutableFloatStateOf(0f) }
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { width.floatValue = it.multiParagraph.width },
        modifier = modifier
            .fadeEnd(width)
            .then(if (active && !reduced) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000, velocity = 30.dp) else Modifier),
    )
}

private val FADE = 24.dp

/**
 * Lines that never end in "…": a slot [lines] high (at least [minLines]); text that runs past it scrolls up slowly once
 * it has rested 1.5 s, pauses at the end, and starts over from the top when the text changes, clipped under a soft fade
 * at the bottom. With reduced motion the slot grows to hold all of it.
 */
@Composable
fun ScrollText(text: String, style: TextStyle, lines: Int, modifier: Modifier = Modifier, minLines: Int = 1, color: Color = Color.Unspecified) {
    if (LocalReducedMotion.current) return Text(text, style = style, color = color, minLines = minLines, modifier = modifier)
    val slot = with(LocalDensity.current) { style.lineHeight.toDp() } * lines
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(0)
        while (true) {
            delay(1_500)
            val end = scroll.maxValue.takeIf { it in 1 until Int.MAX_VALUE } ?: return@LaunchedEffect
            scroll.animateScrollTo(end, tween((end * SCROLL_MS_PER_PX).toInt(), easing = LinearEasing))
            delay(2_000)
            scroll.scrollTo(0)
        }
    }
    Box(modifier.heightIn(max = slot).fadeBottom(scroll).verticalScroll(scroll, enabled = false)) {
        Text(text, style = style, color = color, minLines = minLines)
    }
}

/** About 25 px a second: slow enough to read along. */
private const val SCROLL_MS_PER_PX = 40f

/** Fades the bottom [FADE] of a slot while more text waits below it. */
private fun Modifier.fadeBottom(scroll: ScrollState): Modifier = graphicsLayer {
    compositingStrategy = if (scroll.maxValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithCache {
    val fade = FADE.toPx().coerceAtMost(size.height)
    val from = size.height - fade
    val brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = from, endY = size.height)
    onDrawWithContent {
        drawContent()
        if (scroll.value < scroll.maxValue) drawRect(brush, Offset(0f, from), Size(size.width, fade), blendMode = BlendMode.DstIn)
    }
}

/** Fades the last [FADE] of a line whose text ([textWidth] wide) runs past it. */
private fun Modifier.fadeEnd(textWidth: FloatState): Modifier = graphicsLayer {
    compositingStrategy = if (textWidth.floatValue > size.width) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithCache {
    val fade = FADE.toPx().coerceAtMost(size.width)
    val from = size.width - fade
    val brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = from, endX = size.width)
    onDrawWithContent {
        drawContent()
        if (textWidth.floatValue > size.width) drawRect(brush, Offset(from, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
    }
}

/**
 * Quiet indeterminate progress: a short accent segment gliding along a hairline track.
 * With reduced motion the segment stays put and breathes instead.
 */
@Composable
fun ActivityLine(modifier: Modifier = Modifier, width: Dp = 160.dp) {
    val reduced = LocalReducedMotion.current
    val t by rememberInfiniteTransition(label = "activity").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (reduced) 1600 else 1100, easing = LinearEasing), if (reduced) RepeatMode.Reverse else RepeatMode.Restart),
        label = "t",
    )
    Box(
        modifier
            .size(width, 3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.track)
            .drawWithContent {
                val seg = size.width * 0.3f
                if (reduced) drawRect(Palette.accent.copy(alpha = 0.35f + 0.5f * t), size = size)
                else drawRect(
                    Palette.accent,
                    topLeft = androidx.compose.ui.geometry.Offset(-seg + (size.width + seg) * t, 0f),
                    size = size.copy(width = seg),
                )
            },
    )
}

/** The app's mark (screen with the accent play triangle), as on the launcher banner. */
@Composable
fun Mark(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = modifier.size(size, size * 0.86f))
}
