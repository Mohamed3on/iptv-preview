package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.player.TilePlayer
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus

/** Most tiles at once: on the stick 3 played at 50fps with no dropped frames; 4 starved the line at peak (U1). */
const val TILE_CAP = 3

/**
 * The feed a tile plays (R19, KTD6): the best-ranked family member probed at 1080 or less, else
 * one whose name marker is below 4K, else the top member. Several 4K decodes won't fit the stick.
 */
fun tileFeed(feed: Feed, channel: Channel): Channel {
    val members = feed.familyMembers(channel.family).ifEmpty { listOf(channel) }
    return members.firstOrNull { it.h != null && it.h <= 1080 } ?: members.firstOrNull { it.q < 5 } ?: members.first()
}

/** Full screen plays the channel's best feed: the picked row when it is the best, so a 🏆 pick keeps its group. */
private fun bestFeed(feed: Feed, channel: Channel): Channel =
    channel.takeIf { it.rank == 0 } ?: feed.familyMembers(channel.family).firstOrNull() ?: channel

/** One tile: the [channel] as picked (what the caption names); its session plays [tileFeed] of it. */
data class Tile<P : TilePlayer>(val channel: Channel, val player: P, val session: StreamSession, private val job: Job) {
    internal fun close(release: (P) -> Unit) {
        session.stop()
        job.cancel()
        release(player)
    }
}

/** No tiles = full screen. Only the [selected] tile is heard. */
data class MultiViewState<P : TilePlayer>(val tiles: List<Tile<P>> = emptyList(), val selected: Int = 0)

/**
 * Multi-view (R17, R18, R19): up to [TILE_CAP] tiles, each its own pooled player and
 * [StreamSession], so one tile going busy or offline leaves the others playing. While it is open
 * the full-screen session is stopped; leaving puts the selected tile back on it. Main thread only;
 * each tile's session runs in its own child of [scope], cancelled with the tile.
 */
class MultiView<P : TilePlayer>(
    private val acquire: () -> P,
    private val release: (P) -> Unit,
    private val route: Route,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(MultiViewState<P>())
    val state: StateFlow<MultiViewState<P>> = _state

    /** From full screen: [main]'s channel becomes tile 1 (keeping the sound) and [second] tile 2. */
    fun enter(main: StreamSession, second: Channel, feed: Feed) {
        val first = main.state.value.requested ?: return
        main.stop()
        show(listOf(open(first, feed), open(second, feed)), 0)
    }

    /** Adds a tile; refused (false, nothing opened) at [TILE_CAP]. */
    fun add(channel: Channel, feed: Feed): Boolean {
        val s = _state.value
        if (s.tiles.size >= TILE_CAP) return false
        show(s.tiles + open(channel, feed), s.selected)
        return true
    }

    /** Tunes the selected tile to [channel] on the same player; the other tiles are untouched. */
    fun replace(channel: Channel, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        tile.session.zap(tileFeed(feed, channel), feed)
        show(s.tiles.toMutableList().also { it[s.selected] = tile.copy(channel = channel) }, s.selected)
    }

    /** Removes the selected tile; when one is left, it goes full screen on [main]. */
    fun remove(main: StreamSession, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        tile.close(release)
        val rest = s.tiles - tile
        if (rest.size > 1) return show(rest, s.selected.coerceAtMost(rest.size - 1))
        _state.value = MultiViewState(rest)
        promote(main, feed)
    }

    fun select(index: Int) {
        val s = _state.value
        if (index in s.tiles.indices) show(s.tiles, index)
    }

    /** OK and Back (R18, AE7): the selected tile's channel plays full screen on [main] at its best feed; every tile stops. */
    fun promote(main: StreamSession, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        close()
        main.zap(bestFeed(feed, tile.channel), feed)
    }

    /** Releases every tile, with nothing put on full screen (the app stopping, the idle stop). */
    fun close() {
        _state.value.tiles.forEach { it.close(release) }
        _state.value = MultiViewState()
    }

    private fun show(tiles: List<Tile<P>>, selected: Int) {
        tiles.forEachIndexed { i, t -> t.player.setVolume(if (i == selected) 1f else 0f) }
        _state.value = MultiViewState(tiles, selected)
    }

    private fun open(channel: Channel, feed: Feed): Tile<P> {
        val player = acquire()
        val job = Job(scope.coroutineContext[Job])
        val session = StreamSession(player, route, scope + job, now)
        session.zap(tileFeed(feed, channel), feed)
        return Tile(channel, player, session, job)
    }
}

/**
 * The tile [key] moves the selection to in the two-column grid, or [from] at an edge. Down from
 * the top right lands on the bottom tile when the cell under it is empty.
 */
