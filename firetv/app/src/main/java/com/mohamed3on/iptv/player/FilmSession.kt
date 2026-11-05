package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.RdCandidate
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.RdError
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a film is: carried across every copy switch with the audio and subtitle languages chosen. */
data class Place(val position: Long, val audio: String? = null, val subtitles: String? = null)

/** The slice of a film player a [FilmSession] drives; ExoPlayer in the app, a fake in tests. Main thread only. */
interface FilmPlayer {
    /** Stops whatever is open, then opens [url] at [place] (position and languages) and plays. */
    fun open(url: String, place: Place)
    fun stop()
    fun pause()
    fun play()
    /** Where playback is now, with the languages chosen. */
    val place: Place
    /** The film's length in ms; 0 until known. */
    val duration: Long
    fun setListener(listener: Listener?)

    interface Listener {
        fun onPlaying()
        /** Playback stopped to buffer mid-film; not for the buffering after an open or a seek. */
        fun onRebuffer()
        fun onEnded()
        /** [StreamErrors.failureOf]; also [StreamFailure.Unsupported] for no playable audio track or a detected Dolby Vision profile 7. */
        fun onError(failure: StreamFailure)
    }
}

/** A title's copies as discovery returns them; [rd] is each Real-Debrid copy's torrent, by copy id; [originalLanguage] when known. */
class TitleCopies(val copies: List<Copy>, val rd: Map<String, RdCandidate> = emptyMap(), val originalLanguage: String? = null)

enum class FilmPhase { Stopped, Loading, Starting, Playing, Ended, Unavailable }

/** R10's better copy once bandwidth recovers, R28's untested copy, KTD12's lighter copy for a pinned one that stalls. */
enum class OfferKind { Better, Untested, Lighter }

/** What the corner shows, one at a time. An offer shows [FilmSession.OFFER_MS] (▲ accepts), the rest [FilmSession.INFO_MS]. */
sealed interface Notice {
    data class Offer(val kind: OfferKind, val copy: Copy) : Notice
    /** An automatic step-down; Versions can reverse it (R13). */
    data class SteppedDown(val copy: Copy) : Notice
    /** A pinned copy failed for good, so its pin was cleared (KTD12). */
    data class PinCleared(val copy: Copy) : Notice
    data class TrialResult(val copy: Copy, val works: Boolean) : Notice
}

/** For telemetry (/api/app/events). */
sealed interface FilmEvent {
    /** [copy] failed, at start or mid-film ([playing]); [permanent] faults mark it (KTD7). */
    data class Failover(val copy: Copy, val failure: StreamFailure, val permanent: Boolean, val playing: Boolean) : FilmEvent
    data class StepDown(val from: Copy, val to: Copy) : FilmEvent
    /** An offer was answered: ▲ ([accepted]), or Back or fading. */
    data class Offer(val kind: OfferKind, val copy: Copy, val accepted: Boolean) : FilmEvent
    data class Trial(val copy: Copy, val works: Boolean) : FilmEvent
}

data class FilmState(
    val phase: FilmPhase = FilmPhase.Stopped,
    /** The copy open or opening. */
    val copy: Copy? = null,
    /** Every copy best first, for Versions; null until copies arrive. */
    val ranking: Ranking? = null,
    /** Real-Debrid answers by copy id; a Real-Debrid copy without one is unchecked. */
    val rd: Map<String, RdCheck> = emptyMap(),
    /** The copy plays as a trial: Back in its first 30 s returns to the previous one (R28). */
    val trial: Boolean = false,
    val notice: Notice? = null,
)

/**
 * One film play from first copy to end (U9, KTD11): failover at start, step-down after stalls, the
 * offers, trials and fresh Real-Debrid links. Copies come from [load] within [WAIT_MS], else [play]'s
 * fallback IPTV copy starts and later copies only feed Versions and offers (KTD13). The first copy opens
 * as soon as a ranking exists; Real-Debrid's check runs alongside, and the next copy's link is fetched
 * while this one plays, so failover rarely waits. One session per play; call from the main thread, with
 * [scope] there too. Only permanent faults, and trials, write marks (KTD7).
 */
