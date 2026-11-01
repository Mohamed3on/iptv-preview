package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Airing
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.Badge
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent as AndroidKeyEvent

/** A Live now row: a confirmed match ([programme] set) or an unconfirmed event slot. [via] is the 🏆 row it was found on. */
data class LiveEntry(val title: String, val programme: Programme?, val via: Channel, val tune: Channel) {
    val confirmed get() = programme != null
}

/** Live now (R22): confirmed matches, then unconfirmed event slots. */
data class LiveNow(val matches: List<LiveEntry>, val slots: List<LiveEntry>) {
    val entries get() = matches + slots
    /** The zap context a Live now tune carries: Up/Down walk these. */
    val context get() = entries.map { it.tune }
}

/**
 * Live now from the guide's [airing] matches: one row per (normalized title, start) on a channel in a
 * 🏆 group, found on the first such row in server order (🏆 groups are quality-sorted) and tuned to the
 * top-ranked feed of its family. Then 🏆 event slots whose name reads as a fixture, one per name.
 */
fun Feed.liveNow(airing: List<Airing>): LiveNow {
    val byTvgId = airing.groupBy { it.tvgId }
    val trophy = channels.filter { it.group.startsWith(TROPHY) }
    val seen = HashSet<Pair<String, Long>>()
    val matches = buildList {
        for (c in trophy) {
            if (c.tvgId.isEmpty()) continue
            for (a in byTvgId[c.tvgId].orEmpty()) {
                if (seen.add(a.norm to a.programme.start)) add(LiveEntry(a.programme.title, a.programme, c, bestOf(c)))
            }
        }
    }
    val slots = trophy.filter { it.slot && it.fixture }.distinctBy { it.name }.map { LiveEntry(it.name, null, it, bestOf(it)) }
    return LiveNow(matches, slots)
}

private fun Feed.bestOf(channel: Channel) = familyMembers(channel.family).firstOrNull() ?: channel

private const val TROPHY = "🏆"

/** What a channel row shows under its name. */
data class RowLines(val name: String, val now: Programme?, val next: Programme?, val noGuide: Boolean)

/**
 * A row's lines from its now/next. Synthetic filler shows neither (an event slot's name in the feed
 * is newer than the guide's copy of it); a guide that has run out says so.
 */
fun rowLines(channel: Channel, guide: NowNext): RowLines {
    val name = displayName(channel.name)
    val (now, next) = guide
    if (now == null && next == null) return RowLines(name, null, null, noGuide = true)
    if (now?.synthetic == true) return RowLines(name, null, null, noGuide = false)
    return RowLines(name, now, next?.takeUnless { it.synthetic }, noGuide = false)
}

private val GROUPS_WIDTH = 260.dp
private val CHANNELS_WIDTH = 540.dp
private val GROUP_ROW_HEIGHT = 52.dp
private val ROW_HEIGHT = 84.dp
private val TILE = DpSize(52.dp, 52.dp)
private val MARKER = DpSize(3.dp, 24.dp)
private val DOT = 6.dp
private val INLINE_PROGRESS = 72.dp

/** What the channel column shows. U10 adds Search as another source. */
private sealed interface Source {
    val key: String
}
private data object LiveSource : Source {
    override val key = "live"
}
private data class GroupSource(val name: String) : Source {
    override val key get() = "g|$name"
}

/** A channel-column entry; [key] is stable across refreshes so focus stays on the same row. */
private sealed interface Item {
    val key: String
}
private class ChannelItem(val channel: Channel) : Item {
    override val key = "${channel.id}|${channel.group}"
}
private class LiveItem(val entry: LiveEntry) : Item {
    override val key = entry.programme?.let { "m|${it.start}|${entry.title}" } ?: "s|${entry.via.name}"
}
private data object SlotsHeading : Item {
    override val key = "slots"
}
private data object NoMatches : Item {
    override val key = "no-matches"
}

private val Item.focusable get() = this is ChannelItem || this is LiveItem
private val Item.target get() = when (this) {
    is ChannelItem -> channel
    is LiveItem -> entry.tune
    else -> null
}

