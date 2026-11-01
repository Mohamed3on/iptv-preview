package com.mohamed3on.iptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.Watched
import com.mohamed3on.iptv.data.resolve
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private val SHEET_WIDTH = 380.dp

/**
 * Right in full screen: the channels watched last ([recent], newest first) in a sheet beside the
 * still-playing video, as channel rows. Focus opens on the newest one not playing, so OK goes straight
 * back to the channel before this one; OK hands a row to [onPick]; Back closes (the player's KeyMap).
 * [open] is false while the sheet leaves, so focus may go back to the player.
 */
@Composable
fun RecentSheet(
    feed: Feed,
    guide: GuideDb?,
    clock: State<Long>,
    recent: List<Watched>,
    current: Channel?,
    open: Boolean,
    onPick: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stay by rememberUpdatedState(open)
    val channels = remember(feed, recent) { recent.mapNotNull(feed::resolve).distinctBy { it.identity } }
    val tvgIds = remember(channels) { channels.map { it.tvgId }.filter(String::isNotEmpty).distinct() }
    val nowNext by produceState(emptyMap<String, NowNext>(), tvgIds, guide) {
        snapshotFlow { clock.value }.collectLatest { now ->
            value = withContext(Dispatchers.IO) {
                if (guide == null) tvgIds.associateWith { NowNext(null, null) } else runCatching { guide.nowNextFor(tvgIds, now) }.getOrNull()
            } ?: return@collectLatest
        }
    }

    val start = remember(channels) { channels.indexOfFirst { it.id != current?.id }.coerceAtLeast(0) }
    val entry = remember { FocusRequester() }
    var focused by remember { mutableStateOf(-1) }
    LaunchedEffect(channels) {
        // The row takes focus once it is composed and placed.
        repeat(30) {
            withFrameNanos {}
            runCatching { entry.requestFocus() }
            if (focused >= 0) return@LaunchedEffect
        }
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(SHEET_WIDTH)
            .material()
            // Focus stays in the sheet while it is open: no key lands on the video underneath.
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(vertical = Space.xl),
    ) {
        Text("Recent", style = Type.title, modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.s))
        if (channels.isEmpty()) {
            Text("Channels you watch show up here.", style = Type.body, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.m))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).edgeFade(),
            contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            itemsIndexed(channels, key = { _, c -> "${c.id}|${c.group}" }) { i, channel ->
                ChannelRow(
                    channel, nowNext[channel.tvgId], clock,
                    playing = channel.id == current?.id, watchHint = false,
                    focus = if (i == start) entry else null,
                    onFocused = { focused = i },
                    onClick = { onPick(channel) },
                )
            }
        }
    }
}
