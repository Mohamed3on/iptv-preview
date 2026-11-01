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
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import java.text.Normalizer

/** A Live now row: a confirmed match ([programme] set) or an unconfirmed event slot. [via] is the 🏆 row it was found on. */
data class LiveEntry(
    val title: String, val programme: Programme?, val via: Channel, val tune: Channel, val comp: String? = null,
    /** Other channels showing the same match. */
    val alsoOn: Int = 0,
) {
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
                if (seen.add(a.norm to a.programme.start)) add(LiveEntry(a.programme.title, a.programme, c, bestOf(c), a.comp))
            }
        }
    }
    val slots = trophy.filter { it.slot && it.fixture }.distinctBy { it.name }.map { LiveEntry(it.name, null, it, bestOf(it)) }
    return LiveNow(sameMatchOnce(matches), slots)
}

/**
 * Broadcasters title one match differently ("Live: LaLiga - Athletic Bilbao - Real Sociedad",
 * "Athletic Bilbao vs Real Sociedad - Spanish La Liga"), so rows are one match when they share the
 * competition, overlap in time and name two of the same teams' words. The first row (best channel)
 * is kept; it shows the clearest title of the group (a "v"/"vs" one, then the shortest).
 */
private fun sameMatchOnce(matches: List<LiveEntry>): List<LiveEntry> {
    val groups = mutableListOf<MutableList<LiveEntry>>()
    for (m in matches) {
        val words = teamWords(m.title)
        val group = groups.firstOrNull { g ->
            val first = g.first()
            first.comp == m.comp && first.programme!!.start < m.programme!!.stop && m.programme.start < first.programme.stop &&
                (teamWords(first.title) intersect words).size >= 2
        }
        if (group != null) group += m else groups += mutableListOf(m)
    }
    return groups.map { g ->
        val title = g.map { it.title }.sortedWith(compareBy({ !VERSUS.containsMatchIn(it) }, { it.length })).first()
        g.first().copy(title = fixtureOf(title), alsoOn = g.drop(1).map { it.tune.family }.distinct().count { it != g.first().tune.family })
    }
}

