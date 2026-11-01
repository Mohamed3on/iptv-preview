package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.RouteChoice
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.StreamFailure
import com.mohamed3on.iptv.player.StreamPlayer
import com.mohamed3on.iptv.player.StreamSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opens are recorded; a response (if any) arrives asynchronously, like ExoPlayer's callbacks. */
private class FakePlayer(private val scope: CoroutineScope, private val clock: () -> Long) : StreamPlayer {
    data class Open(val at: Long, val url: String)

    val opens = mutableListOf<Open>()
    /** The URL holding a provider connection right now. */
    var connected: String? = null
        private set
    /** What the provider does with a URL; null means it starts streaming slowly (no event yet). */
    var respond: (String) -> StreamFailure? = { null }
    private var listener: StreamPlayer.Listener? = null
    private var playingSince: Long? = null
    private var frozen = 0L

    override val position get() = playingSince?.let { frozen + clock() - it } ?: frozen

    override fun open(url: String) {
        stop()
        frozen = 0
        opens += Open(clock(), url)
        connected = url
        respond(url)?.let { failure -> scope.launch { if (connected == url) fail(failure) } }
    }

    override fun stop() {
        frozen = position
        playingSince = null
        connected = null
    }

    override fun setListener(listener: StreamPlayer.Listener?) {
        this.listener = listener
    }

    fun play() {
        playingSince = clock()
        listener!!.onPlaying()
    }

    /** Data stops arriving: the position freezes, no event (the watchdog must notice). */
    fun freeze() {
        frozen = position
        playingSince = null
    }

    fun fail(failure: StreamFailure) {
        connected = null
        listener!!.onError(failure)
    }

