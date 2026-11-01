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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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