private val VERSUS = Regex("\\s(v|vs\\.?)\\s", RegexOption.IGNORE_CASE)
private val LIVE_PREFIX = Regex("^live\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE)
private val SUPERSCRIPT = Regex("[\\u1D2C-\\u1DBF\\u2070-\\u209F\\u00B2\\u00B3\\u00B9]+")

/**
 * Just the fixture from a broadcaster's title: "LALIGA EA SPORTS 2026/2027 MD11 : Getafe CF vs
 * Sevilla FC" → "Getafe CF vs Sevilla FC", "Athletic Bilbao vs Real Sociedad - Spanish LaLiga
 * 2026/2027 - Week 11" → "Athletic Bilbao vs Real Sociedad". Other titles are cleaned like channel names.
 */
internal fun fixtureOf(title: String): String {
    var t = title.replace(SUPERSCRIPT, "").replace(LIVE_PREFIX, "").trim()
    t.substringAfterLast(":", "").trim().let { if (it.contains(" - ") || VERSUS.containsMatchIn(it)) t = it }
    if (VERSUS.containsMatchIn(t)) return t.split(" - ").firstOrNull { VERSUS.containsMatchIn(it) }?.trim() ?: t
    // No fixture: a coverage title, often shouted with a feed prefix ("IT: DAZN DIRETTA GOL SERIE A HD").
    return if (t.contains(" - ")) t else displayName(t)
}
/** Words that name a competition or the coverage, never a team. */
private val NOT_TEAM = setOf(
    "live", "liga", "laliga", "league", "premier", "spanish", "english", "italian", "german", "french", "serie",
    "bundesliga", "ligue", "champions", "europa", "conference", "football", "sports", "sport", "primera", "primeira",
    "matchday", "round", "final", "copa", "coppa", "cup", "pokal", "championship",
)
private fun teamWords(title: String): Set<String> =
    Regex("\\p{L}{4,}").findAll(Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase())
        .map { it.value }.filterNot { it in NOT_TEAM }.toSet()

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

/** What the channel column shows. */
private sealed interface Source {
    val key: String
}
private data object SearchSource : Source {
    override val key = "search"
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
/** The query and on-screen keyboard, first in Search. */
private data object SearchBoxItem : Item {
    override val key = "search-box"
}
private class HitItem(val hit: SearchHit) : Item {
    override val key = "h|${hit.tune.family}|${hit.programme.start}|${hit.programme.title}"
}
private data object ChannelsHeading : Item {
    override val key = "channels"
}
private data object ProgrammesHeading : Item {
    override val key = "programmes"
}
private data class Note(val title: String, val hint: String? = null) : Item {
    override val key get() = "note"
}

private val Item.focusable get() = this is ChannelItem || this is LiveItem || this is HitItem
private val Item.target get() = when (this) {
    is ChannelItem -> channel
    is LiveItem -> entry.tune
    is HitItem -> hit.tune
    else -> null
}

/**
 * Null while Live now is still loading. Search shows a hint until 2 characters are [typed], then
 * what was [found] (nothing more while the first search runs): channels by name, then programmes,
 * each under its title only when it has rows.
 */
private fun itemsOf(source: Source, feed: Feed, live: LiveNow?, typed: Boolean, found: SearchResults?): List<Item>? = when (source) {
    SearchSource -> buildList {
        add(SearchBoxItem)
        when {
            !typed -> add(Note("Type a channel, team, player or event"))
            found == null -> {}
            found.count == 0 -> add(Note("No matches for “${found.query.trim()}”", "Try part of a channel, team or player name."))
            else -> {
                if (found.channels.isNotEmpty()) {
                    add(ChannelsHeading)
                    found.channels.mapTo(this, ::ChannelItem)
                }
                if (found.hits.isNotEmpty()) {
                    add(ProgrammesHeading)
                    found.hits.mapTo(this, ::HitItem)
                }
            }
        }
    }
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

/** What a finished search ran with, so reopening the list never runs it again. */
internal class Searched(private val query: String, private val feed: Feed, private val guide: GuideDb?) {
    fun matches(query: String, feed: Feed, guide: GuideDb?) = query == this.query && feed === this.feed && guide === this.guide
}

/**
 * What the channel list keeps between openings, for the app session: the search (query, results,
 * the result last focused) and whether Search was on screen, so reopening goes back to it.
 */
class ListMemory {
    internal var query by mutableStateOf("")
    internal var found by mutableStateOf<SearchResults?>(null)
    internal var searching by mutableStateOf(false)
    internal var searched: Searched? = null
    /** The search result last focused; null when the keyboard was. */
    internal var searchFocus: String? = null
    internal var inSearch = false
}

private const val SEARCH_TAG = "IptvSearch"
/** About half a second of frames for a row to be composed and placed before focus gives up on it. */
private const val FOCUS_FRAMES = 30

/**
 * The channel list over the still-playing video (R5, R6, R21, R22, F2): groups on the left, the shown
 * group's channels with now/next, and a channel's schedule on Right. Focus opens on [current]'s row —
 * in Live now when it was tuned from there ([context]) — or on its group in the groups column with
 * [startInGroups]. When Search was on screen at the last close, it opens there instead, on the result
 * last focused ([memory] keeps the search for the app session). Moving through the groups shows each one.
 *
 * Choosing a row hands [onPreview] the channel and the zap context it carries (Live now's tune list,
 * else null: its group): the player tunes it behind the still-open list, which keeps its place.
 * Choosing the row playing, or any row when there is no [onPreview] (U11's pick mode), goes to
 * [onPick], which closes the list. [open] is false while the overlay leaves, so focus may go back to
 * the player. [updateRow] (U12) is a slot at the top of the groups column, above Search; it renders
 * nothing until filled (use [GroupRow]).
 *
 * Search (R7, R8, R26, F3) shows a keyboard and its results in the channel column; a result tunes
 * with no context, so Up/Down then follow that channel's group.
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
    memory: ListMemory,
    startInGroups: Boolean,
    onPick: (channel: Channel, context: List<Channel>?) -> Unit,
    onPreview: ((channel: Channel, context: List<Channel>?) -> Unit)?,
    modifier: Modifier = Modifier,
    updateRow: LazyListScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val stay by rememberUpdatedState(open)
    val playing by rememberUpdatedState(current)
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
            when {
                memory.inSearch && !startInGroups -> SearchSource
                current != null && context?.any { it.identity == current.identity } == true -> LiveSource
                else -> GroupSource(current?.group ?: feed.groups.first())
            },
        )
    }
    // A refresh can drop the 🏆 group on screen; fall back to the first group.
    val source = shown.takeUnless { it is GroupSource && it.name !in feed.groups } ?: GroupSource(feed.groups.first())
    SideEffect { memory.inSearch = source == SearchSource }
    val inSearch = source == SearchSource
    LaunchedEffect(feed, inSearch) { if (inSearch) withContext(Dispatchers.IO) { feed.prepareSearch() } }
    val query = memory.query
    val typed = searchNorm(query).trim().length >= 2
    // Each keystroke restarts the wait, so a search runs once typing pauses; the last results stay
    // meanwhile, and the busy line shows only for a search slow enough to notice.
    LaunchedEffect(query, feed, guide) {
        if (!typed) {
            memory.found = null
            memory.searched = null
            memory.searching = false
            return@LaunchedEffect
        }
        if (memory.searched?.matches(query, feed, guide) == true) return@LaunchedEffect
        delay(150)
        val busy = launch {
            delay(120)
            memory.searching = true
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val results = withContext(Dispatchers.IO) { runCatching { feed.search(guide, query, clock.value) }.getOrNull() } ?: return@LaunchedEffect
            Log.d(SEARCH_TAG, "q=${query.trim().length} chars, ${SystemClock.elapsedRealtime() - started} ms, ${results.count} hits")
            memory.found = results
            memory.searched = Searched(query, feed, guide)
        } finally {
            busy.cancel()
            memory.searching = false
        }
    }
    val found = memory.found
    val items = remember(source, feed, live.takeIf { source == LiveSource }, typed, found.takeIf { source == SearchSource }) {
        itemsOf(source, feed, live, typed, found)
    }

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
    var inGroups by remember { mutableStateOf(false) }
    var onKeyboard by remember { mutableStateOf(false) }
    var entryKey by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<Channel?>(null) }
    // The pane keeps its channel while it slides away.
    var detailsShown by remember { mutableStateOf<Channel?>(null) }
    val rowFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    // One focus move at a time: a newer one cancels any still waiting for its target.
    val focusing = remember { arrayOfNulls<Job>(1) }
    fun moveFocus(block: suspend CoroutineScope.() -> Unit) {
        focusing[0]?.cancel()
        focusing[0] = scope.launch(block = block)
    }

    /** The row last focused here, else the playing channel's (the same row, else the same stream), else the first. */
    fun entryIndex(list: List<Item>): Int {
        lastFocused[source.key]?.let { k -> list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { return it } }
        current?.let { c ->
            list.indexOfFirst { it.target?.identity == c.identity }.takeIf { it >= 0 }?.let { return it }
            list.indexOfFirst { it.target?.id == c.id }.takeIf { it >= 0 }?.let { return it }
        }
        return list.indexOfFirst { it.focusable }
    }

    /** The search result focused last, if it is still in [list]. */
    fun resumeIndex(list: List<Item>): Int? = memory.searchFocus?.let { k -> list.indexOfFirst { it.key == k }.takeIf { it >= 0 } }

    val groupsState = remember {
        val at = (source as? GroupSource)?.let { feed.groups.indexOf(it.name) + 1 } ?: 0
        LazyListState((at - 2).coerceAtLeast(0))
    }
    val listState = remember(source.key) {
        LazyListState(items?.let { ((if (source == SearchSource) resumeIndex(it) ?: 0 else entryIndex(it)) - 1).coerceAtLeast(0) } ?: 0)
    }

    /**
     * Entering the channel column lands on [index], else the row last or now playing; in Search, on the
     * keyboard, or with [resume] on the result focused last. The target takes focus once it is composed
     * and placed, asked again each frame for a moment, so a list still settling never drops it.
     */
    fun focusChannels(index: Int? = null, resume: Boolean = false) {
        val list = items ?: return
        val i = index ?: if (source == SearchSource) (if (resume) resumeIndex(list) else null) else entryIndex(list)
        if (i == null) {
            moveFocus {
                listState.scrollToItem(0)
                repeat(FOCUS_FRAMES) {
                    withFrameNanos {}
                    runCatching { keyFocus.requestFocus() }
                    if (inChannels && onKeyboard) return@moveFocus
                }
            }
            return
        }
        if (i !in list.indices || !list[i].focusable) return
        val key = list[i].key
        entryKey = key
        moveFocus {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem((i - 1).coerceAtLeast(0))
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                runCatching { rowFocus.requestFocus() }
                if (inChannels && focusedKey == key) return@moveFocus
            }
        }
    }

    /** The groups column, on the shown source's row, once the column has laid out and scrolled to it. */
    fun focusGroups() {
        moveFocus {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                val info = groupsState.layoutInfo
                if (info.totalItemsCount == 0) return@repeat
                if (info.visibleItemsInfo.none { it.key == source.key }) {
                    // The update row (U12) and Search come first, then Live now, then the groups.
                    val first = info.totalItemsCount - feed.groups.size - 1
                    val at = when (source) {
                        SearchSource -> first - 1
                        LiveSource -> first
                        is GroupSource -> first + 1 + feed.groups.indexOf(source.name)
                    }
                    groupsState.scrollToItem((at - 2).coerceAtLeast(0))
                } else {
                    runCatching { groupFocus.requestFocus() }
                    if (inGroups) return@moveFocus
                }
            }
        }
    }

    fun closeDetails() {
        details = null
        focusChannels()
    }

    /** A row chosen: tuned behind the list to preview it, or, when it is the one playing, watched full screen. */
    fun choose(channel: Channel, context: List<Channel>?) {
        val preview = onPreview
        if (preview != null && channel.id != playing?.id) preview(channel, context) else onPick(channel, context)
    }
    val watchHint = onPreview != null

    // Focus opens on the current channel once its list exists, and never lands nowhere: when a refresh
    // drops the focused row, the nearest row takes it; an emptied list hands focus to its group.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        val list = items ?: return@LaunchedEffect
        if (!opened) {
            opened = true
            if (!startInGroups && (source == SearchSource || list.any { it.focusable })) focusChannels(resume = true) else focusGroups()
        } else if (focusedKey != null && wantChannels && details == null && list.none { it.key == focusedKey }) {
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
                        .onFocusChanged { inGroups = it.hasFocus }
                        .onKeyEvent {
                            if (it.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onKeyEvent false
                            if (it.type == KeyEventType.KeyDown) focusChannels()
                            true
                        },
                    contentPadding = PaddingValues(vertical = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    updateRow()
                    item(key = SearchSource.key) {
                        GroupRow(
                            "Search",
                            marked = source == SearchSource && inChannels,
                            focus = if (source == SearchSource) groupFocus else null,
                            onFocused = { shown = SearchSource; wantChannels = false },
                            onClick = { focusChannels() },
                        )
                    }
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
                                if (source == SearchSource) memory.searchFocus = item.key
                                wantChannels = true
                                onKeyboard = false
                            }
                            when (item) {
                                is ChannelItem -> {
                                    val on = item.channel.id == current?.id
                                    ChannelRow(
                                        item.channel, nowNext[item.channel.tvgId], clock,
                                        playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onClick = { choose(item.channel, null) },
                                    )
                                }
                                is LiveItem -> {
                                    val on = item.entry.tune.id == current?.id
                                    LiveRow(
                                        item.entry, clock, playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onClick = { choose(item.entry.tune, live?.context) },
                                    )
                                }
                                NoMatches -> Text("No matches on right now", style = Type.body, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m))
                                SlotsHeading -> SectionTitle("Event channels · not confirmed")
                                ChannelsHeading -> SectionTitle("Channels")
                                ProgrammesHeading -> SectionTitle("Programmes")
                                // Typing is not a row: a refresh while on the keyboard leaves focus there.
                                SearchBoxItem -> SearchBox(
                                    query, busy = memory.searching, onQuery = { memory.query = it }, entry = keyFocus,
                                    onFocused = {
                                        wantChannels = false
                                        onKeyboard = true
                                        memory.searchFocus = null
                                    },
                                    onDown = { focusChannels(list.indexOfFirst { it.focusable }) },
                                )
                                is HitItem -> {
                                    val on = item.hit.tune.id == current?.id
                                    HitRow(
                                        item.hit, clock, playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onPick = { choose(item.hit.tune, null) },
                                    )
                                }
                                is Note -> Empty(item.title, item.hint)
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

/** A section's title in the channel column, in sentence case. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Type.caption, modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs))
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
internal fun RowFrame(
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
 * held empty at full height, so rows never jump as data fills in. [watchHint] puts [WATCH_HINT] in
 * place of next.
 */
@Composable
internal fun ChannelRow(
    channel: Channel,
    guide: NowNext?,
    clock: State<Long>,
    playing: Boolean,
    watchHint: Boolean,
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
        if (watchHint) Hint(WATCH_HINT)
        else Text(remember(next) { next?.let { "Next · ${clockTime(it.start)} ${it.title}" } ?: "" }, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A match: its title first, then the channel it tunes and the competition. Slots say they're
 * unconfirmed. [watchHint] puts [WATCH_HINT] in place of the competition.
 */
@Composable
private fun LiveRow(entry: LiveEntry, clock: State<Long>, playing: Boolean, watchHint: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
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
            Text(if (programme == null) "Not confirmed" else if (entry.alsoOn > 0) "$channel · +${entry.alsoOn} more" else channel, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (programme != null) Progress(programme, clock)
        }
        if (watchHint) Hint(WATCH_HINT)
        else Text(entry.comp ?: entry.via.group, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Repaints from the shared minute clock in the draw phase; the row never recomposes for it. */
@Composable
internal fun Progress(programme: Programme, clock: State<Long>) {
    ProgressLine(
        fraction = { (clock.value - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1) },
        modifier = Modifier.width(INLINE_PROGRESS),
    )
}

/** On the playing row while the list previews: OK on it again goes full screen. */
internal const val WATCH_HINT = "Playing · OK to watch full screen"

/** A quiet note in place of a row's last line. */
@Composable
internal fun Hint(text: String) {
    Text(text, style = Type.caption.copy(color = Palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The channel playing now. */
@Composable
internal fun PlayingDot() {
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
