package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.RouteChoice
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.player.AudioOption
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.focusLift
import com.mohamed3on.iptv.ui.theme.material
import java.util.Locale

private val PANEL_WIDTH = 420.dp

private enum class Page { Main, Audio, Connection }

/**
 * The Menu sheet beside the still-playing video: audio track (R13), connection route with the
 * effective route shown (R4), diagnostics. [views] is the slot for entries that open another view
 * (multi-view, U11, fills it with [PanelRow]s).
 * Back closes a nested list here; closing the panel itself is the player's Back (KeyMap).
 */
@Composable
fun QuickPanel(
    audio: AudioState,
    onAudio: (AudioOption) -> Unit,
    choice: RouteChoice,
    effective: RouteKind,
    onRoute: (RouteChoice) -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    open: Boolean = true,
    views: @Composable ColumnScope.() -> Unit = {},
) {
    val stay by rememberUpdatedState(open)
    var page by remember { mutableStateOf(Page.Main) }
    // The row a nested list was opened from gets focus back when it closes.
    var openedFrom by remember { mutableStateOf<Page?>(null) }
    val audioRow = remember { FocusRequester() }
    val connectionRow = remember { FocusRequester() }
    val diagnosticsRow = remember { FocusRequester() }

    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (page == Page.Main || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) {
                    openedFrom = page
                    page = Page.Main
                }
                true
            }
            // Focus stays in the panel while it is open: Left/Right never land on the video underneath.
            // Once it closes, focus may leave at once (to the list that Multi-view opens, say).
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
            label = "page",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.fillMaxHeight()) {
                Text(
                    when (shown) {
                        Page.Main -> "Options"
                        Page.Audio -> "Audio"
                        Page.Connection -> "Connection"
                    },
                    style = Type.title,
                    modifier = Modifier.padding(start = Space.l, bottom = Space.l),
                )
                when (shown) {
                    Page.Main -> {
                        val hasAudio = audio.options.size > 1
                        LaunchedEffect(Unit) {
                            when (openedFrom) {
                                Page.Audio -> audioRow
                                Page.Connection -> connectionRow
                                else -> if (hasAudio) audioRow else connectionRow
                            }.requestFocus()
                        }
                        if (hasAudio) {
                            PanelRow("Audio", audio.selected?.let(::languageName), focus = audioRow) { page = Page.Audio }
                        }
                        PanelRow("Connection", routeValue(choice, effective), focus = connectionRow) { page = Page.Connection }
                        // Multi-view (U11), set apart from the settings above.
                        Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs), content = views)
                        Spacer(Modifier.height(Space.l))
                        PanelRow("Diagnostics", focus = diagnosticsRow, onClick = onDiagnostics)
                    }
                    Page.Audio -> {
                        val selected = remember { FocusRequester() }
                        LaunchedEffect(Unit) { selected.requestFocus() }
                        audio.options.forEachIndexed { i, option ->
                            val isSelected = option == audio.selected || (audio.selected == null && i == 0)
                            PanelRow(
                                audioLabel(option),
                                checked = option == audio.selected,
                                focus = if (isSelected) selected else null,
                            ) { onAudio(option) }
                        }
                    }
                    Page.Connection -> {
                        val selected = remember { FocusRequester() }
                        LaunchedEffect(Unit) { selected.requestFocus() }
                        for (option in RouteChoice.entries) {
                            PanelRow(
                                routeName(option),
                                value = if (option == RouteChoice.Auto) "Now ${routeName(effective)}" else null,
                                checked = option == choice,
                                focus = if (option == choice) selected else null,
                            ) { onRoute(option) }
                        }
                        Text(
                            "Auto follows the VPN on this device.",
                            style = Type.caption,
                            modifier = Modifier.padding(start = Space.l, top = Space.m),
                        )
                    }
                }
            }
        }
    }
}

/** A panel entry: a label, its current value at the right, and a check when it is the selected option. */
@Composable
fun PanelRow(
    label: String,
    value: String? = null,
    checked: Boolean = false,
    focus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .pressable(onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(label, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.body, maxLines = 1)
        if (checked) Text("✓", style = Type.headline.copy(color = Palette.accent))
    }
}

/** Focusable and pressed with OK, lifting toward the viewer while focused. */
@Composable
internal fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return focusLift(focused).clickable(interaction, indication = null, onClick = onClick)
}

private fun routeName(kind: RouteKind) = if (kind == RouteKind.Vpn) "VPN" else "Plain"

private fun routeName(choice: RouteChoice) = when (choice) {
    RouteChoice.Auto -> "Auto"
    RouteChoice.Plain -> "Plain"
    RouteChoice.Vpn -> "VPN"
}

/** "Auto · VPN"; a one-press wrong-route switch can make the route differ from the choice for a while. */
private fun routeValue(choice: RouteChoice, effective: RouteKind) =
    if (choice == RouteChoice.Auto || routeName(choice) != routeName(effective)) "${routeName(choice)} · ${routeName(effective)}"
    else routeName(choice)

internal fun languageName(option: AudioOption): String =
    option.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
        ?: "Unknown language"

/** "English · AC3 · 5.1". */
internal fun audioLabel(option: AudioOption): String =
    listOfNotNull(languageName(option), option.codec, channelLayout(option.channels)).joinToString(" · ")

/** "5.1", "Stereo"; null when unknown. */
internal fun channelLayout(channels: Int): String? = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (channels > 0) "$channels ch" else null
}
