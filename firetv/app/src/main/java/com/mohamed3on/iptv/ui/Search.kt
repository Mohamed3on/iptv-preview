package com.mohamed3on.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.focusLift
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import android.view.KeyEvent as AndroidKeyEvent

/** The server's search form of a title: accents stripped, lowercased ("München" → "munchen"). */
fun searchNorm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/** A programme found by search, on the best feed of the channel family airing it. */
data class SearchHit(val programme: Programme, val tune: Channel, val comp: String?)

/** What a search for [query] found: programmes (live first, then by start) and event/PPV slots by name. */
data class SearchResults(val query: String, val hits: List<SearchHit>, val slots: List<Channel>)

/**
 * Event search (R7, R26): programmes in the guide from now to a day ahead whose title contains the
 * query anywhere, one row per (channel family, programme), so a channel's HD, FHD and 4K feeds count
 * as one; then event/PPV slot channels whose name contains it. The guide returns rows in start order,
 * and every row still runs past now, so the live ones already come first.
 */
fun Feed.search(guide: GuideDb?, query: String, nowSec: Long): SearchResults {
    val q = searchNorm(query).trim()
    val byTvgId = channels.filter { it.tvgId.isNotEmpty() }.groupBy { it.tvgId }
    val seen = HashSet<Triple<String, String, Long>>()
    val hits = guide?.search(q, nowSec).orEmpty().flatMap { a ->
        byTvgId[a.tvgId].orEmpty().map { it.family }.distinct()
            .filter { seen.add(Triple(it, a.norm, a.programme.start)) }
            .map { SearchHit(a.programme, familyMembers(it).first(), a.comp) }
    }
    val slots = channels.filter { it.slot && q in searchNorm(displayName(it.name)) }
        .distinctBy { it.family }.map { familyMembers(it.family).first() }
    return SearchResults(query, hits, slots)
}

/** "Today 21:00" or "Tomorrow 15:30": search looks a day ahead at most. */
internal fun startLabel(startSec: Long, nowSec: Long): String {
    val zone = ZoneId.systemDefault()
    val day = { s: Long -> Instant.ofEpochSecond(s).atZone(zone).toLocalDate() }
    return "${if (day(startSec) == day(nowSec)) "Today" else "Tomorrow"} ${clockTime(startSec)}"
}

private val KEY_HEIGHT = 44.dp
private val CARET = DpSize(2.dp, 28.dp)
private const val SPACE = "Space"
private const val DELETE = "Delete"
/** Three rows of 13 cells: a–m, n–z, then 0–9 with Space and Delete taking a cell and a half each. */
private val KEYS: List<List<String>> =
    listOf("abcdefghijklm", "nopqrstuvwxyz", "0123456789").map { row -> row.map(Char::toString) }
        .let { it.dropLast(1) + listOf(it.last() + listOf(SPACE, DELETE)) }

/**
 * The query (with a caret) over a compact on-screen keyboard, so typing never covers the video with
 * the system IME. Left from the first column goes back to the groups (the overlay handles it); Down
 * from the last row calls [onDown]. [entry] focuses the key last used.
 */
@Composable
internal fun SearchBox(query: String, onQuery: (String) -> Unit, entry: FocusRequester, onFocused: () -> Unit, onDown: () -> Unit) {
    var at by remember { mutableStateOf(0 to 0) }
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(query, style = Type.title, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.padding(start = 2.dp).size(CARET).clip(RoundedCornerShape(1.dp)).background(Palette.accent))
        }
        Column(
            Modifier.onKeyEvent {
                val (r, c) = at
                val down = it.type == KeyEventType.KeyDown
                when (it.nativeKeyEvent.keyCode) {
                    // The overlay sends Left and Right to the groups and the schedule, so the keys move themselves.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> (c > 0).also { inside -> if (inside && down) focusManager.moveFocus(FocusDirection.Left) }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true.also { if (down && c < KEYS[r].lastIndex) focusManager.moveFocus(FocusDirection.Right) }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> r == 0
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> (r == KEYS.lastIndex).also { last -> if (last && down) onDown() }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            KEYS.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    row.forEachIndexed { c, key ->
                        val word = key.length > 1
                        KeyCap(
                            key,
                            style = if (word) Type.caption.copy(color = Palette.textPrimary) else Type.headline,
                            modifier = Modifier.weight(if (word) 1.5f else 1f),
                            focus = if (at == r to c) entry else null,
                            onFocused = { at = r to c; onFocused() },
                        ) {
                            onQuery(
                                when (key) {
                                    DELETE -> query.dropLast(1)
                                    SPACE -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
                                    else -> query + key
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, style: TextStyle, modifier: Modifier, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .height(KEY_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusLift(focused, Radius.chip)
            .background(Palette.edge)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, maxLines = 1)
    }
}

/**
 * A programme found by search: its title, then the channel (with progress when live, else when it
 * starts), then the competition or group. OK tunes a live one; on an upcoming one it only says when.
 */
@Composable
internal fun HitRow(hit: SearchHit, clock: State<Long>, playing: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onPick: () -> Unit) {
    val p = hit.programme
    val live by remember(p) { derivedStateOf { p.start <= clock.value } }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(2_500)
            hint = false
        }
    }
    val title = remember(p.title) { fixtureOf(p.title) }
    val channel = remember(hit.tune.name) { displayName(hit.tune.name) }
    RowFrame(hit.tune, focus, onFocused, onClick = { if (live) onPick() else hint = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            if (live && p.match) LiveMark()
            Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                if (live) channel else "$channel · ${startLabel(p.start, clock.value)}",
                style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (live) Progress(p, clock)
        }
        Text(
            if (hint) "Starts at ${clockTime(p.start)}" else hit.comp ?: hit.tune.group,
            style = if (hint) Type.caption.copy(color = Palette.textSecondary) else Type.caption,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
