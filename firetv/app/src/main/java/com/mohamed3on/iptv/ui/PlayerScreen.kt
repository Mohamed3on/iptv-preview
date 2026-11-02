package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.ChannelIdentity
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.player.LiveQuality
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.reveal
import com.mohamed3on.iptv.ui.theme.slideFade
import com.mohamed3on.iptv.update.updateRow
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.diag.DiagnosticsScreen
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.player.AudioTracks
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.SessionState
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Scrim
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.tabular
import com.mohamed3on.iptv.ui.theme.bottomScrim
import com.mohamed3on.iptv.ui.theme.leftScrim
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val BANNER_MS = 4_000L
private val LOGO_SIZE = DpSize(128.dp, 72.dp)
private val TUNING_SIZE = 16.dp
private val PROGRESS_WIDTH = 200.dp
private val CARD_WIDTH = 560.dp

/**
 * The full-screen player (R10, R14): the video, with chrome only when asked for. The channel
 * banner rises on launch and on every zap, and sinks back 4s later; OK opens the channel list (Left, on
 * its groups), Right the recent channels, and ⏩/⏪ step through Live now; a stream
 * that can't recover gets a card saying why. The remote follows [keyAction]; [session] is null
 * until the activity has started its player (the video surface is shown regardless). While
 * [tiles] has any, they replace the full-screen video (U11).
 */
@Composable
fun PlayerScreen(feed: Feed, session: StreamSession?, audio: AudioTracks?, surface: SurfaceView, tiles: MultiView<ExoStreamPlayer>, live: Pair<Int, LiveQuality>?) {
    val grid by tiles.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Gone, not just stopped, in multi-view: its last frame would fight the tiles' surfaces.
        if (grid.tiles.isEmpty()) AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        if (session != null) PlayerChrome(feed, session, audio, tiles, grid, live)
    }
}

/** What the channel list was opened to pick, when not a channel to watch. */
private enum class Pick { MultiView, AddTile, ReplaceTile }

