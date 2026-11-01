package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.ui.KeyAction
import com.mohamed3on.iptv.ui.LiveNow
import com.mohamed3on.iptv.ui.PlayerKeys
import com.mohamed3on.iptv.ui.RemoteKey
import com.mohamed3on.iptv.ui.keyAction
import com.mohamed3on.iptv.ui.liveNow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveNowTest {
    @get:Rule val tmp = TemporaryFolder()
    private var guide: GuideDb? = null

    @After fun close() { guide?.close() }

    private val sky = "🏴 Sky Sports & UK Sports"
    private val tnt = "🏴 TNT Sports"
    private val pl = "🏆 Premier League"
    private val now = 1_793_563_200L + 1800

    private fun ch(id: Int, group: String, home: String?, family: String, rank: Int, tvgId: String, slot: Boolean = false, fixture: Boolean = false, name: String = "C$id") =
        Channel(id = id, name = name, group = group, tvgId = tvgId, slot = slot, home = home, family = family, rank = rank, fixture = fixture)

    private fun feed(vararg channels: Channel) =
        Feed(channels.map { it.group }.distinct(), ProviderRoute("http://p", "http://v", "u", "p"), channels.toList())

    private fun match(tvgId: String, title: String, start: Long = now - 1800, synthetic: Boolean = false) =
        Row(tvgId, start, start + 7200, title, synthetic = synthetic, match = true)

    private fun live(feed: Feed, vararg rows: Row): LiveNow =
        feed.liveNow(guideOf(tmp.root, *rows).also { guide = it }.airingMatches(now))

    // Sky Main Event comes as a 4K (rank 0) and an HD (rank 1) feed; TNT Sports 1 is another family.
    private val mainEvent4k = ch(1, sky, sky, "$sky|main event", 0, "skyme.uk")
    private val mainEventHd = ch(2, sky, sky, "$sky|main event", 1, "skyme.uk")
    private val tnt1 = ch(3, tnt, tnt, "$tnt|tnt 1", 0, "tnt1.uk")

    @Test fun oneMatchOnThreeTrophyChannelsInTwoFamiliesIsOneRowTuningTheTopRankedFeed() {
        val f = feed(
            mainEvent4k, mainEventHd, tnt1,
            // The 🏆 group lists the HD Main Event first; the match is on all three copies.
            mainEventHd.copy(group = pl), tnt1.copy(group = pl), mainEvent4k.copy(group = pl),
        )
        val result = live(f, match("skyme.uk", "Arsenal v Chelsea"), match("tnt1.uk", "Arsenal v Chelsea"))

        assertEquals(1, result.matches.size)
        assertEquals("Arsenal v Chelsea", result.matches.single().title)
        assertEquals(mainEvent4k, result.matches.single().tune)
        assertEquals(pl, result.matches.single().via.group)
    }

    @Test fun oneMatchTitledDifferentlyByEachBroadcasterIsOneRow() {
        val a = ch(1, sky, sky, "$sky|a", 0, "a.uk")
        val b = ch(2, sky, sky, "$sky|b", 0, "b.uk")
        val c = ch(3, tnt, tnt, "$tnt|c", 0, "c.uk")
        val d = ch(4, tnt, tnt, "$tnt|d", 0, "d.uk")
        val f = feed(a, b, c, d, a.copy(group = pl), b.copy(group = pl), c.copy(group = pl), d.copy(group = pl))
        val result = live(
            f,
            match("a.uk", "Live: LaLiga - Athletic Bilbao - Real Sociedad"),
            match("b.uk", "Athletic Bilbao vs Real Sociedad - Spanish La Liga"),
            match("c.uk", "LaLiga 26/27 : Athletic Bilbao - Real Sociedad", start = now - 600),
            match("d.uk", "Live: LaLiga - Real Madrid v Getafe"),
        )
        assertEquals(2, result.matches.size)
        val bilbao = result.matches.first()
        assertEquals(a, bilbao.tune)
        assertEquals(2, bilbao.alsoOn)
        assertEquals("Athletic Bilbao vs Real Sociedad", bilbao.title)
    }

    @Test fun downFromTheSecondLiveNowMatchTunesTheThirdNotTheNextChannelOfItsGroup() {
        val a = ch(1, sky, sky, "$sky|a", 0, "a.uk")
        val b = ch(2, sky, sky, "$sky|b", 0, "b.uk")
        val bNeighbour = ch(4, sky, sky, "$sky|n", 0, "n.uk")
        val c = ch(3, tnt, tnt, "$tnt|c", 0, "c.uk")
        val f = feed(a, b, bNeighbour, c, a.copy(group = pl), b.copy(group = pl), c.copy(group = pl))
        val result = live(f, match("a.uk", "Arsenal v Chelsea"), match("b.uk", "Liverpool v Everton"), match("c.uk", "Spurs v Fulham"))
        val second = result.matches[1].tune
        assertEquals(b, second)

        val keys = PlayerKeys(second, null, null, context = result.context)
        assertEquals(KeyAction.Zap(c, down = true), keyAction(RemoteKey.Down, keys, f))
        assertEquals(KeyAction.Zap(a, down = false), keyAction(RemoteKey.Up, keys, f))
        // A tune from the group itself carries no context: Down follows the group again.
        assertEquals(KeyAction.Zap(bNeighbour, down = true), keyAction(RemoteKey.Down, PlayerKeys(second, null, null), f))
    }
}
