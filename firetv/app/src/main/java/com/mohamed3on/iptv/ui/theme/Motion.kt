package com.mohamed3on.iptv.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI

/**
 * Motion is springs, described the way Apple does: a damping ratio (1 = no overshoot)
 * and a response in seconds (how quickly it gets there; not a duration). Springs start
 * from wherever the value is on screen, so a key pressed mid-animation redirects it
 * smoothly instead of waiting. Nothing here bounces: on a remote no gesture carries
 * momentum, so overshoot would only distract from the picture.
 *
 * Every transition leaves the way it came ([slideFade]): the channel list slides in from the left
 * and out to the left, the banner rises from the bottom and sinks back. Panels move without
 * offscreen layers, so the stick's GPU draws each frame of a transition in one pass.
 */
object Motion {
    /** Stiffness for a unit-mass spring that settles in about [response] seconds. */
    private fun stiffness(response: Float) = (2 * PI / response).let { (it * it).toFloat() }

    fun <T> spring(response: Float = 0.35f): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness(response))

    /** Focus lift and press feedback: quick, so it lands on the same beat as the key. */
    fun <T> focus(): FiniteAnimationSpec<T> = spring(0.22f)
    /** Panels and overlays. */
    fun <T> panel(): FiniteAnimationSpec<T> = spring(0.35f)

    /** Focused rows and tiles grow this much toward the viewer. */
    const val FOCUS_SCALE = 1.03f
    /** Reduced-motion cross-fade. */
    internal const val FADE_MS = 160

    private fun <T> fade(): FiniteAnimationSpec<T> = tween(FADE_MS)

    /**
     * Banner content on a zap moves in the direction of travel: zapping down brings the
     * next channel up from below, so the motion hints where you are in the list.
     */
    fun zapIn(down: Boolean, reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(fade())
        else slideInVertically(spring<IntOffset>(0.3f)) { if (down) it / 3 else -it / 3 } + fadeIn(spring(0.3f))

    fun zapOut(down: Boolean, reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(fade())
        else slideOutVertically(spring<IntOffset>(0.3f)) { if (down) -it / 3 else it / 3 } + fadeOut(spring(0.2f))

    /** Android's "Remove animations" (Accessibility) sets the animator scale to 0. */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Where a panel comes in from, and leaves back to. */
enum class Edge { Left, Right, Bottom }

/**
 * An AnimatedVisibility's own enter/exit as a number, 0 hidden → 1 shown, on the panel spring (a
 * short fade's timing with reduced motion). Pair it with `enter = EnterTransition.None, exit =
 * ExitTransition.None`: the content stays until this settles.
 */
@Composable
fun AnimatedVisibilityScope.reveal(): State<Float> {
    val reduced = LocalReducedMotion.current
    return transition.animateFloat(
        transitionSpec = { if (reduced) tween(Motion.FADE_MS) else Motion.panel() },
        label = "reveal",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
}

/**
 * Slides in from [from] by a third of its size and fades in as [progress] goes 0 → 1, and back out
 * the same way. The alpha is applied to each draw rather than through an offscreen copy, which the
 * stick's GPU can't afford for a full-height column every frame. Reduced motion only fades.
 */
fun Modifier.slideFade(progress: () -> Float, from: Edge, reduced: Boolean): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    alpha = p
    compositingStrategy = CompositingStrategy.ModulateAlpha
    if (!reduced) when (from) {
        Edge.Left -> translationX = -(1f - p) * size.width / 3f
        Edge.Right -> translationX = (1f - p) * size.width / 3f
        Edge.Bottom -> translationY = (1f - p) * size.height / 2f
    }
}

/** [progress] delayed by [lag] of its course: a second column that follows the first in, and leaves before it. */
fun staggered(progress: Float, lag: Float = 0.15f): Float = ((progress - lag) / (1f - lag)).coerceIn(0f, 1f)
