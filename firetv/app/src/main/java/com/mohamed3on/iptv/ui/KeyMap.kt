package com.mohamed3on.iptv.ui

import android.view.KeyEvent
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Watched
import com.mohamed3on.iptv.data.resolve

/** The remote keys the full-screen player answers to (R10). */
enum class RemoteKey { Up, Down, Left, Right, Ok, Menu, Back, FastForward, Rewind }

fun remoteKey(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.Right
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.Ok
    KeyEvent.KEYCODE_MENU -> RemoteKey.Menu
    KeyEvent.KEYCODE_BACK -> RemoteKey.Back
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.FastForward
    KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.Rewind
    else -> null
}

/** What sits over the video. Back closes it before anything else. */
enum class Layer { None, ChannelList, Recent, QuickPanel, Diagnostics }

/**
 * What a key press needs to know: the requested channel, the watch history, what is open, and the
 * zap context when the channel was tuned from a list other than its group (Live now); null = its group;
 * and whether multi-view's tiles are on screen.
 */
data class PlayerKeys(
    val current: Channel?,
    val last: Watched?,
    val previous: Watched?,
    val layer: Layer = Layer.None,
    val context: List<Channel>? = null,
    val multiView: Boolean = false,
    /** The info view (banner with the stream's numbers) is up. */
    val info: Boolean = false,
)

sealed interface KeyAction {
    data class Zap(val to: Channel, val down: Boolean) : KeyAction
    /** Back to the channel watched before this one. */
    data class Previous(val to: Channel) : KeyAction
    /** The channel list (U9), focused on [at]'s row, or on its group in the groups column with [groups]. */
    data class OpenOverlay(val at: Channel, val groups: Boolean = false) : KeyAction
    data object OpenPanel : KeyAction
    /** The channels watched last, in a sheet from the right. */
    data object OpenRecent : KeyAction
    /** The banner with the stream's numbers and health, until Back (or OK, which goes on to the list). */
    data object ShowInfo : KeyAction
    data object HideInfo : KeyAction
    /** The next (or previous) match in Live now, which becomes the zap context. */
    data class LiveZap(val down: Boolean) : KeyAction
    /** Multi-view: moves the selection, and the sound, toward [key]. */
    data class MoveTile(val key: RemoteKey) : KeyAction
    /** Multi-view: the selected tile goes full screen; the others stop (R18, AE7). */
    data object Promote : KeyAction
    /** Closes the open layer. */
    data object Close : KeyAction
    /** Swallowed with no effect: Back must never leave the app from the player (Home does). */
    data object Ignore : KeyAction
    /** Not the player's key: the open layer moves its own focus with it. */
    data object Pass : KeyAction
}

/** The remote key map from the plan's High-Level Technical Design, as a pure function. */
fun keyAction(key: RemoteKey, screen: PlayerKeys, feed: Feed): KeyAction {
    if (screen.layer != Layer.None) return when {
        key == RemoteKey.Back -> KeyAction.Close
        key == RemoteKey.Menu && screen.layer == Layer.QuickPanel -> KeyAction.Close
        // Recent came in with Right; Left sends it back the way it came.
        key == RemoteKey.Left && screen.layer == Layer.Recent -> KeyAction.Close
        else -> KeyAction.Pass
    }
    if (key == RemoteKey.Menu) return KeyAction.OpenPanel
    if (screen.multiView) return when (key) {
        RemoteKey.Ok, RemoteKey.Back -> KeyAction.Promote
        RemoteKey.FastForward, RemoteKey.Rewind -> KeyAction.Ignore
        else -> KeyAction.MoveTile(key)
    }
    val current = screen.current ?: return KeyAction.Ignore
    return when (key) {
        RemoteKey.Left -> KeyAction.OpenOverlay(current, groups = true)
        RemoteKey.Right -> KeyAction.OpenRecent
        RemoteKey.FastForward, RemoteKey.Rewind -> KeyAction.LiveZap(down = key == RemoteKey.FastForward)
        RemoteKey.Up, RemoteKey.Down -> {
            val down = key == RemoteKey.Down
            val next = screen.context?.let { neighbour(it, current, down) } ?: feed.zapNeighbour(current, down)
            next?.let { KeyAction.Zap(it, down) } ?: KeyAction.Ignore
        }
        // OK shows more each press: the info view, then the channel list.
        RemoteKey.Ok -> if (screen.info) KeyAction.OpenOverlay(current) else KeyAction.ShowInfo
        RemoteKey.Menu -> KeyAction.OpenPanel // handled above, with or without a channel
        RemoteKey.Back -> {
            if (screen.info) return KeyAction.HideInfo
            // History records a channel once it plays, so until the requested one does, the last is the one before it.
            val before = screen.last?.takeIf { it.identity != current.identity } ?: screen.previous
            before?.let(feed::resolve)?.takeIf { it.identity != current.identity }?.let(KeyAction::Previous) ?: KeyAction.Ignore
        }
    }
}

/**
 * The next or previous channel in [from]'s own group, wrapping at the ends: zapping inside a 🏆
 * group follows that group (KTD5). If a refresh dropped the group, zapping continues where the
 * channel resolves now (its home group).
 */
private fun Feed.zapNeighbour(from: Channel, down: Boolean): Channel? {
    val here = find(from.identity) ?: resolve(Watched.of(from)) ?: return null
    return neighbour(channelsIn(here.group), here, down)
}

/**
 * The channel after or before [from] in [list], wrapping; null when [from] is not in it or alone.
 * A spare feed (from Recent, say) stands at its family's row.
 */
private fun neighbour(list: List<Channel>, from: Channel, down: Boolean): Channel? {
    val i = list.indexOfFirst { it.identity == from.identity }.takeIf { it >= 0 } ?: list.indexOfFirst { it.family == from.family }
    if (i < 0) return null
    return list[(i + if (down) 1 else -1).mod(list.size)].takeIf { it.family != from.family }
}

/** The step [down] (or up) through [list] from [from]; from outside it, its first (or last) channel. */
internal fun stepThrough(list: List<Channel>, from: Channel?, down: Boolean): Channel? =
    if (from != null && list.any { it.family == from.family }) neighbour(list, from, down)
    else if (down) list.firstOrNull() else list.lastOrNull()
