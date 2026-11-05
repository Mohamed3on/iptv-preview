package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.RdCandidate
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.RdStatus
import com.mohamed3on.iptv.player.Bandwidth
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.Capacity.Source.Iptv
import com.mohamed3on.iptv.player.Capacity.Source.RealDebrid
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.FilmPhase
import com.mohamed3on.iptv.player.FilmPlayer
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Notice
import com.mohamed3on.iptv.player.OfferKind
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.Place
import com.mohamed3on.iptv.player.SetupProfile
import com.mohamed3on.iptv.player.StreamFailure
import com.mohamed3on.iptv.player.TitleCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

/** Opens are recorded; each answers asynchronously, like ExoPlayer: [respond]'s failure, else the first frame. */
private class FakeFilm(private val scope: CoroutineScope, private val clock: () -> Long) : FilmPlayer {
    val opens = mutableListOf<Pair<String, Place>>()
    var respond: (String) -> StreamFailure? = { null }
    override var duration = 120 * MIN
    private var listener: FilmPlayer.Listener? = null
    private var base = Place(0)
    private var since: Long? = null
    private var gen = 0

    override val place get() = since?.let { base.copy(position = base.position + clock() - it) } ?: base

    override fun open(url: String, place: Place) {
        stop()
        base = place
        opens += url to place
        val g = gen
        scope.launch {
            if (g != gen) return@launch
            val failure = respond(url)
            if (failure != null) listener!!.onError(failure) else {
                since = clock()
                listener!!.onPlaying()
            }
        }
    }

    override fun stop() {
        pause()
        gen++
    }

    override fun pause() {
        base = place
        since = null
    }

    override fun play() {
        if (since == null) since = clock()
    }

    override fun seek(position: Long) {
        base = place.copy(position = position)
        if (since != null) since = clock()
    }

    override fun setListener(listener: FilmPlayer.Listener?) {
        this.listener = listener
    }

    fun rebuffer() = listener!!.onRebuffer()
}

/** Every Real-Debrid copy in the tests is in the account; links count up so a fresh one is visible. */
private class FakeDebrid : Debrid {
    override val status = MutableStateFlow(RdStatus.Ok)
    var links = 0
    override suspend fun inAccount(candidates: List<RdCandidate>) = candidates.associateWith { RdCheck.Ready(it.hash, "hoster/${it.hash}") as RdCheck }
    override suspend fun probe(c: RdCandidate, wait: Boolean): RdCheck? = null
    override suspend fun check(candidates: List<RdCandidate>) = inAccount(candidates)
    override suspend fun link(ready: RdCheck.Ready) = "https://rd.test/${ready.torrentId}/${++links}"
}

