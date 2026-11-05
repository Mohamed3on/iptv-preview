package com.mohamed3on.iptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusProperties
import com.mohamed3on.iptv.ui.theme.material
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.RdStatus
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.Capacity.Source
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.Correction
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.Ranked
import com.mohamed3on.iptv.player.Ranking
import com.mohamed3on.iptv.player.Support
import com.mohamed3on.iptv.player.TitleAnswer
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import kotlin.math.roundToInt

/** Versions shows this many copies before "Show all". */
private const val FIRST_ROWS = 8

/** What Versions lists: a title's ranking, Real-Debrid's answers, the box's answer (sizes, names) and the copy playing, if any. */
class VersionsData(val ranking: Ranking?, val rd: Map<String, RdCheck>, val answer: TitleAnswer?, val playing: String? = null, val failed: Boolean = false)

/** How a copy stands here, in the order Versions groups them (R11). */
private enum class Standing(val heading: String) { Plays("Plays here"), Untested("Untested here"), NotReady("Not ready on Real-Debrid"), Wont("Won’t play here") }

private fun standing(r: Ranked, rd: Map<String, RdCheck>) = when {
    r.support == Support.Wont -> Standing.Wont
    r.copy.source == Source.RealDebrid && rd[r.copy.id] !is RdCheck.Ready -> Standing.NotReady
    r.support == Support.Unsure -> Standing.Untested
    else -> Standing.Plays
}

/**
 * Every copy of [title], grouped by how it stands on this setup, each with its labels, picture tier,
 * source, size and why it ranks where it does (R11, R12). OK plays a copy and pins it ([onChoose]); Menu on
 * a pinned or marked one clears that (R13). Below: what the setup reported and the typical bandwidth the
 * pick used, the corrections (R14) and the sources' states. [onChanged] re-ranks after any change.
 */
@Composable
internal fun Versions(title: String, data: VersionsData, first: FocusRequester, onChoose: (Copy) -> Unit, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val choices = graph.copyChoices
    val setup by graph.setup.profile.collectAsState()
    val debridStatus by graph.debrid.status.collectAsState()
    var all by remember { mutableStateOf(false) }
    // "Show all" leaves the list as it's pressed: focus moves on to the first copy it brought in.
    val more = remember { FocusRequester() }
    LaunchedEffect(all) {
        if (!all) return@LaunchedEffect
        withFrameNanos {}
        runCatching { more.requestFocus() }
    }
    var corrections by remember(setup) { mutableStateOf(choices.corrections(setup)) }
    val ranking = data.ranking
    val rows = remember(ranking, data.rd) {
        ranking?.copies.orEmpty().map { it to standing(it, data.rd) }.sortedBy { it.second.ordinal }
    }
    // Reasons are worded against the copy Play opens, as the Plays line names it: the best one ready.
    val plays = ranking?.copies?.firstOrNull {
        (it.support != Support.Wont || it.pinned) && (it.copy.source == Source.Iptv || data.rd[it.copy.id] is RdCheck.Ready)
    }?.copy?.id
    val state = rememberLazyListState()
    LazyColumn(modifier.scrollbar(state), state = state, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (ranking == null) {
            item(key = "loading") {
                Column(Modifier.padding(Space.l).focusRequester(first).focusable(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    if (data.failed) Text("The box didn’t list copies. Play uses the library’s copy.", style = Type.body)
                    else {
                        Text("Finding copies…", style = Type.body)
                        ActivityLine()
                    }
                }
            }
        }
        val shown = if (all) rows else rows.take(FIRST_ROWS)
        shown.forEachIndexed { i, (r, s) ->
            if (i == 0 || shown[i - 1].second != s) item(key = "h${s.name}") { Heading(s.heading) }
            item(key = r.copy.id) {
                VersionRow(r, ranking!!.reasonFor(r, plays), data, focus = if (i == 0) first else if (i == FIRST_ROWS) more else null, onClick = { onChoose(r.copy) }, onClear = if (r.pinned || r.mark != null) ({
                    if (r.pinned) choices.pin(title, null) else choices.clearMark(setup, r.copy.id)
                    onChanged()
                }) else null)
            }
        }
        if (rows.size > shown.size) item(key = "all") { PanelRow("Show all", "${rows.size} copies") { all = true } }
        item(key = "setup") {
            Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Why this pick", style = Type.headline)
                Text(setupLine(setup.hdr, setup.passthrough, setup.maxChannels), style = Type.caption)
                Text(bandwidthLine(graph.capacity::typicalMbps), style = Type.caption)
            }
        }
        item(key = "corrections") { Heading("Corrections") }
        items(Correction.entries, key = { it.name }) { c ->
            PanelRow(CORRECTIONS.getValue(c), if (c in corrections) "On" else "Off") {
                choices.correct(setup, c, c !in corrections)
                corrections = choices.corrections(setup)
                onChanged()
            }
        }
        item(key = "sources") { Heading("Sources") }
        item(key = "rd") { PanelRow("Real-Debrid", rdStatus(debridStatus, data.answer)) {} }
        item(key = "iptv") { PanelRow("IPTV", if (data.answer?.iptvUnavailable == true) "Unavailable" else "Ready") {} }
    }
}