fun neighbourTile(from: Int, count: Int, key: RemoteKey): Int = when (key) {
    RemoteKey.Left -> if (from % 2 == 1) from - 1 else from
    RemoteKey.Right -> if (from % 2 == 0 && from + 1 < count) from + 1 else from
    RemoteKey.Up -> if (from >= 2) from - 2 else from
    RemoteKey.Down -> if (from < 2 && count > 2) minOf(from + 2, count - 1) else from
    else -> from
}

private const val ASPECT = 16f / 9f
/** The selection ring sits this far outside the video. */
private val RING_GAP = 4.dp
private val RING_WIDTH = 2.dp
private val CAPTION_HEIGHT = 20.dp

/**
 * The tiles: 2 side by side, 3 in a 2×2 grid with the last cell empty, each 16:9 and the whole
 * centred in the overscan-safe area. Video is square-edged (a SurfaceView can't be clipped
 * cheaply); the selection is an accent ring drawn over it that glides between tiles.
 */
@Composable
fun MultiViewTiles(state: MultiViewState<ExoStreamPlayer>, guide: GuideDb?, clock: State<Long>) {
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).padding(Space.safe)) {
        val n = state.tiles.size
        val rows = if (n > 2) 2 else 1
        val gutter = Space.l
        val captionSpace = Space.s + CAPTION_HEIGHT
        val w = minOf((maxWidth - gutter) / 2, ((maxHeight - gutter * (rows - 1)) / rows - captionSpace) * ASPECT)
        val h = w / ASPECT
        val left = (maxWidth - w * 2 - gutter) / 2
        val top = (maxHeight - (h + captionSpace) * rows - gutter * (rows - 1)) / 2
        fun at(i: Int) = DpOffset(left + (w + gutter) * (i % 2), top + (h + captionSpace + gutter) * (i / 2))

        state.tiles.forEachIndexed { i, tile ->
            key(tile.player) {
                val o = at(i)
                // Clipped, because a SurfaceView punches its hole by clearing the whole clip it draws in:
                // unclipped, each tile's punch wiped the captions of the tiles drawn before it.
                Box(Modifier.offset(o.x, o.y).size(w, h).clipToBounds(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { SurfaceView(it).apply { keepScreenOn = true }.also(tile.player.exo::setVideoSurfaceView) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    TileStatus(tile.session)
                }
                Caption(tile.channel, guide, clock, i == state.selected, Modifier.offset(o.x, o.y + h + Space.s).width(w))
            }
        }

        val ring = at(state.selected.coerceIn(0, (n - 1).coerceAtLeast(0)))
        val spec = if (reduced) snap<Dp>() else Motion.focus<Dp>()
        // Read while placing, so the glide moves the ring without recomposing the grid each frame.
        val x = animateDpAsState(ring.x, spec, label = "ringX")
        val y = animateDpAsState(ring.y, spec, label = "ringY")
        val pad = RING_GAP + RING_WIDTH
        if (n > 0) Box(
            Modifier
                .offset { IntOffset((x.value - pad).roundToPx(), (y.value - pad).roundToPx()) }
                .size(w + pad * 2, h + pad * 2)
                .border(RING_WIDTH, Palette.accent, RoundedCornerShape(Radius.chip + RING_GAP)),
        )
    }
}

/** The tile's own trouble, compactly inside it; a tile that plays shows nothing. */
@Composable
private fun TileStatus(session: StreamSession) {
    val phase = session.state.collectAsState().value.phase
    val message = when (phase) {
        Phase.Connecting -> return ActivityLine(width = 96.dp)
        Phase.Busy -> "Account in use · retrying"
        Phase.GaveUp -> "Account in use"
        is Phase.WrongRoute -> "Wrong connection for your VPN"
        Phase.Offline -> "Channel unavailable"
        Phase.Stopped, Phase.Playing -> return
    }
    FitText(
        message,
        style = Type.caption.copy(color = Palette.textPrimary),
        modifier = Modifier.padding(Space.m).material(Radius.chip).padding(horizontal = Space.m, vertical = Space.xs),
    )
}

/** Name and the programme airing now; the selected tile's, which is the one heard, reads brightest. */
@Composable
private fun Caption(channel: Channel, guide: GuideDb?, clock: State<Long>, selected: Boolean, modifier: Modifier) {
    val now by remember(channel.tvgId, guide) {
        derivedStateOf {
            // The guide can be swapped (and the old file closed) mid-read after a download.
            guide?.takeIf { channel.tvgId.isNotEmpty() }?.let { runCatching { it.nowNext(channel.tvgId, clock.value).now }.getOrNull() }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        FitText(
            remember(channel.name) { displayName(channel.name) },
            style = Type.caption.copy(color = if (selected) Palette.textPrimary else Palette.textSecondary),
            modifier = Modifier.weight(1f, fill = false),
        )
        val programme = now?.takeUnless { it.synthetic } ?: return@Row
        if (programme.match) LiveMark()
        FitText(programme.title, style = Type.caption, modifier = Modifier.weight(1f, fill = false))
    }
}
