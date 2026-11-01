package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.WatchHistory
import com.mohamed3on.iptv.ui.KeyAction
import com.mohamed3on.iptv.ui.Layer
import com.mohamed3on.iptv.ui.PlayerKeys
import com.mohamed3on.iptv.ui.RemoteKey
import com.mohamed3on.iptv.ui.keyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapTest {
    private val g = "🇬🇧 UK"
    private val a = Channel(id = 1, name = "A", group = g, home = g, family = "$g|a", rank = 0)
    private val b = Channel(id = 2, name = "B", group = g, home = g, family = "$g|b", rank = 0)
    private val feed = Feed(listOf(g), ProviderRoute("http://p", "http://v", "u", "p"), listOf(a, b))

    /** The player's key context after [watched] played in order, with [current] requested. */
    private fun screen(current: Channel, vararg watched: Channel, layer: Layer = Layer.None): PlayerKeys {
        val history = WatchHistory(MapStore()).apply { watched.forEach(::watched) }
        return PlayerKeys(current, history.last, history.previous, layer)
    }

    // AE2: watched A then B; Back plays A.
    @Test fun backWithAPreviousChannelTunesIt() {
        assertEquals(KeyAction.Previous(a), keyAction(RemoteKey.Back, screen(b, a, b), feed))
    }

    @Test fun backTogglesBetweenTheLastTwoChannels() {
        assertEquals(KeyAction.Previous(b), keyAction(RemoteKey.Back, screen(a, a, b, a), feed))
    }

    // B was requested but hasn't played yet, so A is still the last channel watched.
    @Test fun backWhileTheNewChannelIsStillConnectingReturnsToTheOneBefore() {
        assertEquals(KeyAction.Previous(a), keyAction(RemoteKey.Back, screen(b, a), feed))
    }

    @Test fun backWithTheChannelListOpenClosesIt() {
        assertEquals(KeyAction.Close, keyAction(RemoteKey.Back, screen(b, a, b, layer = Layer.ChannelList), feed))
    }

    @Test fun backWithNoPreviousChannelAndNothingOpenDoesNothing() {
        assertEquals(KeyAction.Ignore, keyAction(RemoteKey.Back, screen(a, a), feed))
    }

    @Test fun okOpensTheChannelListAtTheCurrentChannel() {
        assertEquals(KeyAction.OpenOverlay(b), keyAction(RemoteKey.Ok, screen(b, a, b), feed))
    }

    @Test fun menuOpensTheQuickPanelAndBackOrMenuClosesIt() {
        assertEquals(KeyAction.OpenPanel, keyAction(RemoteKey.Menu, screen(b, a, b), feed))
        assertEquals(KeyAction.Close, keyAction(RemoteKey.Back, screen(b, a, b, layer = Layer.QuickPanel), feed))
        assertEquals(KeyAction.Close, keyAction(RemoteKey.Menu, screen(b, a, b, layer = Layer.QuickPanel), feed))
    }

    // Diagnostics stay reachable when nothing could be tuned.
    @Test fun menuOpensTheQuickPanelEvenWithNothingPlaying() {
        assertEquals(KeyAction.OpenPanel, keyAction(RemoteKey.Menu, PlayerKeys(null, null, null), feed))
    }

    // The panel and the list move their own focus: the player must not zap underneath them.
    @Test fun dpadAndOkGoToAnOpenLayerNotThePlayer() {
        for (layer in listOf(Layer.QuickPanel, Layer.ChannelList, Layer.Diagnostics)) {
            for (key in listOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Ok)) {
                assertEquals("$key in $layer", KeyAction.Pass, keyAction(key, screen(b, a, b, layer = layer), feed))
            }
        }
    }

    @Test fun backFromDiagnosticsClosesIt() {
        assertEquals(KeyAction.Close, keyAction(RemoteKey.Back, screen(b, a, b, layer = Layer.Diagnostics), feed))
    }
}