/** Null while Live now is still loading. */
private fun itemsOf(source: Source, feed: Feed, live: LiveNow?): List<Item>? = when (source) {
    is GroupSource -> feed.channelsIn(source.name).map(::ChannelItem)
    LiveSource -> live?.let {
        buildList {
            it.matches.mapTo(this, ::LiveItem)
            if (it.slots.isNotEmpty()) {
                if (it.matches.isEmpty()) add(NoMatches)
                add(SlotsHeading)
                it.slots.mapTo(this, ::LiveItem)
            }
        }
    }
}

/**
 * The channel list over the still-playing video (R5, R6, R21, R22, F2): groups on the left, the shown
 * group's channels with now/next, and a channel's schedule on Right. Focus opens on [current] — in
 * Live now when it was tuned from there ([context]) — and moving through the groups shows each one.
 *
 * [onPick] gets the chosen channel and the zap context it carries (Live now's tune list, else null:
 * its group). The player tunes it; U11's pick mode passes its own. [open] is false while the overlay
 * leaves, so focus may go back to the player. [updateRow] (U12) and [searchRow] (U10) are slots at the
 * top of the groups column, in that order; they render nothing until filled (use [GroupRow]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelOverlay(
    feed: Feed,
    guide: GuideDb?,
    clock: State<Long>,
    current: Channel?,
    context: List<Channel>?,
    open: Boolean,
    onPick: (channel: Channel, context: List<Channel>?) -> Unit,
    modifier: Modifier = Modifier,
    updateRow: LazyListScope.() -> Unit = {},
    searchRow: LazyListScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val stay by rememberUpdatedState(open)
    val scope = rememberCoroutineScope()

    val live by produceState<LiveNow?>(null, feed, guide) {
        snapshotFlow { clock.value }.collectLatest { now ->
            // The guide can be swapped (and the old file closed) mid-read after a download: keep what is shown.
            value = withContext(Dispatchers.IO) {
                val airing = if (guide == null) emptyList() else runCatching { guide.airingMatches(now) }.getOrNull()
                airing?.let(feed::liveNow)
            } ?: return@collectLatest
        }
    }

    var shown by remember {
        mutableStateOf(
            if (current != null && context?.any { it.identity == current.identity } == true) LiveSource
            else GroupSource(current?.group ?: feed.groups.first()),
        )
    }
    // A refresh can drop the 🏆 group on screen; fall back to the first group.
    val source = shown.takeUnless { it is GroupSource && it.name !in feed.groups } ?: GroupSource(feed.groups.first())
    val items = remember(source, feed, live) { itemsOf(source, feed, live) }

    // Now/next for the whole shown group in one query, off the main thread, again each minute.
    val tvgIds = remember(items) { items?.mapNotNull { (it as? ChannelItem)?.channel?.tvgId?.takeIf(String::isNotEmpty) }?.distinct().orEmpty() }
    val nowNext by produceState(emptyMap<String, NowNext>(), tvgIds, guide) {
        snapshotFlow { clock.value }.collectLatest { now ->
            val fresh = withContext(Dispatchers.IO) {
                if (guide == null) tvgIds.associateWith { NowNext(null, null) } else runCatching { guide.nowNextFor(tvgIds, now) }.getOrNull()
            } ?: return@collectLatest
            value = value + fresh
        }
    }

    val lastFocused = remember { HashMap<String, String>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableStateOf(0) }
    var wantChannels by remember { mutableStateOf(true) }
    var inChannels by remember { mutableStateOf(false) }
    var entryKey by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<Channel?>(null) }
    // The pane keeps its channel while it slides away.
    var detailsShown by remember { mutableStateOf<Channel?>(null) }
    val rowFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }

    fun entryIndex(list: List<Item>): Int {
        lastFocused[source.key]?.let { k -> list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { return it } }
        current?.let { c -> list.indexOfFirst { it.target?.identity == c.identity }.takeIf { it >= 0 }?.let { return it } }
        return list.indexOfFirst { it.focusable }
    }

    val groupsState = remember {
        val at = if (source == LiveSource) 0 else feed.groups.indexOf((source as GroupSource).name) + 1
        LazyListState((at - 2).coerceAtLeast(0))
    }
    val listState = remember(source.key) { LazyListState(items?.let { (entryIndex(it) - 1).coerceAtLeast(0) } ?: 0) }

    fun focusChannels(index: Int? = null) {
        val list = items ?: return
        val i = index ?: entryIndex(list)
        if (i !in list.indices || !list[i].focusable) return
        entryKey = list[i].key
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem((i - 1).coerceAtLeast(0))
            settle()
            runCatching { rowFocus.requestFocus() }
        }
    }

    fun focusGroups() {
        scope.launch {
            val info = groupsState.layoutInfo
            if (info.visibleItemsInfo.none { it.key == source.key }) {
                // The hook rows (U10, U12) come first, then Live now, then the groups.
                val first = info.totalItemsCount - feed.groups.size - 1
                val at = if (source == LiveSource) first else first + 1 + feed.groups.indexOf((source as GroupSource).name)
                groupsState.scrollToItem((at - 2).coerceAtLeast(0))
            }
            settle()
            runCatching { groupFocus.requestFocus() }
        }
    }

    fun closeDetails() {
        details = null
        focusChannels()
    }

    // Focus opens on the current channel once its list exists, and never lands nowhere: when a refresh
    // drops the focused row, the nearest row takes it; an emptied list hands focus to its group.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        val list = items ?: return@LaunchedEffect
        if (!opened) {
            opened = true
            if (list.any { it.focusable }) focusChannels() else focusGroups()
        } else if (wantChannels && details == null && list.none { it.key == focusedKey }) {
            val near = list.indices.filter { list[it].focusable }.minByOrNull { kotlin.math.abs(it - focusedIndex) }
            if (near != null) focusChannels(near) else focusGroups()
        }
    }

    // Keep the focused row clear of the edge fades, with the next row in view.
    val density = LocalDensity.current
    val keepClear = remember(density) {
        val margin = with(density) { ROW_HEIGHT.toPx() * 0.75f }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val before = offset - margin
                val after = offset + size + margin - containerSize
                return when {
                    before < 0 -> before
                    after > 0 -> after
                    else -> 0f
                }
            }
        }
    }

    Row(
        modifier
            .fillMaxHeight()
            .padding(Space.safe)
            .onKeyEvent {
                if (details == null || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) closeDetails()
                true
            }
            // Focus stays in the list while it is open: no key lands on the video underneath.
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        AnimatedVisibility(
            visible = details == null,
            enter = Motion.fromLeft(reduced) + expandHorizontally(Motion.panel()),
            exit = Motion.toLeft(reduced) + shrinkHorizontally(Motion.panel()),
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                LazyColumn(
                    state = groupsState,
                    modifier = Modifier
                        .width(GROUPS_WIDTH)
                        .fillMaxHeight()
                        .edgeFade()
                        .onKeyEvent {
                            if (it.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onKeyEvent false
                            if (it.type == KeyEventType.KeyDown) focusChannels()
                            true
                        },
                    contentPadding = PaddingValues(vertical = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    updateRow()
                    searchRow()
                    item(key = LiveSource.key) {
                        GroupRow(
                            "Live now",
                            marked = source == LiveSource && inChannels,
                            focus = if (source == LiveSource) groupFocus else null,
                            onFocused = { shown = LiveSource; wantChannels = false },
                            onClick = { focusChannels() },
                        )
                    }
                    items(feed.groups, key = { "g|$it" }) { group ->
                        val here = source is GroupSource && source.name == group
                        GroupRow(
                            group,
                            marked = here && inChannels,
                            focus = if (here) groupFocus else null,
                            onFocused = { shown = GroupSource(group); wantChannels = false },
                            onClick = { focusChannels() },
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .width(CHANNELS_WIDTH)
                .fillMaxHeight()
                .onFocusChanged { inChannels = it.hasFocus }
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onKeyEvent false
                    if (it.type == KeyEventType.KeyDown) {
                        if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) focusGroups()
                        else items?.firstOrNull { item -> item.key == focusedKey }?.target?.let { c ->
                            details = c
                            detailsShown = c
                        }
                    }
                    true
                },
        ) {
            val list = items
            when {
                list == null -> {}
                list.isEmpty() && source == LiveSource -> Empty("No matches on right now", "Matches appear here when they kick off.")
                list.isEmpty() -> Empty("Nothing in this group right now")
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().edgeFade(),
                        contentPadding = PaddingValues(vertical = Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { index, item ->
                            val focus = if (item.key == entryKey) rowFocus else null
                            val onFocused = {
                                focusedKey = item.key
                                focusedIndex = index
                                lastFocused[source.key] = item.key
                                wantChannels = true
                            }
                            when (item) {
                                is ChannelItem -> ChannelRow(
                                    item.channel, nowNext[item.channel.tvgId], clock,
                                    playing = item.channel.id == current?.id, focus = focus, onFocused = onFocused,
                                    onClick = { onPick(item.channel, null) },
                                )
                                is LiveItem -> LiveRow(
                                    item.entry, clock, playing = item.entry.tune.id == current?.id, focus = focus, onFocused = onFocused,
                                    onClick = { onPick(item.entry.tune, live?.context) },
                                )
                                NoMatches -> Text("No matches on right now", style = Type.body, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m))
                                SlotsHeading -> Text(
                                    "Event channels · not confirmed",
                                    style = Type.caption,
                                    modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs),
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = details != null,
            enter = Motion.fromRight(reduced),
            exit = Motion.toRight(reduced),
        ) {
            detailsShown?.let { Details(it, guide, clock, stay = { details != null }, onClose = ::closeDetails) }
        }
    }
}

/** Two frames: the new focus target is composed and placed before it is asked to take focus. */
private suspend fun settle() {
    withFrameNanos {}
    withFrameNanos {}
}