    fun end() = listener!!.onEnded()
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionTest {
    private val provider = ProviderRoute(plain = "http://plain.test", vpn = "http://vpn.test", user = "u", pass = "p")

    private fun ch(id: Int, family: String = "fam$id", rank: Int = 0) =
        Channel(id = id, name = "C$id", group = "G", home = "G", family = family, rank = rank)

    private fun feedOf(vararg channels: Channel) = Feed(listOf("G"), provider, channels.toList())

    private fun plain(id: Int) = "http://plain.test/live/u/p/$id.ts"
    private fun vpn(id: Int) = "http://vpn.test/live/u/p/$id.ts"

    private class Rig(val session: StreamSession, val player: FakePlayer, val route: Route, val vpn: MutableStateFlow<Boolean>) {
        val phase get() = session.state.value.phase
        val urls get() = player.opens.map { it.url }
        val times get() = player.opens.map { it.at }
    }

    private fun TestScope.rig(vpnOn: Boolean = false, choice: RouteChoice = RouteChoice.Auto): Rig {
        val vpn = MutableStateFlow(vpnOn)
        val route = Route(MapStore(), vpn, backgroundScope)
        if (choice != RouteChoice.Auto) route.choose(choice)
        runCurrent() // a choice made before this session: let Route.effective settle
        val player = FakePlayer(backgroundScope) { testScheduler.currentTime }
        return Rig(StreamSession(player, route, backgroundScope) { testScheduler.currentTime }, player, route, vpn)
    }

    private fun TestScope.at(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    // AE4: another device holds the connection.
    @Test fun aBusyAccountIsRetriedEvery5sAndGivenUpAt10Minutes() = runTest {
        val r = rig()
        val a = ch(1)
        r.player.respond = { StreamFailure.Http(458) }
        r.session.zap(a, feedOf(a))
        runCurrent()
        assertEquals(Phase.Busy, r.phase)
        assertNull("no connection held while waiting", r.player.connected)

        at(599_999)
        assertEquals(Phase.Busy, r.phase)
        assertEquals((0L..595_000L step 5_000).toList(), r.times)
        assertTrue(r.urls.all { it == plain(1) })

        at(600_000)
        assertEquals(Phase.GaveUp, r.phase)
        at(700_000)
        assertEquals(121, r.player.opens.size)

        r.player.respond = { null }
        r.session.retry()
        assertEquals(122, r.player.opens.size)
        r.player.play()
        assertEquals(Phase.Playing, r.phase)
    }

    @Test fun a458RightAfterTheAutomaticRouteSwitchIsRetriedQuietlyOnce() = runTest {
        val r = rig(vpnOn = false) // stale: the stick is actually on VPN
        val a = ch(1)
        r.player.respond = { if (it == plain(1)) StreamFailure.Http(511) else StreamFailure.Http(458) }
        r.session.zap(a, feedOf(a))
        runCurrent()
        assertEquals(listOf(plain(1), vpn(1)), r.urls)
        assertEquals(Phase.Connecting, r.phase)

        at(4_999)
        assertEquals(2, r.player.opens.size)
        at(5_000)
        assertEquals(listOf(plain(1), vpn(1), vpn(1)), r.urls)
        assertEquals(Phase.Busy, r.phase)
    }

    @Test fun a458SoonAfterTheVpnComesOnIsRetriedQuietlyOnce() = runTest {
        val r = rig(vpnOn = false)
        val a = ch(1)
        r.session.zap(a, feedOf(a))
        r.player.play()
        at(20_000)
        r.vpn.value = true // Auto now picks the VPN front; the old connection dies
        runCurrent()
        r.player.respond = { StreamFailure.Http(458) }
        r.player.fail(StreamFailure.Unreachable)

        at(21_000) // 1s backoff, then the VPN front, still held by the old IP
        assertEquals(listOf(plain(1), vpn(1)), r.urls)
        assertEquals(Phase.Connecting, r.phase)
        r.player.respond = { null }
        at(26_000)
        assertEquals(listOf(plain(1), vpn(1), vpn(1)), r.urls)
        r.player.play()
        assertEquals(Phase.Playing, r.phase)
    }

    @Test fun wrongRouteWithNoOverrideSwitchesOnceThenPrompts() = runTest {
        val r = rig(vpnOn = false) // stale VPN state: Auto picked plain, the provider sees the VPN
        val a = ch(1)
        r.player.respond = { StreamFailure.Http(511) }
        r.session.zap(a, feedOf(a))
        runCurrent()
        assertEquals(listOf(plain(1), vpn(1)), r.urls)
        assertTrue(r.phase is Phase.WrongRoute)
        at(60_000)
        assertEquals(2, r.player.opens.size)
    }

    // AE5: VPN on, plain forced, 511.
    @Test fun wrongRouteWithAManualChoicePromptsAndOnePressSwitchesAndRetunes() = runTest {
        val r = rig(vpnOn = true, choice = RouteChoice.Plain)
        val a = ch(1)
        r.player.respond = { if (it == plain(1)) StreamFailure.Http(511) else null }
        r.session.zap(a, feedOf(a))
        runCurrent()
        assertEquals(listOf(plain(1)), r.urls)
        assertEquals(Phase.WrongRoute(RouteKind.Vpn), r.phase)
        at(30_000)
        assertEquals(1, r.player.opens.size)

        r.session.switchRoute()
        assertEquals(listOf(plain(1), vpn(1)), r.urls)
        r.player.play()
        assertEquals(Phase.Playing, r.phase)
        runCurrent()
        assertEquals(RouteKind.Vpn, r.route.effective.value)
    }

    @Test fun http403OnTheVpnFrontWithTheVpnOffIsTheWrongRoute() = runTest {
        val r = rig(vpnOn = false, choice = RouteChoice.Vpn)
        val a = ch(1)
        r.player.respond = { StreamFailure.Http(403) }
        r.session.zap(a, feedOf(a))
        runCurrent()
        assertEquals(listOf(vpn(1)), r.urls)
        assertEquals(Phase.WrongRoute(RouteKind.Plain), r.phase)
    }

    // AE6: the FHD feed times out; the HD feed of the same family takes over.
    @Test fun aTimedOutFeedFailsOverToTheNextFamilyMemberThenOffline() = runTest {
        val r = rig()
        val fhd = ch(1, family = "sky", rank = 0)
        val hd = ch(2, family = "sky", rank = 1)
        val feed = feedOf(fhd, hd)
        r.player.respond = { if (it == plain(1)) StreamFailure.Timeout else null }
        r.session.zap(fhd, feed)
        runCurrent()
        assertEquals(listOf(plain(1), plain(2)), r.urls)
        r.player.play()
        assertEquals(Phase.Playing, r.phase)
        assertEquals(hd, r.session.state.value.feed)
        assertEquals(fhd, r.session.state.value.requested)

        r.player.respond = { StreamFailure.Timeout }
        r.session.zap(hd, feed)
        at(2_000)
        assertEquals(listOf(plain(1), plain(2), plain(2), plain(1)), r.urls)
        assertEquals(Phase.Offline, r.phase)
        assertNull(r.player.connected)
    }

    // AE6: the FHD feed stops delivering data mid-match.
    @Test fun aPlayingFeedThatStopsAdvancingHandsOffToTheNextFamilyMember() = runTest {
        val r = rig()
        val fhd = ch(1, family = "sky", rank = 0)
        val hd = ch(2, family = "sky", rank = 1)
        r.session.zap(fhd, feedOf(fhd, hd))
        r.player.play()
        at(60_000)
        assertEquals(1, r.player.opens.size)

        r.player.freeze()
        at(62_000)
        assertEquals(1, r.player.opens.size) // a short hiccup is not a stall
        at(70_000)
        assertEquals(listOf(plain(1), plain(2)), r.urls)
        r.player.play()
        assertEquals(Phase.Playing, r.phase)
        assertEquals(hd, r.session.state.value.feed)
    }

    @Test fun serverErrorsBackOff1s2s4s8s8s() = runTest {
        val r = rig()
        val a = ch(1)
        r.player.respond = { StreamFailure.Http(503) }
        r.session.zap(a, feedOf(a))
        at(23_000)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 23_000L), r.times)
    }

