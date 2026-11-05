package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.FAVOURITES
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Lineup
import com.mohamed3on.iptv.data.ProviderRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class LineupTest {
    private val ru = "🇷🇺 Russia"
    private fun ch(id: Int, group: String = ru) = Channel(id = id, name = "C$id", group = group, home = group, family = "$group|$id", rank = 0)
    private fun feed(vararg channels: Channel) =
        Feed(channels.map { it.group }.distinct(), ProviderRoute("http://p", "http://v", "u", "p"), channels.toList())
    private fun Feed.ids(group: String) = channelsIn(group).map { it.id }

    @Test fun anArrangedOrderOutlivesARefreshAndNewChannelsComeLast() {
        val store = MapStore()
        val lineup = Lineup(store)
        val f = lineup.applyTo(feed(ch(1), ch(2), ch(3)))
        lineup.move(ru, f.channelsIn(ru).map { it.family }, "$ru|3", down = false)
        lineup.move(ru, lineup.applyTo(f).channelsIn(ru).map { it.family }, "$ru|3", down = false)
        // A fresh start (same store) on a refreshed feed with a new channel.
        assertEquals(listOf(3, 1, 2, 4), Lineup(store).applyTo(feed(ch(1), ch(2), ch(3), ch(4))).ids(ru))
    }

    @Test fun favouritesAreTheFirstGroupAndZapInTheirOwnOrder() {
        val lineup = Lineup(MapStore())
        lineup.toggleFavourite("$ru|2")
        lineup.toggleFavourite("$ru|1")
        val f = lineup.applyTo(feed(ch(1), ch(2), ch(3)))
        assertEquals(listOf(FAVOURITES, ru), f.groups)
        assertEquals(listOf(2, 1), f.ids(FAVOURITES))
        assertEquals(listOf(1, 2, 3), f.ids(ru))
        lineup.toggleFavourite("$ru|2")
        lineup.toggleFavourite("$ru|1")
        assertEquals(listOf(ru), lineup.applyTo(feed(ch(1))).groups)
    }
}
