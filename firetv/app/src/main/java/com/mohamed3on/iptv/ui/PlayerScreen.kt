package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.diag.DiagnosticsScreen
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.player.AudioTracks
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.SessionState
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.Badge
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Scrim
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.bottomScrim
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val BANNER_MS = 4_000L
private val LOGO_HEIGHT = 72.dp
private val TUNING_SIZE = 16.dp
private val PROGRESS_WIDTH = 200.dp
private val CARD_WIDTH = 560.dp

/**
 * The full-screen player (R10, R14): the video, with chrome only when asked for. The channel
 * banner rises on launch, on every zap and on OK, and sinks back 4s later; a stream
 * that can't recover gets a card saying why. The remote follows [keyAction]; [session] is null
 * until the activity has started its player (the video surface is shown regardless).
 */
@Composable
fun PlayerScreen(feed: Feed, session: StreamSession?, audio: AudioTracks?, surface: SurfaceView) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        if (session != null) PlayerChrome(feed, session, audio)
    }
}

@Composable
private fun PlayerChrome(feed: Feed, session: StreamSession, audio: AudioTracks?) {
    val graph = LocalContext.current.graph
    val reduced = LocalReducedMotion.current
    val state by session.state.collectAsState()
    val guide by graph.sync.guide.collectAsState()
    val clock = rememberMinuteClock()
    var layer by remember { mutableStateOf(Layer.None) }
    // The last zap's direction, for the banner's motion; null for a jump (Back to the previous channel).
    var down by remember { mutableStateOf<Boolean?>(true) }
    var bannerAsks by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    // Until the debounced tune starts, the feed playing is still the previous channel's (KTD9).
    val tuning = state.phase == Phase.Connecting || state.feed?.family != state.requested?.family
    val problem = problemOf(state).takeIf { state.feed?.family == state.requested?.family }

    var banner by remember { mutableStateOf(true) }
    LaunchedEffect(bannerAsks, state.requested?.identity, tuning) {
        banner = true
        if (!tuning) {
            delay(BANNER_MS)
            banner = false
        }
    }
    // Focus rests on the player itself whenever nothing else holds it, so the remote reaches keyAction.
    LaunchedEffect(layer, problem?.button) {
        if (layer == Layer.None && problem?.button == null) focus.requestFocus()
    }

    fun perform(action: KeyAction) {
        when (action) {
            is KeyAction.Zap -> {
                down = action.down
                session.zap(action.to, feed)
            }
            is KeyAction.Previous -> {
                down = null
                session.zap(action.to, feed)
            }
            // U9: open the channel list focused on action.at (layer = Layer.ChannelList); until then OK shows the banner.
            is KeyAction.OpenOverlay -> bannerAsks++
            KeyAction.OpenPanel -> layer = Layer.QuickPanel
            KeyAction.Close -> layer = Layer.None
            KeyAction.Ignore, KeyAction.Pass -> {}
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Bubbles up from whatever is focused: an open layer's own keys never get here as player keys.
            .onKeyEvent { event ->
                val key = remoteKey(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
                val action = keyAction(key, PlayerKeys(state.requested, graph.history.last, graph.history.previous, layer), feed)
                if (action == KeyAction.Pass) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) perform(action)
                true // the key-up too, so Back never reaches the activity and leaves the app
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        AnimatedVisibility(
            visible = banner && layer == Layer.None && state.requested != null,
            enter = fadeIn(Motion.panel()),
            exit = fadeOut(Motion.panel()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // The scrim fades; the banner itself rises from the bottom and sinks back.
            Box(Modifier.fillMaxWidth().bottomScrim().padding(top = Space.xxxl * 2)) {
                AnimatedContent(
                    targetState = state.requested,
                    contentKey = { it?.identity },
                    transitionSpec = {
                        val d = down
                        Motion.zapIn(d ?: true, reduced || d == null) togetherWith
                            Motion.zapOut(d ?: true, reduced || d == null) using SizeTransform(clip = false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateEnterExit(enter = Motion.fromBottom(reduced), exit = Motion.toBottom(reduced))
                        .padding(Space.safe),
                    label = "banner",
                ) { channel ->
                    if (channel != null) Banner(channel, feed, guide, clock, tuning && channel.identity == state.requested?.identity)
                }
            }
        }

        AnimatedContent(
            targetState = problem,
            transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
            modifier = Modifier.fillMaxSize(),
            label = "problem",
        ) { shown ->
            if (shown != null) ProblemCard(shown, takeFocus = layer == Layer.None) {
                if (shown.switchesRoute) session.switchRoute() else session.retry()
            }
        }

        AnimatedVisibility(
            visible = layer == Layer.QuickPanel,
            enter = Motion.fromRight(reduced),
            exit = Motion.toRight(reduced),
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
        ) {
            val audioState = audio?.state?.collectAsState()?.value ?: AudioState()
            val choice by graph.route.choice.collectAsState()
            val effective by graph.route.effective.collectAsState()
            QuickPanel(
                audio = audioState,
                onAudio = { audio?.select(it) },
                choice = choice,
                effective = effective,
                // Route.effective catches up a moment later, so tune on the route the choice resolves to now.
                onRoute = { session.useRoute(graph.route.choose(it)) },
                onDiagnostics = { layer = Layer.Diagnostics },
            )
        }

        if (layer == Layer.Diagnostics) {
            val diagnostics = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos {} // its list items exist once laid out
                runCatching { diagnostics.requestFocus() }
            }
            Box(Modifier.fillMaxSize().focusRequester(diagnostics).focusGroup()) { DiagnosticsScreen() }
        }
    }
}

/** Logo, name and badges, now and next, and where the channel sits in its group. */
@Composable
private fun Banner(channel: Channel, feed: Feed, guide: GuideDb?, clock: State<Long>, tuning: Boolean) {
    val group = feed.channelsIn(channel.group)
    val position = group.indexOfFirst { it.identity == channel.identity }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
        if (channel.logo.isNotEmpty()) {
            Box(
                Modifier
                    .height(LOGO_HEIGHT)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(Radius.row))
                    .background(Palette.raised)
                    .padding(Space.s),
            ) {
                AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Text(remember(channel.name) { displayName(channel.name) }, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                channel.badges.forEach { Badge(it) }
                if (tuning) Tuning()
            }
            Guide(channel, guide, clock)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(channel.group, style = Type.caption, maxLines = 1)
            if (position >= 0) Text("${position + 1} of ${group.size}", style = Type.caption)
        }
    }
}

/** Now and next. Synthetic rows (name-as-title filler) show nothing: event slot names outrun the guide. */
@Composable
private fun Guide(channel: Channel, guide: GuideDb?, clock: State<Long>) {
    // Re-read each minute; recomposes only when the programmes themselves change.
    val nowNext by remember(channel.tvgId, guide) {
        derivedStateOf {
            // The guide can be swapped (and the old file closed) mid-read after a download.
            guide?.takeIf { channel.tvgId.isNotEmpty() }?.let { runCatching { it.nowNext(channel.tvgId, clock.value) }.getOrNull() }
        }
    }
    val (now, next) = nowNext ?: NowNext(null, null)
    if (now == null && next == null) {
        Text("No guide", style = Type.body)
        return
    }
    if (now?.synthetic == true) return
    if (now != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (now.match) LiveMark()
            Text(now.title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text("${clockTime(now.start)} – ${clockTime(now.stop)}", style = Type.caption)
            ProgressLine(
                fraction = { (clock.value - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1) },
                modifier = Modifier.widthIn(max = PROGRESS_WIDTH),
            )
        }
    }
    if (next != null && !next.synthetic) {
        Text("Next · ${clockTime(next.start)} ${next.title}", style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small, calm sign that the channel is still tuning; the old picture stays until the new one arrives. */
@Composable
private fun Tuning() {
    val reduced = LocalReducedMotion.current
    val angle = rememberInfiniteTransition(label = "tuning")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(TUNING_SIZE)) {
        val stroke = 2.dp.toPx()
        rotate(if (reduced) 0f else angle.value) {
            drawArc(
                Palette.textTertiary, startAngle = -90f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** What an error card says (R14, KTD8), with at most one [button]: retry, or the route switch. */
private data class Problem(val title: String, val body: String, val button: String? = null, val switchesRoute: Boolean = false)

private fun problemOf(state: SessionState): Problem? = when (val phase = state.phase) {
    Phase.Busy -> Problem("Your account is in use", "Another device is playing. Trying again every 5 seconds.")
    Phase.GaveUp -> Problem("Your account is in use", "Still in use after 10 minutes.", "Try again")
    is Phase.WrongRoute -> {
        val route = if (phase.switchTo == RouteKind.Vpn) "VPN route" else "plain route"
        Problem("Wrong connection for your VPN", "This stream needs the $route.", "Switch to $route", switchesRoute = true)
    }
    Phase.Offline -> Problem("Channel unavailable", "None of this channel's feeds are responding.", "Try again")
    Phase.Stopped, Phase.Connecting, Phase.Playing -> null
}

@Composable
private fun ProblemCard(problem: Problem, takeFocus: Boolean, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = CARD_WIDTH).material().padding(Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(problem.title, style = Type.title)
            Text(problem.body, style = Type.body)
            val label = problem.button ?: return@Column
            val button = remember { FocusRequester() }
            LaunchedEffect(takeFocus) { if (takeFocus) button.requestFocus() }
            Text(
                label,
                style = Type.headline,
                modifier = Modifier
                    .padding(top = Space.m)
                    .focusRequester(button)
                    .pressable(onAction)
                    .padding(horizontal = Space.xl, vertical = Space.m),
            )
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun clockTime(epochSec: Long) = timeFormat.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/** Epoch seconds, ticking on each minute: the one clock behind every progress line. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis() / 1000
    }
}