class FilmSession(
    private val titleId: String,
    /** The title's original language until copies name it (R2). */
    private var originalLanguage: String?,
    private val player: FilmPlayer,
    private val choices: CopyChoices,
    private val setup: () -> SetupProfile,
    private val bandwidth: Bandwidth,
    private val debrid: Debrid,
    private val iptvUrl: (Copy) -> String,
    scope: CoroutineScope,
    /** Wall-clock ms: hour bands of typical bandwidth key on it. */
    private val now: () -> Long,
    private val report: (FilmEvent) -> Unit = {},
) {
    private val _state = MutableStateFlow(FilmState())
    val state: StateFlow<FilmState> = _state

    /** Everything this session runs; [stop] cancels it. */
    private val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    /** Opening a copy: resolving its URL, then the open. Cancelled by any other switch. */
    private var work: Job? = null

    private var phase = FilmPhase.Stopped
    private var copies = emptyList<Copy>()
    private var torrents = emptyMap<String, RdCandidate>()
    private var ranking: Ranking? = null
    private val rd = mutableMapOf<String, RdCheck>()
    private var checking: Deferred<Unit>? = null
    /** Links fetched ahead for the copies a failover or step-down would open. */
    private val links = mutableMapOf<String, Pair<Long, Deferred<String?>>>()

    private var current: Copy? = null
    /** Whether the open copy's player events still count. */
    private var attempt = false
    private var openPlace = Place(0)
    private var openedAt = 0L
    private var playingAt: Long? = null
    private val tried = mutableSetOf<String>()
    private val retried = mutableSetOf<String>()
    private var iptvBusy = false
    /** Where a switch that fails returns: the copy before an accepted offer, a trial or a pick in Versions. */
    private var back: Copy? = null
    private var trial = false
    private var trialFrom = 0L
    private val rebuffers = ArrayDeque<Long>()
    /** R10: the copy bandwidth held back (left by a step-down, or ranked out at start). */
    private var better: Copy? = null
    private var fitsSince: Long? = null
    private val spent = mutableSetOf<OfferKind>()
    private val notices = ArrayDeque<Notice>()
    private var noticeAt = 0L
    private var pausedAt: Long? = null
    private var stale = false
    private var panel = false

    init {
        player.setListener(object : FilmPlayer.Listener {
            override fun onPlaying() {
                if (attempt && phase == FilmPhase.Starting) playing()
            }
            override fun onRebuffer() {
                if (attempt && phase == FilmPhase.Playing) rebuffered()
            }
            override fun onEnded() {
                if (!attempt) return
                attempt = false
                phase = FilmPhase.Ended
                publish()
            }
            override fun onError(failure: StreamFailure) {
                if (attempt) failed(failure)
            }
        })
    }

    /** Plays the title from [from]: the best copy [load] ranks, or [fallback] (its IPTV copy from the cached library) if copies take longer than [WAIT_MS]. */
    fun play(from: Place, load: suspend () -> TitleCopies, fallback: Copy? = null) {
        phase = FilmPhase.Loading
        publish()
        jobs.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
        val loading = jobs.async {
            try {
                load()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        work = jobs.launch {
            val got = withTimeoutOrNull(WAIT_MS) { loading.await() }
            arrived(got ?: TitleCopies(emptyList()), fallback)
            if (got == null) jobs.launch { loading.await()?.let { arrived(it, fallback) } }
            next(from)
        }
    }

    /** ▲ on an offer: switches to its copy at the same place (a trial for an untested one). False when no offer shows. */
    fun accept(): Boolean {
        val n = notices.firstOrNull() as? Notice.Offer ?: return false
        dismiss(accepted = true)
        if (n.kind == OfferKind.Better) better = null
        switch(n.copy, back = current, trial = n.kind == OfferKind.Untested)
        return true
    }

    /**
     * Back: in a trial's first 30 s it returns to the previous copy and marks the trial copy as not working
     * here; else it dismisses the notice showing (spending an offer). False when it did neither: leave the player.
     */
    fun back(): Boolean {
        val copy = current
        val to = back
        if (trial && copy != null && to != null && here().position - trialFrom < TRIAL_MS) {
            choices.mark(setup(), copy, works = false)
            rerank()
            trialOver(copy, works = false)
            reopen(to, here())
            return true
        }
        return dismiss(accepted = false) != null
    }

    /** Versions: plays [copyId] at the same place and pins it for the title (KTD12). */
    fun choose(copyId: String) {
        choices.pin(titleId, copyId)
        rerank()
        val to = copies.firstOrNull { it.id == copyId } ?: return
        if (to.id != current?.id || phase != FilmPhase.Playing) switch(to, back = current.takeIf { phase == FilmPhase.Playing }, trial = false)
    }

    fun pause() {
        if (pausedAt == null) pausedAt = now()
        player.pause()
    }

    /** After a pause over [PAUSE_MS], or an IP change meanwhile, a Real-Debrid copy gets a fresh link first (KTD2). */
    fun resume() {
        val at = pausedAt ?: return player.play()
        pausedAt = null
        val copy = current
        if (copy?.source == Source.RealDebrid && phase == FilmPhase.Playing && (stale || now() - at > PAUSE_MS)) reopen(copy, player.place) else player.play()
        stale = false
    }

    /** The network or VPN changed: a Real-Debrid link is tied to the old IP, so it is fetched afresh (KTD2). */
    fun ipChanged() {
        links.clear()
        val copy = current ?: return
        if (copy.source != Source.RealDebrid || phase != FilmPhase.Playing) return
        if (pausedAt != null) stale = true else reopen(copy, player.place)
    }

    /** Options or Versions is open: no offer is raised meanwhile. */
    fun panel(open: Boolean) {
        panel = open
    }

    /** Re-ranks after a mark, correction or setup change made outside the session (Versions). */
    fun rerank() {
        ranking = choices.rank(titleId, copies, originalLanguage, setup(), ::typical)
        publish()
    }

    fun stop() {
        jobs.cancel()
        attempt = false
        player.stop()
        phase = FilmPhase.Stopped
        publish()
    }

    private fun arrived(t: TitleCopies, fallback: Copy?) {
        copies = t.copies + listOfNotNull(fallback?.takeIf { f -> t.copies.none { it.id == f.id } })
        torrents = t.rd
        t.originalLanguage?.let { originalLanguage = it }
        rerank()
        check()
    }

    /** Opens the best copy not yet tried at [place]; Unavailable when none is left. */
    private suspend fun next(place: Place) {
        while (true) {
            val r = candidate() ?: return unavailable()
            tried += r.copy.id
            val url = url(r.copy) ?: continue
            // R28: every original is untested, so the best of them plays as a trial with the working dub to go back to.
            val dub = ranking?.fallback?.copy
            if (current == null && r === ranking?.pick && r.support == Support.Unsure && dub != null) {
                trial = true
                back = dub
                trialFrom = place.position
            }
            return open(r.copy, url, place)
        }
    }

    private fun candidate(): Ranked? {
        val pin = choices.pin(titleId)
        return ranking?.copies?.firstOrNull { r ->
            val c = r.copy
            c.id !in tried && (r.support != Support.Wont || c.id == pin) &&
                if (c.source == Source.Iptv) !iptvBusy else c.id in torrents && rd[c.id].let { it == null || it is RdCheck.Ready }
        }
    }

    /** [copy]'s URL: IPTV's at once; Real-Debrid's once ready, from a link fetched ahead or a fresh one. Null when it can't play. */
    private suspend fun url(copy: Copy): String? {
        if (copy.source == Source.Iptv) return iptvUrl(copy)
        val ready = ready(copy) ?: return null
        links.remove(copy.id)?.takeIf { now() - it.first < LINK_MS }?.second?.await()?.let { return it }
        repeat(2) {
            try {
                return debrid.link(ready)
            } catch (e: RdError) {
                if (e.permanent) return null.also { fault(copy) }
            }
        }
        return null
    }

    private suspend fun ready(copy: Copy): RdCheck.Ready? {
        if (copy.id !in rd) {
            // While an IPTV copy could play instead, Real-Debrid gets no longer than copies do (KTD13).
            val other = !iptvBusy && copies.any { it.source == Source.Iptv && it.id !in tried }
            if (other) withTimeoutOrNull(WAIT_MS) { checked(copy) } else checked(copy)
        }
        return rd[copy.id] as? RdCheck.Ready
    }

    private suspend fun checked(copy: Copy) {
        checking?.await()
        if (copy.id in rd) return
        check()
        checking?.await()
        rd.getOrPut(copy.id) { RdCheck.NotCached } // first in the batch and still unanswered: the account refuses
    }

    /** Checks the unchecked Real-Debrid copies in rank order (the order of checks is [Debrid.check]'s). */
    private fun check() {
        if (checking?.isActive == true) return
        val pin = choices.pin(titleId)
        val list = ranking?.copies.orEmpty()
            .filter { it.copy.id in torrents && it.copy.id !in rd && (it.support != Support.Wont || it.copy.id == pin) }.map { it.copy }
        if (list.isEmpty()) return
        checking = jobs.async {
            val answers = try {
                debrid.check(list.map { torrents.getValue(it.id) })
            } catch (e: RdError) {
                emptyMap()
            }
            for (c in list) answers[torrents.getValue(c.id)]?.let { rd[c.id] = it }
            for (c in list) if ((rd[c.id] as? RdCheck.Failed)?.error?.permanent == true) fault(c)
            publish()
            prepare()
        }
    }

    /** Fetches links ahead for the copies a failover or a step-down would open next. */
    private fun prepare() {
        val copy = current?.takeIf { phase == FilmPhase.Playing } ?: return
        for (r in listOfNotNull(candidate(), usable().lighter(copy))) {
            val ready = rd[r.copy.id] as? RdCheck.Ready ?: continue
            if (links[r.copy.id]?.let { now() - it.first < LINK_MS } == true) continue
            links[r.copy.id] = now() to jobs.async {
                try {
                    debrid.link(ready)
                } catch (e: RdError) {
                    null
                }
            }
        }
    }

    private fun open(copy: Copy, url: String, at: Place) {
        // No language chosen yet: the film's own (R2), not the dub a multi-language file flags as default.
        val place = if (at.audio == null && originalLanguage != null) at.copy(audio = originalLanguage) else at
        current = copy
        openPlace = place
        attempt = true
        openedAt = now()
        playingAt = null
        rebuffers.clear()
        phase = FilmPhase.Starting
        publish()
        player.open(url, place)
    }

    private fun playing() {
        val copy = current!!
        playingAt = now()
        phase = FilmPhase.Playing
        tried.clear()
        tried += copy.id
        retried.clear()
        iptvBusy = false
        if (!trial) back = null
        if (better == null) usable().takeIf { it.pick?.copy?.id == copy.id }?.heavier?.let { better = it.copy }
        publish()
        prepare()
    }

    private fun failed(failure: StreamFailure) {
        val copy = current!!
        val playing = phase == FilmPhase.Playing
        val place = here()
        attempt = false
        work?.cancel()
        player.stop()
        val permanent = StreamErrors.permanent(failure)
        report(FilmEvent.Failover(copy, failure, permanent, playing))
        if (permanent) fault(copy)
        val to = back
        when {
            to != null -> {
                if (trial) trialOver(copy, works = false)
                reopen(to, place)
            }
            // The provider's one connection is taken: every IPTV copy would get the same answer.
            copy.source == Source.Iptv && StreamErrors.recovery(failure) == Recovery.Busy -> {
                iptvBusy = true
                restart { next(place) }
            }
            !permanent && retried.add(copy.id) -> reopen(copy, place)
            else -> restart { next(place) }
        }
    }

    /** Reopens [copy] at [place] with a fresh URL, else the next copy. */
    private fun reopen(copy: Copy, place: Place) {
        back = null
        trial = false
        links.remove(copy.id)
        restart { url(copy)?.let { open(copy, it, place) } ?: next(place) }
    }

    private fun restart(block: suspend () -> Unit) {
        attempt = false
        work?.cancel()
        phase = FilmPhase.Starting
        openedAt = now()
        publish()
        work = jobs.launch { block() }
    }

    /** Switches to [to] once its URL is ready; what plays keeps playing until then, and stays if it can't be had. */
    private fun switch(to: Copy, back: Copy?, trial: Boolean) {
        work?.cancel()
        work = jobs.launch {
            val url = url(to) ?: return@launch
            val place = here()
            this@FilmSession.back = back
            this@FilmSession.trial = trial
            trialFrom = place.position
            tried += to.id
            open(to, url, place)
        }
    }

    /** A permanent fault: the copy is marked for this setup, and a pin on it cleared (KTD7, KTD12). */
    private fun fault(copy: Copy) {
        choices.mark(setup(), copy, works = false)
        if (choices.pin(titleId) == copy.id) {
            choices.pin(titleId, null)
            notify(Notice.PinCleared(copy))
        }
        rerank()
    }

    private fun unavailable() {
        attempt = false
        player.stop()
        phase = FilmPhase.Unavailable
        publish()
    }

    /** R9: three rebuffers within ten minutes step down to the next lighter working copy, unless this one is pinned. */
    private fun rebuffered() {
        val t = now()
        rebuffers.addLast(t)
        while (t - rebuffers.first() > STALL_WINDOW_MS) rebuffers.removeFirst()
        if (rebuffers.size < STALLS || trial) return
        rebuffers.clear()
        val from = current!!
        val to = usable().lighter(from)?.copy ?: return
        if (choices.pin(titleId) == from.id) return offer(OfferKind.Lighter, to)
        report(FilmEvent.StepDown(from, to))
        notify(Notice.SteppedDown(to))
        better = from
        fitsSince = null
        switch(to, back = null, trial = false)
    }

    private fun tick() {
        val t = now()
        notices.firstOrNull()?.let { if (t - noticeAt >= if (it is Notice.Offer) OFFER_MS else INFO_MS) dismiss(accepted = false) }
        if (attempt && phase == FilmPhase.Starting && t - openedAt >= START_MS) return failed(StreamFailure.Timeout)
        if (phase != FilmPhase.Playing) return
        val copy = current ?: return
        if (trial && player.place.position - trialFrom >= TRIAL_MS) {
            back = null
            choices.mark(setup(), copy, works = true)
            rerank()
            trialOver(copy, works = true)
        }
        offers(t, copy)
    }

    /** KTD11: an offer waits for 5 min of bandwidth 1.3× the better copy's, or 10 s of play for an untested one; never late in the film. */
    private fun offers(t: Long, copy: Copy) {
        val dur = player.duration
        val quiet = attempt && pausedAt == null && !panel && !trial && back == null && (dur <= 0 || dur - player.place.position > LATE_MS)
        val b = better
        val mbps = bandwidth.sessionMbps
        fitsSince = if (quiet && b != null && b.mbps > 0f && mbps != null && mbps >= MARGIN * b.mbps) fitsSince ?: t else null
        if (!quiet || notices.isNotEmpty()) return
        if (b != null && fitsSince?.let { t - it >= FIT_MS } == true) return offer(OfferKind.Better, b)
        if (OfferKind.Untested in spent || t - (playingAt ?: t) < SETTLE_MS || choices.pin(titleId) == copy.id) return
        usable().offer?.copy?.takeIf { it.id != copy.id }?.let { offer(OfferKind.Untested, it) }
    }

    private fun offer(kind: OfferKind, copy: Copy) {
        if (kind !in spent && notices.none { it is Notice.Offer }) notify(Notice.Offer(kind, copy))
    }

    private fun notify(n: Notice) {
        notices.addLast(n)
        if (notices.size == 1) noticeAt = now()
        publish()
    }

    private fun dismiss(accepted: Boolean): Notice? {
        val n = notices.removeFirstOrNull() ?: return null
        if (n is Notice.Offer) {
            spent += n.kind
            report(FilmEvent.Offer(n.kind, n.copy, accepted))
        }
        noticeAt = now()
        publish()
        return n
    }

    private fun trialOver(copy: Copy, works: Boolean) {
        trial = false
        report(FilmEvent.Trial(copy, works))
        notify(Notice.TrialResult(copy, works))
    }

    private fun here() = if (phase == FilmPhase.Playing) player.place else openPlace

    private fun typical(source: Source) = bandwidth.typicalMbps(source, now())

    /** The copies that can play now: IPTV's, and Real-Debrid's that are ready (R1). */
    private fun usable() = choices.rank(titleId, copies.filter { it.source == Source.Iptv || rd[it.id] is RdCheck.Ready }, originalLanguage, setup(), ::typical)

    private fun publish() {
        _state.value = FilmState(phase, current, ranking, rd.toMap(), trial, notices.firstOrNull())
    }

    companion object {
        /** Play waits this long for copies, and for Real-Debrid while IPTV could play instead (KTD13). */
        const val WAIT_MS = 4_000L
        const val OFFER_MS = 12_000L
        const val INFO_MS = 6_000L
        private const val TICK_MS = 1_000L
        /** First frame of a film, over a connect and the 1.5 s start buffer. */
        private const val START_MS = 15_000L
        private const val STALLS = 3
        private const val STALL_WINDOW_MS = 10 * 60_000L
        private const val MARGIN = 1.3f
        private const val FIT_MS = 5 * 60_000L
        private const val LATE_MS = 15 * 60_000L
        private const val SETTLE_MS = 10_000L
        private const val TRIAL_MS = 30_000L
        private const val PAUSE_MS = 10 * 60_000L
        /** A link fetched ahead is used within this long, the same as a pause that forces a fresh one. */
        private const val LINK_MS = PAUSE_MS
    }
}
