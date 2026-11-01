package com.mohamed3on.iptv.ui

import android.view.KeyEvent
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Watched
import com.mohamed3on.iptv.data.resolve

/** The remote keys the full-screen player answers to (R10). */
enum class RemoteKey { Up, Down, Left, Right, Ok, Menu, Back }

fun remoteKey(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.Right
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.Ok
    KeyEvent.KEYCODE_MENU -> RemoteKey.Menu
    KeyEvent.KEYCODE_BACK -> RemoteKey.Back
    else -> null
}

/** What sits over the video. Back closes it before anything else. */
enum class Layer { None, ChannelList, QuickPanel, Diagnostics }

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
)

sealed interface KeyAction {
    data class Zap(val to: Channel, val down: Boolean) : KeyAction
    /** Back to the channel watched before this one. */
    data class Previous(val to: Channel) : KeyAction
    /** The channel list (U9), focused on [at]. */
    data class OpenOverlay(val at: Channel) : KeyAction
    data object OpenPanel : KeyAction
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
        else -> KeyAction.Pass
    }
    if (key == RemoteKey.Menu) return KeyAction.OpenPanel
    if (screen.multiView) return if (key == RemoteKey.Ok || key == RemoteKey.Back) KeyAction.Promote else KeyAction.MoveTile(key)
    val current = screen.current ?: return KeyAction.Ignore
    return when (key) {
        RemoteKey.Left, RemoteKey.Right -> KeyAction.Ignore
        RemoteKey.Up, RemoteKey.Down -> {
            val down = key == RemoteKey.Down
            val next = screen.context?.let { neighbour(it, current, down) } ?: feed.zapNeighbour(current, down)
            next?.let { KeyAction.Zap(it, down) } ?: KeyAction.Ignore
        }
        RemoteKey.Ok -> KeyAction.OpenOverlay(current)
        RemoteKey.Menu -> KeyAction.OpenPanel // handled above, with or without a channel
        RemoteKey.Back -> {
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

/** The channel after or before [from] in [list], wrapping; null when [from] is not in it or alone. */
private fun neighbour(list: List<Channel>, from: Channel, down: Boolean): Channel? {
    val i = list.indexOfFirst { it.identity == from.identity }
    if (i < 0) return null
    return list[(i + if (down) 1 else -1).mod(list.size)].takeIf { it.identity != from.identity }
}