/**
 * Versions over a title page, from the right, on the title's prepared copies (opened from cached state, so
 * at once). OK pins the copy and plays it ([onPlay]); Back closes it ([onClose]).
 */
@Composable
internal fun VersionsPanel(title: String, onPlay: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val p by remember(title) { graph.films.state(title) }.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(p.ranking == null) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onClose()
                true
            }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
    ) {
        Text("Versions", style = Type.title, modifier = Modifier.padding(start = Space.l, bottom = Space.l))
        Versions(
            title, VersionsData(p.ranking, p.rd, p.answer, failed = p.failed), first,
            onChoose = {
                graph.copyChoices.pin(title, it.id)
                onClose()
                onPlay()
            },
            onChanged = { graph.films.rerank(title) },
            modifier = Modifier.weight(1f),
        )
    }
}

private val PANEL_WIDTH = 420.dp

@Composable
private fun Heading(text: String) {
    Text(text, style = Type.headline, modifier = Modifier.padding(start = Space.l, top = Space.m, bottom = Space.xs))
}

/** A copy: tier and labels, then why it ranks where it does, then source, size and release name. */
@Composable
private fun VersionRow(r: Ranked, reason: String, data: VersionsData, focus: FocusRequester?, onClick: () -> Unit, onClear: (() -> Unit)?) {
    val c = r.copy
    val status = listOfNotNull(
        reason.ifEmpty { null },
        rdState(c, data.rd),
        onClear?.let { "Menu clears" },
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onKeyEvent {
                if (onClear == null || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onClear()
                true
            }
            .pressable(onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            copyTier(c)?.let { TierChip(it) }
            Text(copyLabels(c).joinToString(" · ").ifEmpty { "Quality unknown" }, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                c.id == data.playing -> Text("Playing", style = Type.caption.copy(color = Palette.accent), maxLines = 1)
                r.pinned -> Text("Pinned", style = Type.caption.copy(color = Palette.accent), maxLines = 1)
            }
        }
        if (status.isNotEmpty()) Text(status, style = Type.caption.copy(color = Palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(sourceName(c.source), sizeLabel(data.answer?.size(c.id) ?: 0), data.answer?.name(c.id)?.ifEmpty { null }).joinToString(" · "),
            style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A thin accent thumb at the right of an inner list that scrolls, read while drawing. */
internal fun Modifier.scrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible >= total) return@drawWithContent
    val w = 3.dp.toPx()
    val h = (size.height * visible / total).coerceAtLeast(24.dp.toPx())
    val top = (size.height - h) * (state.firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
    drawRoundRect(Palette.accent, topLeft = Offset(size.width - w, top), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
}

internal fun sourceName(source: Source) = if (source == Source.RealDebrid) "Real-Debrid" else "IPTV"

/** "4K", "HDR10", "HEVC", "E-AC3 5.1 Atmos", "DE", "18 Mbps": what is known of a copy. */
internal fun copyLabels(c: Copy): List<String> = listOfNotNull(
    when {
        c.resolution >= 2000 -> "4K"
        c.resolution > 0 -> "${c.resolution}p"
        else -> null
    },
    c.hdr.firstOrNull()?.let { if (it == "DV") "Dolby Vision" + (c.dvProfile?.let { p -> " $p" } ?: "") else it },
    VIDEO[c.codec],
    listOfNotNull(AUDIO_LABEL[c.audio], c.channels.takeIf { it > 0 }?.let(::channelLayout), "Atmos".takeIf { c.atmos }).joinToString(" ").ifEmpty { null },
    c.languages.joinToString("/") { if (it == "multi") "Multi" else it.uppercase() }.ifEmpty { null },
    c.mbps.takeIf { it > 0f }?.let { "${it.roundToInt()} Mbps" },
)

/** The picture in a word, as the server's `pictureTier` gives it: resolution, capped by codec-adjusted bits per pixel when the bitrate is known. */
internal fun copyTier(c: Copy): String? {
    val (w, h) = when {
        c.resolution >= 2000 -> 3840 to 2160
        c.resolution >= 1000 -> 1920 to 1080
        c.resolution >= 700 -> 1280 to 720
        c.resolution > 0 -> 720 to 480
        else -> return null
    }
    val cap = when {
        h >= 2000 -> 0
        h >= 1000 -> 1
        h >= 700 -> 2
        else -> 3
    }
    val gain = GAIN[c.codec] ?: if (h >= 2000) 2f else 1f
    val bpp = c.mbps * 1_000_000 * gain / (w * h * 24f)
    val bits = when {
        c.mbps <= 0f || bpp >= 0.24f -> 0
        bpp >= 0.14f -> 1
        bpp >= 0.07f -> 2
        else -> 3
    }
    return TIERS[maxOf(cap, bits)]
}

/** "14.2 GB"; null when unknown. */
internal fun sizeLabel(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes shr 20} MB"
}

/** The copy as the title page and Options name it: "Real-Debrid · 4K · HDR10 · E-AC3 5.1 · 14.2 GB". */
internal fun copyLine(c: Copy, answer: TitleAnswer?): String =
    (listOf(sourceName(c.source)) + copyLabels(c) + listOfNotNull(sizeLabel(answer?.size(c.id) ?: 0))).joinToString(" · ")

private fun rdState(c: Copy, rd: Map<String, RdCheck>): String? {
    if (c.source != Source.RealDebrid) return null
    return when (val r = rd[c.id]) {
        is RdCheck.Ready -> "ready"
        RdCheck.NotCached -> "not cached"
        is RdCheck.Failed -> if (r.error.permanent) "refused by Real-Debrid" else "check failed"
        null -> "not checked"
    }
}

private fun rdStatus(status: RdStatus, answer: TitleAnswer?) = when (status) {
    RdStatus.NoToken -> "No key on the box"
    RdStatus.TokenInvalid -> "Key refused"
    RdStatus.RateLimited -> "Busy, adds paused"
    RdStatus.TooManyDownloads -> "Too many downloads"
    RdStatus.Denied -> "Refused (account or IP)"
    RdStatus.NotPremium -> "Premium ended"
    RdStatus.Ok -> when {
        answer?.rdPending == true -> "Searching…"
        answer?.rdUnavailable == true -> "Search unavailable"
        else -> "Ready"
    }
}

/** "TV shows HDR10, HLG · passes E-AC3, Atmos · up to 8 channels". */
private fun setupLine(hdr: Set<Hdr>, passthrough: Set<Passthrough>, channels: Int): String = listOfNotNull(
    "TV shows " + hdr.joinToString(", ") { HDR_NAMES.getValue(it) }.ifEmpty { "SDR only" },
    "passes " + passthrough.joinToString(", ") { PASS_NAMES.getValue(it) }.ifEmpty { "stereo only" },
    channels.takeIf { it > 0 }?.let { "up to $it channels" },
).joinToString(" · ")

/** "Typical IPTV 40 Mbps · Real-Debrid not measured yet": the bandwidth the ranking used, this hour. */
private fun bandwidthLine(typical: (Source, Long) -> Float?): String {
    val now = System.currentTimeMillis()
    return "Typical " + Source.entries.joinToString(" · ") { s -> "${sourceName(s)} " + (typical(s, now)?.let { "${it.roundToInt()} Mbps" } ?: "not measured yet") }
}

private val TIERS = listOf("Reference", "Great", "OK", "Soft")
private val GAIN = mapOf("h264" to 1f, "hevc" to 2f, "vp9" to 1.8f, "av1" to 2.4f, "mpeg2video" to 0.6f)
private val VIDEO = mapOf("hevc" to "HEVC", "h264" to "H.264", "av1" to "AV1", "vp9" to "VP9", "mpeg2video" to "MPEG-2", "vc1" to "VC-1")
private val AUDIO_LABEL = mapOf(
    "truehd" to "TrueHD", "dtshd" to "DTS-HD", "dtsx" to "DTS:X", "dts" to "DTS", "eac3" to "E-AC3", "ac3" to "AC3", "aac" to "AAC", "opus" to "Opus", "flac" to "FLAC",
)
private val HDR_NAMES = mapOf(Hdr.DolbyVision to "Dolby Vision", Hdr.Hdr10 to "HDR10", Hdr.Hlg to "HLG", Hdr.Hdr10Plus to "HDR10+")
private val PASS_NAMES = mapOf(
    Passthrough.Ac3 to "AC3", Passthrough.EAc3 to "E-AC3", Passthrough.EAc3Joc to "Atmos", Passthrough.Ac4 to "AC-4",
    Passthrough.Dts to "DTS", Passthrough.DtsHd to "DTS-HD", Passthrough.DtsX to "DTS:X", Passthrough.TrueHd to "TrueHD",
)
private val CORRECTIONS = mapOf(
    Correction.PassesTrueHd to "Sound passes TrueHD", Correction.PassesDts to "Sound passes DTS", Correction.IgnoreBandwidth to "Ignore the bandwidth limit",
)
