package com.mohamed3on.iptv.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The app's look: the video is the hero, and all chrome floats over it as a dark,
 * slightly blue-tinted translucent material. Surfaces are designed for dark from the
 * start (no light theme exists); every text colour clears 4.5:1 on [Palette.material]
 * and the focus accent clears 3:1 on every surface it sits on.
 */
@Immutable
object Palette {
    /** Behind everything when no video is playing (first run, errors). */
    val ink = Color(0xFF0B0E12)
    /** Floating chrome over video: overlay columns, banner, quick panel. */
    val material = Color(0xEB121820)
    /** A lighter layer for the focused row: lifts toward the viewer. */
    val raised = Color(0xFF26303C)
    /** Hairline highlight along a material's top/leading edge, as light catching it. */
    val edge = Color(0x1FFFFFFF)

    val textPrimary = Color(0xFFF4F6F8)
    val textSecondary = Color(0xFFB4BCC6)
    val textTertiary = Color(0xFF8790A0)

    /** Focus ring and progress: one accent, used sparingly so focus always reads first. */
    val accent = Color(0xFF3D9BFF)
    /** "Live" and errors that need attention. */
    val live = Color(0xFFFF5147)
    val warning = Color(0xFFFFB340)
    /** A stream that is playing well (the health dot). */
    val good = Color(0xFF3DD68C)

    /** Progress track behind [accent]. */
    val track = Color(0x33FFFFFF)
}

/**
 * 10-foot type scale on the system font (it already ships optical sizing and tuned
 * tracking), sized for the 960×540 dp canvas: refined, not shouty. Tracking tightens as size
 * grows and opens slightly when small; leading is tight on large text and looser on small.
 * Nothing below 13sp: it's read from a sofa.
 */
@Immutable
object Type {
    private val base = TextStyle(fontFamily = FontFamily.Default, color = Palette.textPrimary)

    /** Channel name in the banner. */
    val display = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em)
    /** Screen and section headings (details, search, error). */
    val title = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    /** Channel row name, current programme in the banner. */
    val headline = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.em)
    /** Now/next lines, schedule rows. */
    val body = base.copy(fontSize = 15.sp, lineHeight = 20.sp, color = Palette.textSecondary, letterSpacing = 0.005.em)
    /** Times, badges, hints. */
    val caption = base.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = Palette.textTertiary, letterSpacing = 0.015.em)
}

/**
 * Tabular figures, for numbers that change in place or line up in a column, so they never jiggle.
 * Only there: a font feature makes text layout several times slower on the stick, which a list of
 * rows feels when it builds (measured 2026-11-02).
 */
val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

/** Spacing steps; pick from these, never ad-hoc values. */
@Immutable
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    /** TV overscan-safe inset (5% of 960×540dp): chrome never sits closer to the edge. */
    val safe = PaddingValues(horizontal = 48.dp, vertical = 27.dp)
}

/** Corner radii: larger surfaces read as rounder. Nested radius = outer − inset. */
@Immutable
object Radius {
    val chip = 6.dp
    val row = 12.dp
    val panel = 20.dp
}

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun IptvTheme(reducedMotion: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            background = Palette.ink,
            surface = Palette.material,
            onSurface = Palette.textPrimary,
            onBackground = Palette.textPrimary,
            border = Palette.accent,
        ),
    ) {
        CompositionLocalProvider(
            LocalReducedMotion provides reducedMotion,
            LocalTextMeasurer provides rememberTextMeasurer(cacheSize = 32),
            content = content,
        )
    }
}
