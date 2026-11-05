package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.CopiesAnswer
import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.RdCandidate
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.ServerCopy
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A title's copies as the box answered, mapped for the ranker and [FilmSession], with what Versions shows of each. */
class TitleAnswer(val copies: TitleCopies, val rdPending: Boolean, val rdUnavailable: Boolean, val iptvUnavailable: Boolean, private val server: Map<String, ServerCopy>) {
    /** An IPTV copy's container ("mkv"). */
    fun ext(id: String): String? = server[id]?.ext
    /** A Real-Debrid copy's size in bytes; 0 unknown. */
    fun size(id: String): Long = server[id]?.size ?: 0
    /** The release or catalogue name. */
    fun name(id: String): String = server[id]?.name.orEmpty()
}

/**
 * A title prepared ahead of Play (KTD13): its copies ranked for this setup, the Real-Debrid check, and
 * [plays], the copy Play would open, once [rdChecked] (or there is nothing on Real-Debrid). [failed]
 * when the box could not answer: Play then starts the library's IPTV copy.
 */
data class Prepared(
    val answer: TitleAnswer? = null,
    val ranking: Ranking? = null,
    val rd: Map<String, RdCheck> = emptyMap(),
    val rdChecked: Boolean = false,
    val plays: Copy? = null,
    val failed: Boolean = false,
)

/**
 * Every title's copies, kept in memory a while (KTD13). Opening a title page, resting on an Up next card or
 * starting an episode (for the next one) [warm]s a title: copies from the box, the ranking and the first
 * Real-Debrid check, all off the main thread, so Play opens a copy at once. An answer whose Real-Debrid
 * side is still pending is asked again shortly (the box has it by then).
 */
class FilmCopies(
    private val fetch: suspend (String) -> Result<CopiesAnswer>,
    private val debrid: Debrid,
    private val choices: CopyChoices,
    private val capacity: Capacity,
    private val setup: () -> SetupProfile,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long) {
        val state = MutableStateFlow(Prepared())
        @Volatile var rd = false
        @Volatile var checking: Job? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** [title] as it is prepared; preparing it now unless a recent answer is kept. */
    fun state(title: String): StateFlow<Prepared> = entry(title, rd = true).state

    /** Prepares [title] ahead of Play: copies, ranking and the Real-Debrid check. */
    fun warm(title: String) {
        entry(title, rd = true)
    }

    /** For [FilmSession]'s load: the latest answer, waiting for the first; throws when the box can't answer. */
    suspend fun load(title: String): TitleCopies =
        entry(title, rd = false).state.first { it.answer != null || it.failed }.answer?.copies ?: throw IOException("no copies")

    /** Ranks [title] again after a pin, mark or correction in Versions. */
    fun rerank(title: String) {
        val e = entries[title] ?: return
        scope.launch { e.state.value.answer?.let { publish(title, e, it) } }
    }

    private fun entry(title: String, rd: Boolean): Entry {
        val e = synchronized(entries) {
            entries[title]?.takeIf { now() - it.at < KEEP_MS && !it.state.value.failed }
                ?: Entry(now()).also {
                    entries[title] = it
                    scope.launch { gather(title, it) }
                }
        }
        if (rd && !e.rd) {
            e.rd = true
            if (e.state.value.answer != null) check(e)
        }
        return e
    }

    private suspend fun gather(title: String, e: Entry) {
        repeat(ASKS) {
            val a = fetch(title).getOrNull()?.let(::answerOf)
            if (a == null) {
                if (e.state.value.answer == null) e.state.update { it.copy(failed = true) }
                return
            }
            publish(title, e, a)
            if (e.rd) {
                e.checking?.join()
                check(e)
            }
            if (!a.rdPending) return
            delay(REASK_MS)
        }
    }

    private fun publish(title: String, e: Entry, a: TitleAnswer) {
        val ranking = choices.rank(title, a.copies.copies, a.copies.originalLanguage, setup()) { capacity.typicalMbps(it, now()) }
        e.state.update { it.copy(answer = a, ranking = ranking, rdChecked = it.rdChecked || a.copies.rd.isEmpty()).settled() }
    }

    /** Asks Real-Debrid about the unchecked copies in rank order; its answers are kept there too, so Play's check makes no calls. */
    private fun check(e: Entry) {
        if (e.checking?.isActive == true) return
        e.checking = scope.launch {
            val p = e.state.value
            val torrents = p.answer?.copies?.rd ?: return@launch
            val ids = p.ranking?.copies.orEmpty()
                .filter { it.copy.id in torrents && it.copy.id !in p.rd && (it.support != Support.Wont || it.pinned) }.map { it.copy.id }
            val answers = if (ids.isEmpty()) emptyMap() else runCatching { debrid.check(ids.map(torrents::getValue)) }.getOrDefault(emptyMap())
            val byId = ids.mapNotNull { id -> answers[torrents.getValue(id)]?.let { id to it } }.toMap()
            e.state.update { it.copy(rd = it.rd + byId, rdChecked = true).settled() }
        }
    }

    /** What Play would open: the best copy not ruled out that IPTV serves or Real-Debrid has ready. Kept as it was while a check runs. */
    private fun Prepared.settled(): Prepared {
        val r = ranking ?: return this
        if (!rdChecked) return this
        return copy(plays = r.copies.firstOrNull { (it.support != Support.Wont || it.pinned) && (it.copy.source == Source.Iptv || rd[it.copy.id] is RdCheck.Ready) }?.copy)
    }

    private companion object {
        const val KEEP_MS = 30 * 60_000L
        /** A pending Real-Debrid side is asked again this much later, at most [ASKS] asks in all. */
        const val REASK_MS = 2_500L
        const val ASKS = 3
    }
}

/** The box's answer as copies: ids stable across plays ("iptv:<stream>", "rd:<hash>:<file>"), names the ranker's. */
fun answerOf(a: CopiesAnswer): TitleAnswer {
    val server = a.copies.associateBy(::copyId)
    val copies = server.map { (id, s) -> copyOf(id, s) }
    val rd = server.filterValues { it.src == "rd" }.mapValues { (_, s) -> RdCandidate(s.hash, s.file, s.filename, s.cached) }
    return TitleAnswer(
        TitleCopies(copies, rd, a.originalLanguage.ifEmpty { null }),
        rdPending = a.sources.rd == "pending", rdUnavailable = a.sources.rd == "unavailable", iptvUnavailable = a.sources.iptv == "unavailable",
        server = server,
    )
}

private fun copyId(s: ServerCopy) = if (s.src == "rd") "rd:${s.hash}" + (s.file?.let { ":$it" } ?: "") else "iptv:${s.id}"

private fun copyOf(id: String, s: ServerCopy) = Copy(
    id = id,
    source = if (s.src == "rd") Source.RealDebrid else Source.Iptv,
    resolution = s.res,
    hdr = s.hdr.map { HDR[it] ?: it.uppercase() },
    dvProfile = s.dvProfile,
    codec = CODECS[s.codec] ?: s.codec,
    audio = AUDIO[s.audio] ?: s.audio,
    channels = s.ch,
    atmos = s.atmos,
    languages = s.langs,
    englishSubs = "en" in s.subs,
    mbps = (s.kbps / 1000).toFloat(),
)

private val HDR = mapOf("dv" to "DV", "hdr10+" to "HDR10+", "hdr10" to "HDR10", "hlg" to "HLG")
private val CODECS = mapOf("mpeg2" to "mpeg2video")
private val AUDIO = mapOf("dts-hd" to "dtshd")
