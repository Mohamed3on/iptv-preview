package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.ui.SearchResults
import com.mohamed3on.iptv.ui.search
import com.mohamed3on.iptv.ui.searchNorm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchRankingTest {
    @get:Rule val tmp = TemporaryFolder()
    private var guide: GuideDb? = null

    @After fun close() { guide?.close() }

    private val g = "🏴 Sky Sports & UK Sports"
    private val now = 1_793_563_200L + 1800

    private fun ch(id: Int, name: String, tvgId: String, family: String = name, rank: Int = 0) =
        Channel(id = id, name = name, group = g, tvgId = tvgId, home = g, family = "$g|$family", rank = rank)

    /** A guide row as the server writes it, [norm] included. */
    private fun row(tvgId: String, title: String, start: Long = now - 1800, synthetic: Boolean = false) =
        Row(tvgId, start, start + 7200, title, norm = searchNorm(title), synthetic = synthetic)

    private fun search(query: String, channels: List<Channel>, vararg rows: Row): SearchResults {
        val feed = Feed(listOf(g), ProviderRoute("p", "v", "u", "x"), channels)
        return feed.search(guideOf(tmp.root, *rows).also { guide = it }, query, now)
    }

    private val mainEvent = ch(1, "Sky Sports Main Event", "skyme.uk")
    private val tnt1 = ch(2, "TNT Sports 1", "tnt1.uk")
    private val premier = ch(3, "Sky Sports Premier League", "skypl.uk")

    @Test fun arsenalLiveOnTwoChannelsComesBeforeItsLaterKickOffOnAThird() {
        val result = search(
            "arse", listOf(mainEvent, tnt1, premier),
            row("skypl.uk", "Arsenal Women v Chelsea Women", start = now + 3 * 3600),
            row("skyme.uk", "Arsenal v Spurs"),
            row("tnt1.uk", "Arsenal v Spurs"),
        )
        assertEquals(3, result.hits.size)
        assertEquals(setOf(mainEvent, tnt1), result.hits.take(2).map { it.tune }.toSet())
        assertEquals(premier, result.hits[2].tune)
        assertEquals(listOf(true, true, false), result.hits.map { it.programme.start <= now })
    }

    @Test fun matchesInsideAWordAndIgnoresAccents() {
        val channels = listOf(mainEvent, tnt1)
        val rows = arrayOf(row("skyme.uk", "Football Focus"), row("tnt1.uk", "Bayern München v Arsenal"))
        assertEquals(listOf("Football Focus"), search("ball", channels, *rows).hits.map { it.programme.title })
        assertEquals(listOf("Bayern München v Arsenal"), search("munchen", channels, *rows).hits.map { it.programme.title })
    }

    @Test fun nameAsTitleFillerIsNeverAResult() {
        val arsenalTv = ch(4, "Arsenal TV", "arsenaltv.uk")
        val result = search("arsenal", listOf(arsenalTv), row("arsenaltv.uk", "Arsenal TV", synthetic = true))
        assertEquals(emptyList<Any>(), result.hits)
    }

    @Test fun theSameProgrammeOnAFamilysFhdAndHdFeedsIsOneRowOnTheBestFeed() {
        val fhd = ch(5, "Sky Sports Main Event FHD", "skyme.fhd.uk", family = "main event", rank = 0)
        val hd = ch(6, "Sky Sports Main Event HD", "skyme.hd.uk", family = "main event", rank = 1)
        val result = search("arse", listOf(hd, fhd), row("skyme.hd.uk", "Arsenal v Spurs"), row("skyme.fhd.uk", "Arsenal v Spurs"))
        assertEquals(listOf(fhd), result.hits.map { it.tune })
    }
}