private class FakeBandwidth : Bandwidth {
    override fun typicalMbps(source: Capacity.Source, at: Long): Float? = null
    override var sessionMbps: Float? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilmSessionTest {
    private val tv = SetupProfile(
        "Built-in Screen", setOf(Hdr.Hdr10, Hdr.Hlg), setOf(5, 8),
        setOf("video/hevc", "video/avc"), setOf(Passthrough.Ac3, Passthrough.EAc3, Passthrough.EAc3Joc), 8,
    )
    private val dvTv = tv.copy(displayName = "OLED", hdr = tv.hdr + Hdr.DolbyVision)

    private val rd4k = Copy("rd:a", RealDebrid, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, mbps = 25f)
    private val iptv1080 = Copy("iptv:1", Iptv, 1080, codec = "h264", audio = "aac", channels = 2, mbps = 6f)
    /** Dolby Vision of unknown profile: untested (KTD6). */
    private val dv = Copy("rd:dv", RealDebrid, 2160, listOf("DV"), codec = "hevc", audio = "eac3", channels = 6, mbps = 40f)
    private val iptvHdr = Copy("iptv:hdr", Iptv, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, mbps = 20f)

    private class Rig(val session: FilmSession, val player: FakeFilm, val debrid: FakeDebrid, val choices: CopyChoices, val net: FakeBandwidth) {
        val state get() = session.state.value
        val urls get() = player.opens.map { it.first }
    }

    private fun TestScope.rig(setup: SetupProfile = tv): Rig {
        val player = FakeFilm(backgroundScope) { testScheduler.currentTime }
        val debrid = FakeDebrid()
        val choices = CopyChoices(MapStore()) { testScheduler.currentTime }
        val net = FakeBandwidth()
        val session = FilmSession(
            "movie:1", "en", player, choices, { setup }, net, debrid, { "http://iptv.test/${it.id}" }, backgroundScope,
            { testScheduler.currentTime },
        )
        return Rig(session, player, debrid, choices, net)
    }

    private fun titleCopies(vararg copies: Copy) =
        TitleCopies(copies.toList(), copies.filter { it.source == RealDebrid }.associate { it.id to RdCandidate(it.id, null) })

    private fun TestScope.play(r: Rig, vararg copies: Copy, from: Long = 0) {
        r.session.play(Place(from, audio = "en"), { titleCopies(*copies) })
        runCurrent()
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.stallThrice(r: Rig) {
        repeat(3) {
            advance(1_000)
            r.player.rebuffer()
        }
        runCurrent()
    }

    @Test fun aPermanentFaultMovesOnAndMarksTheCopy() = runTest {
        val r = rig()
        r.player.respond = { if ("rd.test" in it) StreamFailure.Unsupported else null }
        play(r, rd4k, iptv1080)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(FilmPhase.Playing, r.state.phase)
        assertEquals(false, r.choices.marks(tv)[rd4k.id]?.works)
    }

    // Unreported audio is tried; a file none of whose tracks plays is marked and teaches the setup, whose copies of
    // that kind then rank below otherwise equal ones (never ruled out: a remux may carry a Dolby track too).
    @Test fun audioThatDidntPlayIsLearntForTheSetupAndItsCopiesDemoted() = runTest {
        val r = rig()
        val dts = rd4k.copy(id = "rd:dts", audio = "dts")
        r.player.respond = { if ("rd:dts" in it) StreamFailure.NoAudio(setOf("dts")) else null }
        play(r, dts, iptv1080)
        assertEquals(listOf("rd:dts", "iptv:1"), r.player.opens.map { it.first.substringAfter("test/").substringBefore('/') })
        assertEquals(iptv1080, r.state.copy)
        assertEquals(false, r.choices.marks(tv)[dts.id]?.works)

        val dtsHd = rd4k.copy(id = "rd:dtshd", audio = "dtshd")
        val ranked = r.choices.rank("movie:2", listOf(dtsHd, rd4k), "en", tv) { null }
        assertEquals(listOf(rd4k, dtsHd), ranked.copies.map { it.copy })
        assertTrue(ranked.copies[1].reason, ranked.copies[1].reason.startsWith("DTS didn’t play here on"))
        assertEquals(dtsHd, r.choices.rank("movie:2", listOf(dtsHd, iptv1080), "en", tv) { null }.pick?.copy) // not ruled out
        assertEquals(dtsHd, r.choices.rank("movie:2", listOf(dtsHd, rd4k), "en", dvTv) { null }.pick?.copy) // a new setup: no demotion, a tie keeps order
    }

    // R2: a multi-language file's default track is often a dub; a first play asks for the film's own language.
    @Test fun aFirstPlayAsksForTheOriginalLanguage() = runTest {
        val r = rig()
        r.session.play(Place(0), { titleCopies(rd4k) })
        runCurrent()
        assertEquals("en", r.player.opens.last().second.audio)
    }

    @Test fun aBusyProviderMovesOnWithNoMark() = runTest {
        val r = rig()
        r.player.respond = { if ("iptv.test" in it) StreamFailure.Http(458) else null }
        play(r, iptvHdr, Copy("rd:b", RealDebrid, 1080, codec = "h264", mbps = 8f))
        assertEquals("rd:b", r.state.copy?.id)
        assertTrue(r.choices.marks(tv).isEmpty())
    }

    // AE5
    @Test fun stallsStepDownAndRecoveryOffersTheBetterCopyOnce() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080)
        advance(MIN)
        stallThrice(r)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(r.player.opens[0].second.position + MIN + 3_000, r.player.opens.last().second.position)
        assertEquals("en", r.player.opens.last().second.audio)
        assertEquals(Notice.SteppedDown(iptv1080), r.state.notice)

        r.net.sessionMbps = 40f // over 1.3 x 25
        advance(5 * MIN + 2_000)
        assertEquals(Notice.Offer(OfferKind.Better, rd4k), r.state.notice)
        advance(FilmSession.OFFER_MS) // fades
        assertNull(r.state.notice)
        advance(20 * MIN)
        assertNull(r.state.notice)
        assertEquals(iptv1080, r.state.copy)
    }

