package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.ui.KeyAction
import com.mohamed3on.iptv.ui.PlayerKeys
import com.mohamed3on.iptv.ui.RemoteKey
import com.mohamed3on.iptv.ui.keyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ZapOrderTest {
    private val sky = "🏴 Sky Sports & UK Sports"
    private val pl = "🏆 Premier League"

    private fun ch(id: Int, group: String) = Channel(id = id, name = "C$id", group = group, home = sky, family = "$sky|$id", rank = 0)

    private fun feed(vararg channels: Channel) =
        Feed(channels.map { it.group }.distinct(), ProviderRoute("http://p", "http://v", "u", "p"), channels.toList())

    private fun zap(key: RemoteKey, from: Channel, feed: Feed) = keyAction(key, PlayerKeys(from, null, null), feed)

    private val home = feed(ch(1, sky), ch(2, sky), ch(3, sky))

    @Test fun downOnTheLastChannelWrapsToTheFirst() {
        assertEquals(KeyAction.Zap(ch(1, sky), down = true), zap(RemoteKey.Down, ch(3, sky), home))
    }

    @Test fun upOnTheFirstChannelWrapsToTheLast() {
        assertEquals(KeyAction.Zap(ch(3, sky), down = false), zap(RemoteKey.Up, ch(1, sky), home))
    }

    @Test fun downAndUpStepThroughTheGroupInServerOrder() {
        assertEquals(KeyAction.Zap(ch(3, sky), down = true), zap(RemoteKey.Down, ch(2, sky), home))
        assertEquals(KeyAction.Zap(ch(1, sky), down = false), zap(RemoteKey.Up, ch(2, sky), home))
    }

    // The 🏆 copies of 3 and 1 share their stream ids with the home rows, in a different order.
    @Test fun zappingInsideATrophyGroupFollowsThatGroupsOrder() {
        val f = feed(ch(3, pl), ch(1, pl), ch(1, sky), ch(2, sky), ch(3, sky))
        assertEquals(KeyAction.Zap(ch(1, pl), down = true), zap(RemoteKey.Down, ch(3, pl), f))
        assertEquals(KeyAction.Zap(ch(3, pl), down = true), zap(RemoteKey.Down, ch(1, pl), f))
        assertEquals(KeyAction.Zap(ch(1, pl), down = false), zap(RemoteKey.Up, ch(3, pl), f))
    }

    // 🏆 groups change every 30 min: after a refresh drops the group, zapping continues in the home group (KTD5).
    @Test fun aVanishedTrophyGroupZapsOnInTheHomeGroup() {
        assertEquals(KeyAction.Zap(ch(3, sky), down = true), zap(RemoteKey.Down, ch(2, pl), home))
    }
}