@Composable
private fun PlayerChrome(feed: Feed, session: StreamSession, audio: AudioTracks?, tiles: MultiView<ExoStreamPlayer>, grid: MultiViewState<ExoStreamPlayer>, live: Pair<Int, LiveQuality>?) {
    val graph = LocalContext.current.graph
    val reduced = LocalReducedMotion.current
    val multiView = grid.tiles.isNotEmpty()
    var pick by remember { mutableStateOf<Pick?>(null) }
    val state by session.state.collectAsState()
    val guide by graph.sync.guide.collectAsState()
    val clock = rememberMinuteClock()
    var layer by remember { mutableStateOf(Layer.None) }
    // What Up/Down walk: a Live now tune carries its list; a tune from a group carries none (its group).
    var zapContext by remember { mutableStateOf<List<Channel>?>(null) }
    // The last zap's direction, for the banner's motion; null for a jump (Back to the previous channel).
    var down by remember { mutableStateOf<Boolean?>(true) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // The channel list's search and place, kept between openings; each opening is a fresh list.
    val memory = remember { ListMemory() }
    var opens by remember { mutableStateOf(0) }
    var openInGroups by remember { mutableStateOf(false) }
    // Until the open layer has taken focus, the player still holds it: arrow keys then wait, rather
    // than moving focus into the layer wherever they happen to point.
    var layerFocused by remember { mutableStateOf(false) }
    // A brief line in the banner for the channel it was said on ("No matches on right now").
    var note by remember { mutableStateOf<Pair<ChannelIdentity?, String>?>(null) }
    var bannerKick by remember { mutableStateOf(0) }

    // Until the debounced tune starts, the feed playing is still the previous channel's (KTD9).
    val tuning = state.phase == Phase.Connecting || state.feed?.family != state.requested?.family
    val problem = problemOf(state).takeIf { state.feed?.family == state.requested?.family }

    var banner by remember { mutableStateOf(true) }
    LaunchedEffect(state.requested?.identity, tuning, bannerKick) {
        banner = true
        if (!tuning) {
            delay(BANNER_MS)
            banner = false
            note = null
        }
    }
    // Focus rests on the player itself whenever nothing else holds it, so the remote reaches keyAction.
    LaunchedEffect(layer, problem?.button) {
        if (layer == Layer.None && problem?.button == null) focus.requestFocus()
    }

    // A new list each time, even when reopened while the last one is still sliding away.
    fun openList(groups: Boolean) {
        opens++
        openInGroups = groups
        layer = Layer.ChannelList
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
            is KeyAction.OpenOverlay -> openList(groups = action.groups)
            KeyAction.OpenPanel -> layer = Layer.QuickPanel
            KeyAction.OpenRecent -> layer = Layer.Recent
            is KeyAction.LiveZap -> scope.launch {
                val g = guide
                val now = System.currentTimeMillis() / 1000
                // The guide can be swapped (and the old file closed) mid-read after a download.
                val matches = withContext(Dispatchers.IO) {
                    runCatching { feed.liveNow(g?.airingMatches(now).orEmpty()).context }.getOrNull()
                } ?: return@launch
                val from = session.state.value.requested
                val to = stepThrough(matches, from, action.down)
                if (matches.isEmpty()) note = from?.identity to "No matches on right now"
                if (to == null) {
                    bannerKick++
                    return@launch
                }
                down = action.down
                zapContext = matches
                session.zap(to, feed)
            }
            KeyAction.Close -> {
                layer = Layer.None
                pick = null
            }
            is KeyAction.MoveTile -> tiles.select(neighbourTile(grid.selected, grid.tiles.size, action.key))
            KeyAction.Promote -> {
                down = null
                zapContext = null
                tiles.promote(session, feed)
            }
            KeyAction.Ignore, KeyAction.Pass -> {}
        }
    }

    fun pickTile(purpose: Pick) {
        pick = purpose
        openList(groups = false)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Bubbles up from whatever is focused: an open layer's own keys never get here as player keys.
            .onKeyEvent { event ->
                val key = remoteKey(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
                if ((layer == Layer.ChannelList || layer == Layer.Recent) && !layerFocused && key != RemoteKey.Back) return@onKeyEvent true
                val action = keyAction(key, PlayerKeys(state.requested, graph.history.last, graph.history.previous, layer, zapContext, multiView), feed)
                if (action == KeyAction.Pass) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) perform(action)
                true // the key-up too, so Back never reaches the activity and leaves the app
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        if (multiView) MultiViewTiles(grid, guide, clock)

        AnimatedVisibility(
            visible = banner && layer == Layer.None && state.requested != null && !multiView,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // The scrim fades; the banner itself rises from the bottom and sinks back.
            val shown = reveal()
            Box(Modifier.fillMaxWidth().bottomScrim { shown.value }.padding(top = Space.xxxl * 2)) {
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
                        .slideFade({ shown.value }, Edge.Bottom, reduced)
                        .padding(Space.safe),
                    label = "banner",
                ) { channel ->
                    // Live badges once the playing feed is measured; the server's probed ones until then.
                    val liveBadges = live?.takeIf { channel?.identity == state.requested?.identity && it.first == state.feed?.id }?.second?.badges
                    if (channel != null) Banner(
                        channel, feed, guide, clock, tuning && channel.identity == state.requested?.identity, liveBadges ?: channel.badges,
                        note = note?.takeIf { it.first == channel.identity }?.second,
                        liveNow = zapContext,
                    )
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

        // The scrim fades; the list itself slides in from the left and back out to it.
        AnimatedVisibility(
            visible = layer == Layer.ChannelList,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.fillMaxSize(),
        ) {
            val shown = reveal()
            Box(Modifier.fillMaxSize().leftScrim { shown.value }.onFocusChanged { layerFocused = it.hasFocus }) {
                val selectedTile = grid.tiles.getOrNull(grid.selected)?.channel
                key(opens) { ChannelOverlay(
                    updateRow = updateRow(graph.updater),
                    feed = feed,
                    guide = guide,
                    clock = clock,
                    current = selectedTile ?: state.requested,
                    context = zapContext.takeIf { !multiView },
                    open = layer == Layer.ChannelList,
                    memory = memory,
                    startInGroups = openInGroups,
                    // Watching, not picking a tile: a row tunes behind the open list first (preview).
                    onPreview = if (pick == null && !multiView) { channel, context ->
                        zapContext = context
                        down = null
                        session.zap(channel, feed)
                    } else null,
                    onPick = { channel, context ->
                        layer = Layer.None
                        val purpose = pick
                        pick = null
                        val onScreen = if (multiView) grid.tiles.map { it.channel.family } else listOfNotNull(state.requested?.family)
                        if (purpose == null) {
                            // Picking the channel already on (in any group) just closes the list; it never retunes.
                            if (channel.family != state.requested?.family) {
                                zapContext = context
                                down = null
                                session.zap(channel, feed)
                            } else {
                                zapContext = context
                            }
                        } else if (channel.family !in onScreen) { // a channel already on screen never gets a second tile
                            when (purpose) {
                                Pick.MultiView -> tiles.enter(session, channel, feed)
                                Pick.AddTile -> tiles.add(channel, feed)
                                Pick.ReplaceTile -> tiles.replace(channel, feed)
                            }
                        }
                    },
                    entrance = { shown.value },
                ) }
            }
        }

        AnimatedVisibility(
            visible = layer == Layer.Recent,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe).onFocusChanged { layerFocused = it.hasFocus },
        ) {
            val shown = reveal()
            RecentSheet(
                modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
                feed = feed,
                guide = guide,
                clock = clock,
                recent = remember { graph.history.recent },
                current = state.requested,
                open = layer == Layer.Recent,
                onPick = { channel ->
                    layer = Layer.None
                    if (channel.family != state.requested?.family) {
                        down = null
                        zapContext = null
                        session.zap(channel, feed)
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = layer == Layer.QuickPanel,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
        ) {
            val shown = reveal()
            val audioState = audio?.state?.collectAsState()?.value ?: AudioState()
            val choice by graph.route.choice.collectAsState()
            val effective by graph.route.effective.collectAsState()
            QuickPanel(
                modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
                open = layer == Layer.QuickPanel,
                // The audio tracks are the full-screen player's, which multi-view stops.
                audio = if (multiView) AudioState() else audioState,
                onAudio = { audio?.select(it) },
                choice = choice,
                effective = effective,
                // Route.effective catches up a moment later, so tune on the route the choice resolves to now.
                onRoute = { picked ->
                    val kind = graph.route.choose(picked)
                    session.useRoute(kind)
                    grid.tiles.forEach { it.session.useRoute(kind) }
                },
                onDiagnostics = { layer = Layer.Diagnostics },
                views = {
                    if (!multiView) {
                        if (state.requested != null) PanelRow("Multi-view") { pickTile(Pick.MultiView) }
                    } else {
                        var full by remember { mutableStateOf(false) }
                        PanelRow("Add tile", "${grid.tiles.size} of $TILE_CAP") {
                            if (grid.tiles.size >= TILE_CAP) full = true else pickTile(Pick.AddTile)
                        }
                        PanelRow("Replace tile") { pickTile(Pick.ReplaceTile) }
                        PanelRow("Remove tile") {
                            layer = Layer.None
                            tiles.remove(session, feed)
                        }
                        if (full) Text(
                            "$TILE_CAP tiles is the most. Remove tile to make room for another.",
                            style = Type.caption,
                            modifier = Modifier.padding(start = Space.l, top = Space.xs),
                        )
                    }
                },
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

/**
 * Logo, name and badges, now and next, and where Up/Down move from: Live now when it was tuned from
 * there ([liveNow], the zap context), else its group. Above that, a passing [note].
 */
@Composable
private fun Banner(channel: Channel, feed: Feed, guide: GuideDb?, clock: State<Long>, tuning: Boolean, badges: List<String>, note: String?, liveNow: List<Channel>?) {
    val inLiveNow = liveNow?.takeIf { list -> list.any { it.family == channel.family } }
    val list = inLiveNow ?: feed.channelsIn(channel.group)
    val position = list.indexOfFirst { it.identity == channel.identity }.takeIf { it >= 0 } ?: list.indexOfFirst { it.family == channel.family }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
        LogoTile(channel, LOGO_SIZE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Text(remember(channel.name) { displayName(channel.name) }, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Badges(badges)
                if (tuning) Tuning()
            }
            Guide(channel, guide, clock)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (note != null) Text(note, style = Type.headline, maxLines = 1)
            Text(if (inLiveNow != null) "Live now" else channel.group, style = Type.caption, maxLines = 1)
            if (position >= 0) Text("${position + 1} of ${list.size}", style = Type.caption.tabular)
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
            Text(remember(now) { if (now.match) fixtureOf(now.title) else cleanTitle(now.title) }, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text("${clockTime(now.start)} – ${clockTime(now.stop)}", style = Type.caption.tabular)
            ProgressLine(
                fraction = { (clock.value - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1) },
                modifier = Modifier.widthIn(max = PROGRESS_WIDTH),
            )
        }
    }
    if (next != null && !next.synthetic) {
        Text("Next · ${clockTime(next.start)} ${cleanTitle(next.title)}", style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

internal fun clockTime(epochSec: Long) = timeFormat.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/** Epoch seconds, ticking on each minute: the one clock behind every progress line. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis() / 1000
    }
}