    // AE6
    @Test fun noOfferInTheLast15Minutes() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080, from = 120 * MIN - 10 * MIN)
        stallThrice(r)
        r.net.sessionMbps = 40f
        advance(6 * MIN)
        assertNull(r.state.notice)
    }

    @Test fun aFailedSwitchReturnsToTheCurrentCopyAndTheOfferStaysSpent() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080)
        stallThrice(r)
        r.net.sessionMbps = 40f
        advance(5 * MIN + 2_000)
        r.player.respond = { if ("rd.test" in it) StreamFailure.Http(503) else null }
        val at = r.player.place.position
        assertTrue(r.session.accept())
        runCurrent()
        assertEquals(iptv1080, r.state.copy)
        assertEquals(FilmPhase.Playing, r.state.phase)
        assertEquals(at, r.player.opens.last().second.position)
        advance(10 * MIN)
        assertNull(r.state.notice)
    }

    // AE9
    @Test fun backDuringATrialReturnsAndMarksTheCopyAsNotWorking() = runTest {
        val r = rig(dvTv)
        play(r, dv, iptvHdr)
        assertEquals(iptvHdr, r.state.copy) // a working copy plays; the untested one is offered
        advance(11_000)
        assertEquals(Notice.Offer(OfferKind.Untested, dv), r.state.notice)
        assertTrue(r.session.accept())
        runCurrent()
        assertEquals(dv, r.state.copy)
        assertTrue(r.state.trial)
        advance(5_000)
        val at = r.player.place.position
        assertTrue(r.session.back())
        runCurrent()
        assertEquals(iptvHdr, r.state.copy)
        assertEquals(at, r.player.opens.last().second.position)
        assertEquals(false, r.choices.marks(dvTv)[dv.id]?.works)
    }

    @Test fun aCleanTrialMarksTheCopyAsWorking() = runTest {
        val r = rig(dvTv)
        play(r, dv, iptvHdr)
        advance(11_000)
        r.session.accept()
        runCurrent()
        advance(31_000)
        assertEquals(true, r.choices.marks(dvTv)[dv.id]?.works)
        assertEquals(Notice.TrialResult(dv, works = true), r.state.notice)
    }

    @Test fun stallsOnAPinnedCopyOfferTheLighterOneInsteadOfSteppingDown() = runTest {
        val r = rig()
        r.choices.pin("movie:1", rd4k.id)
        play(r, rd4k, iptv1080)
        stallThrice(r)
        assertEquals(Notice.Offer(OfferKind.Lighter, iptv1080), r.state.notice)
        assertEquals(1, r.player.opens.size)
    }

    @Test fun aRealDebridCopyResumedAfterALongPauseGetsAFreshLink() = runTest {
        val r = rig()
        play(r, rd4k)
        advance(MIN)
        r.session.pause()
        advance(15 * MIN)
        r.session.resume()
        runCurrent()
        assertEquals(2, r.debrid.links)
        assertEquals("https://rd.test/rd:a/2" to Place(MIN, audio = "en"), r.player.opens.last())
    }

    @Test fun everyCopyFailingEndsUnavailable() = runTest {
        val r = rig()
        r.player.respond = { StreamFailure.Http(404) }
        play(r, rd4k, iptv1080)
        assertEquals(FilmPhase.Unavailable, r.state.phase)
        assertTrue(r.choices.marks(tv).isEmpty())
    }

    @Test fun slowCopiesStartTheLibrarysIptvCopyAndLaterOnesDoNotSwitch() = runTest {
        val r = rig()
        r.session.play(Place(0), {
            delay(6_000)
            titleCopies(rd4k, iptv1080)
        }, fallback = iptv1080)
        advance(FilmSession.WAIT_MS)
        assertEquals(listOf("http://iptv.test/iptv:1"), r.urls)
        advance(3_000)
        assertEquals(2, r.state.ranking?.copies?.size)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(1, r.player.opens.size)
    }

    // KTD13: Real-Debrid's copies, still pending when Play started, join the play when the box has them.
    @Test fun copiesArrivingAfterPlayJoinIt() = runTest {
        val r = rig()
        r.session.play(Place(0), { titleCopies(iptv1080) }, later = flow {
            delay(3_000)
            emit(titleCopies(iptv1080, rd4k))
        })
        runCurrent()
        assertEquals(iptv1080, r.state.copy)
        advance(3_000)
        assertEquals(listOf(rd4k, iptv1080), r.state.ranking?.copies?.map { it.copy })
        assertTrue(r.state.rd[rd4k.id] is RdCheck.Ready)
    }
}