/** A groups-column entry; [marked] shows a quiet accent bar while the channel column has focus. */
@Composable
fun GroupRow(label: String, marked: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(GROUP_ROW_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .pressable(onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (marked) Box(Modifier.size(MARKER).clip(RoundedCornerShape(MARKER.width)).background(Palette.accent))
        Text(label, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Space.l))
    }
}

@Composable
private fun RowFrame(
    tile: Channel,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    lines: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .pressable(onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        LogoTile(tile, TILE)
        Column(Modifier.weight(1f), content = lines)
    }
}

/**
 * Name and badges, then now (with progress) and next. Until the guide lookup returns, the lines are
 * held empty at full height, so rows never jump as data fills in.
 */
@Composable
private fun ChannelRow(
    channel: Channel,
    guide: NowNext?,
    clock: State<Long>,
    playing: Boolean,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val lines = remember(channel, guide) { guide?.let { rowLines(channel, it) } }
    val name = remember(channel.name) { displayName(channel.name) }
    RowFrame(channel, focus, onFocused, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            Text(name, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            channel.badges.forEach { Badge(it) }
        }
        val now = lines?.now
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            when {
                lines?.noGuide == true -> Text("No guide", style = Type.body, maxLines = 1)
                now != null -> {
                    if (now.match) LiveMark()
                    Text(now.title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Progress(now, clock)
                }
                else -> Text("", style = Type.body)
            }
        }
        val next = lines?.next
        Text(remember(next) { next?.let { "Next · ${clockTime(it.start)} ${it.title}" } ?: "" }, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A match: its title first, then the channel it tunes and the competition. Slots say they're unconfirmed. */
@Composable
private fun LiveRow(entry: LiveEntry, clock: State<Long>, playing: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val programme = entry.programme
    val title = remember(entry) { if (programme != null) entry.title else displayName(entry.title) }
    val channel = remember(entry.tune.name) { displayName(entry.tune.name) }
    RowFrame(entry.tune, focus, onFocused, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            if (programme != null) LiveMark()
            Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(if (programme != null) channel else "Not confirmed", style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (programme != null) Progress(programme, clock)
        }
        Text(entry.via.group, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Repaints from the shared minute clock in the draw phase; the row never recomposes for it. */
@Composable
private fun Progress(programme: Programme, clock: State<Long>) {
    ProgressLine(
        fraction = { (clock.value - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1) },
        modifier = Modifier.width(INLINE_PROGRESS),
    )
}

/** The channel playing now. */
@Composable
private fun PlayingDot() {
    Box(Modifier.size(DOT).clip(CircleShape).background(Palette.accent))
}

/** An empty list, said plainly at the height of a row. */
@Composable
private fun Empty(title: String, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = Space.xl).height(ROW_HEIGHT).padding(horizontal = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterVertically),
    ) {
        Text(title, style = Type.body)
        if (hint != null) Text(hint, style = Type.caption)
    }
}