    @Test fun aBurstOfZapsTunesOnlyTheFirstAndTheLastChannel() = runTest {
        val r = rig()
        val channels = (1..5).map { ch(it) }
        val feed = feedOf(*channels.toTypedArray())
        channels.forEachIndexed { i, c ->
            at(i * 50L)
            r.session.zap(c, feed)
            assertEquals(c, r.session.state.value.requested)
        }
        at(5_000)
        assertEquals(listOf(plain(1), plain(5)), r.urls)
        assertEquals(listOf(0L, 500L), r.times)
    }

    @Test fun anIsolatedZapTunesWithNoDelay() = runTest {
        val r = rig()
        val a = ch(1)
        val b = ch(2)
        val feed = feedOf(a, b)
        r.session.zap(a, feed)
        assertEquals(listOf(Pair(0L, plain(1))), r.player.opens.map { it.at to it.url })
        at(1_000)
        r.session.zap(b, feed)
        assertEquals(Pair(1_000L, plain(2)), r.player.opens.last().let { it.at to it.url })
    }

    @Test fun aZapDuringABusyRetryCancelsThePendingRetry() = runTest {
        val r = rig()
        val a = ch(1)
        val b = ch(2)
        val feed = feedOf(a, b)
        r.player.respond = { if (it == plain(1)) StreamFailure.Http(458) else null }
        r.session.zap(a, feed)
        runCurrent()
        assertEquals(Phase.Busy, r.phase)
        at(2_000)
        r.session.zap(b, feed)
        r.player.play()
        at(30_000)
        assertEquals(listOf(plain(1), plain(2)), r.urls)
        assertEquals(plain(2), r.player.connected)
    }

    @Test fun liveStateEndedIsAStallNotTheEnd() = runTest {
        val r = rig()
        val a = ch(1) // a single feed: the stall path re-prepares it
        r.session.zap(a, feedOf(a))
        r.player.play()
        at(10_000)
        r.player.end()
        assertEquals(listOf(plain(1), plain(1)), r.urls)
        assertEquals(Phase.Connecting, r.phase)
    }
}
