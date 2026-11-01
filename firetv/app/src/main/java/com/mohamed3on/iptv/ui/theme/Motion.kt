package com.mohamed3on.iptv.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI

/**
 * Motion is springs, described the way Apple does: a damping ratio (1 = no overshoot)
 * and a response in seconds (how quickly it gets there; not a duration). Springs start
 * from wherever the value is on screen, so a key pressed mid-animation redirects it
 * smoothly instead of waiting. Nothing here bounces: on a remote no gesture carries
 * momentum, so overshoot would only distract from the picture.
 *
 * Every transition leaves the way it came: the channel list slides in from the left and
 * out to the left, the banner rises from the bottom and sinks back.
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
    private const val FADE_MS = 160

    private fun <T> fade(): FiniteAnimationSpec<T> = tween(FADE_MS)

    fun fromLeft(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(fade())
        else slideInHorizontally(panel<IntOffset>()) { -it / 3 } + fadeIn(panel())

    fun toLeft(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(fade())
        else slideOutHorizontally(panel<IntOffset>()) { -it / 3 } + fadeOut(panel())

    fun fromBottom(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(fade())
        else slideInVertically(panel<IntOffset>()) { it / 2 } + fadeIn(panel())

    fun toBottom(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(fade())
        else slideOutVertically(panel<IntOffset>()) { it / 2 } + fadeOut(panel())

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
