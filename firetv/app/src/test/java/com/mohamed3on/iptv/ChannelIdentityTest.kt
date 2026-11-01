package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.StartAction
import com.mohamed3on.iptv.data.Watched
import com.mohamed3on.iptv.data.WatchHistory
import com.mohamed3on.iptv.data.startAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelIdentityTest {
    private val pl = "🏆 Premier League"
    private val sky = "🏴 Sky Sports & UK Sports"
    private val family = "$sky|sky sport 1"

    private fun ch(id: Int, group: String, rank: Int = 0, home: String? = sky, fam: String = family) =
        Channel(id = id, name = "C$id", group = group, home = home, family = fam, rank = rank)

    private fun feed(vararg channels: Channel) =
        Feed(channels.map { it.group }.distinct(), ProviderRoute("http://p", "http://v", "u", "p"), channels.toList())

    @Test fun theSameChannelInTheSameGroupPlays() {
        val f = feed(ch(101, pl), ch(101, sky), ch(102, sky, rank = 1))
        assertEquals(StartAction.Play(ch(101, pl)), f.startAction(Watched(101, pl, family)))
    }

    // AE8: the 🏆 group the channel was watched in is gone after a refresh.
    @Test fun aVanishedTrophyGroupFallsBackToTheHomeGroup() {
        val f = feed(ch(200, "🏆 Champions League", home = "🇪🇸 Spain", fam = "🇪🇸 Spain|x"), ch(200, "🇪🇸 Spain", home = "🇪🇸 Spain", fam = "🇪🇸 Spain|x"), ch(102, sky), ch(101, sky, rank = 1))
        assertEquals(StartAction.Play(ch(101, sky, rank = 1)), f.startAction(Watched(101, pl, family)))
    }

    @Test fun aVanishedStreamFallsBackToTheTopRankedFeedOfItsFamily() {
        val f = feed(ch(104, pl, rank = 2), ch(103, sky, rank = 3), ch(104, sky, rank = 2), ch(105, sky, rank = 4))
        assertEquals(StartAction.Play(ch(104, sky, rank = 2)), f.startAction(Watched(101, pl, family)))
    }

    @Test fun aVanishedFamilyOpensTheListWithNoAutoplay() {
        val f = feed(ch(300, "🏆 La Liga", fam = "es|a"), ch(301, sky, fam = "uk|b"))
        assertEquals(StartAction.OpenList("🏆 La Liga"), f.startAction(Watched(101, pl, family)))
    }

    @Test fun noLastChannelOpensTheListAtTheFirstGroup() {
        val f = feed(ch(101, pl), ch(101, sky))
        assertEquals(StartAction.OpenList(pl), f.startAction(null))
    }

    @Test fun watchingABThenAMakesBThePreviousChannel() {
        val a = ch(101, pl)
        val b = ch(101, sky) // same stream, different group: a different channel (KTD5)
        val store = MapStore()
        WatchHistory(store).apply { watched(a); watched(b); watched(a) }
        val reloaded = WatchHistory(store)
        assertEquals(Watched(101, pl, family), reloaded.last)
        assertEquals(Watched(101, sky, family), reloaded.previous)
    }

    @Test fun rewatchingTheSameChannelKeepsThePreviousOne() {
        val store = MapStore()
        val h = WatchHistory(store).apply { watched(ch(1, sky)); watched(ch(2, sky)); watched(ch(2, sky)) }
        assertEquals(Watched(1, sky, family), h.previous)
        assertNull(WatchHistory(MapStore()).previous)
    }
}
