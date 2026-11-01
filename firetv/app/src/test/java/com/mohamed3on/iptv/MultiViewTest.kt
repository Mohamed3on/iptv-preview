package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.player.StreamPlayer
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.player.TilePlayer
import com.mohamed3on.iptv.ui.MultiView
import com.mohamed3on.iptv.ui.TILE_CAP
import com.mohamed3on.iptv.ui.tileFeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what it was told to play; no provider behind it. */
private class FakeTilePlayer : TilePlayer {
    val opens = mutableListOf<String>()
    var connected: String? = null
        private set
    var volume = 1f
        private set

    override fun open(url: String) {
        opens += url
        connected = url
    }

    override fun stop() {
        connected = null
    }

    override val position = 0L
    override fun setListener(listener: StreamPlayer.Listener?) {}
    override fun setVolume(volume: Float) {
        this.volume = volume
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MultiViewTest {
    private val provider = ProviderRoute(plain = "http://plain.test", vpn = "http://vpn.test", user = "u", pass = "p")

    private fun ch(id: Int, family: String, rank: Int, h: Int? = null, q: Int = 3) =
        Channel(id = id, name = "C$id", group = "G", home = "G", family = family, rank = rank, h = h, q = q)

    private fun feedOf(vararg channels: Channel) = Feed(listOf("G"), provider, channels.toList())

    private fun url(id: Int) = "http://plain.test/live/u/p/$id.ts"

    private class Rig(val multi: MultiView<FakeTilePlayer>, val main: StreamSession, val mainPlayer: FakeTilePlayer) {
        val acquired = mutableListOf<FakeTilePlayer>()
        val released = mutableListOf<FakeTilePlayer>()
    }

    private fun TestScope.rig(): Rig {
        val route = Route(MapStore(), MutableStateFlow(false), backgroundScope)
        val clock = { testScheduler.currentTime }
        val mainPlayer = FakeTilePlayer()
        lateinit var rig: Rig
        val multi = MultiView(
            acquire = { FakeTilePlayer().also { rig.acquired += it } },
            release = { rig.released += it },
            route = route,
            scope = backgroundScope,
            now = clock,
        )
        rig = Rig(multi, StreamSession(mainPlayer, route, backgroundScope, clock), mainPlayer)
        return rig
    }

    @Test fun aTilePlaysTheBestFeedProbedAt1080OrLess() {
        val uhd = ch(1, "f", 0, h = 2160, q = 5)
        val fhd = ch(2, "f", 1, h = 1080, q = 4)
        val hd = ch(3, "f", 2, h = 720, q = 3)
        assertEquals(fhd, tileFeed(feedOf(uhd, fhd, hd), uhd))
    }

    @Test fun aFamilyWithOnlyA4KFeedPlaysIt() {
        val uhd = ch(1, "f", 0, h = 2160, q = 5)
        assertEquals(uhd, tileFeed(feedOf(uhd), uhd))
    }

    @Test fun anUnprobed4KNamedFeedIsSkippedForAProbed1080pOne() {
        val named4k = ch(1, "f", 0, h = null, q = 5)
        val fhd = ch(2, "f", 1, h = 1080, q = 4)
        assertEquals(fhd, tileFeed(feedOf(named4k, fhd), named4k))
    }

    // AE7: two tiles, the right one selected; OK plays it full screen and stops the other.
    @Test fun promotingTheSelectedTilePlaysItFullScreenOnItsBestFeedAndReleasesEveryTile() = runTest {
        val r = rig()
        val left = ch(1, "a", 0, h = 1080)
        val rightBest = ch(2, "b", 0, h = 2160, q = 5)
        val rightHd = ch(3, "b", 1, h = 1080)
        val feed = feedOf(left, rightBest, rightHd)
        r.main.zap(left, feed)

        r.multi.enter(r.main, rightBest, feed)
        val (leftTile, rightTile) = r.acquired
        assertNull("full screen stops while the tiles play", r.mainPlayer.connected)
        assertEquals(url(3), rightTile.connected)
        r.multi.select(1)
        assertEquals(0f, leftTile.volume)
        assertEquals(1f, rightTile.volume)

        advanceTimeBy(1_000) // past the zap debounce since the full-screen tune
        r.multi.promote(r.main, feed)
        assertEquals(url(2), r.mainPlayer.connected)
        assertEquals(rightBest, r.main.state.value.requested)
        assertEquals(listOf(leftTile, rightTile), r.released)
        assertTrue(r.acquired.all { it.connected == null })
        assertTrue(r.multi.state.value.tiles.isEmpty())
    }

    @Test fun addingBeyondTheCapIsRefusedWithoutOpeningAnything() = runTest {
        val r = rig()
        val channels = (1..TILE_CAP + 1).map { ch(it, "f$it", 0) }
        val feed = feedOf(*channels.toTypedArray())
        r.main.zap(channels[0], feed)
        r.multi.enter(r.main, channels[1], feed)
        channels.subList(2, TILE_CAP).forEach { assertTrue(r.multi.add(it, feed)) }

        assertFalse(r.multi.add(channels[TILE_CAP], feed))
        assertEquals(TILE_CAP, r.acquired.size)
        assertEquals(TILE_CAP, r.multi.state.value.tiles.size)
    }
}
