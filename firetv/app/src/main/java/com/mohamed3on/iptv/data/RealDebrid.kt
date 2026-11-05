package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A Real-Debrid copy as discovery describes it: the torrent and its file, by Torrentio's file name
 * (`behaviorHints.filename`) or, failing that, its 0-based `fileIdx` (Real-Debrid file ids are 1-based).
 * [cached] is Torrentio's `[RD+]`: some Torrentio user resolved it in the last 14 days. Only those are added unasked.
 */
data class RdCandidate(val hash: String, val fileIdx: Int?, val filename: String? = null, val cached: Boolean = false)

/** The Real-Debrid account as Versions shows it (source status). [NotPremium] holds for the app session: no more Real-Debrid calls. */
enum class RdStatus { Ok, NoToken, TokenInvalid, RateLimited, TooManyDownloads, Denied, NotPremium }

/** A failed Real-Debrid call. Only [permanent] faults (`infringing_file`) mark a copy (KTD7). Never carries the token. */
class RdError(val permanent: Boolean, val code: String) : Exception(code)

sealed interface RdCheck {
    /** Cached in the account; [link] is the file's hoster link, unrestricted afresh by [Debrid.link] on every start. */
    data class Ready(val torrentId: String, val link: String) : RdCheck
    data object NotCached : RdCheck
    data class Failed(val error: RdError) : RdCheck
}

/** What the film session needs from Real-Debrid; faked in its tests. */
interface Debrid {
    val status: StateFlow<RdStatus>
    /** Candidates already downloaded in the account: no adds, so cheap and safe. */
    suspend fun inAccount(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck>
    /**
     * Adds [c], keeping it only when `downloaded`. Adds are spaced (see [AddGate]); without [wait] it returns null
     * rather than wait for its turn. Answers are kept 15 min, except transient failures.
     */
    suspend fun probe(c: RdCandidate, wait: Boolean = true): RdCheck?
    /** A fresh streaming URL for [ready]; never reuse one across sessions (KTD2). Throws [RdError]. */
    suspend fun link(ready: RdCheck.Ready): String

    /**
     * The check order (KTD4), kept here so it can change in one place: the account first, then the `[RD+]` copies
     * ranked above the best account copy, one at a time, at most 3, stopping at a ready copy or once the account
     * refuses (rate limit, token, downloads). With an account copy to play, it never waits for an add's turn.
     */
    suspend fun check(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> {
        val out = LinkedHashMap(inAccount(candidates))
        val wait = out.isEmpty()
        for (c in candidates.takeWhile { it !in out }.filter { it.cached }.take(3)) {
            if (status.value != RdStatus.Ok) break
            val result = probe(c, wait) ?: break
            out[c] = result
            if (result is RdCheck.Ready) break
        }
        return out
    }
}

/**
 * addMagnet's own limit, shared by every client in the process (the default): adds at least 4 s apart, and none
 * for Retry-After or a minute after a 429 or full download slots.
 */
class AddGate {
    internal val lock = Mutex()
    @Volatile internal var lastAdd = Long.MIN_VALUE / 2
    @Volatile internal var pausedUntil = 0L

    companion object {
        val process = AddGate()
    }
}

@Serializable private class Torrent(val id: String, val hash: String, val status: String)
@Serializable private class RdFile(val id: Int, val path: String = "", val bytes: Long = 0, val selected: Int = 0)
@Serializable private class Info(val status: String, val files: List<RdFile> = emptyList(), val links: List<String> = emptyList())
@Serializable private class Added(val id: String)
@Serializable private class Unrestricted(val download: String)
@Serializable private class User(val type: String = "")
@Serializable private class ApiError(val error: String? = null, @SerialName("error_code") val code: Int? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Real-Debrid from the stick's own IP (KTD2). A probe is addMagnet, info (the file list), selectFiles and info;
 * a torrent it added is kept only when `downloaded` and joins the cached account list, so a prewarmed copy
 * needs only a fresh unrestrict at Play. `infringing_file` (451, at addMagnet) goes to [onInfringing] so the
 * box can skip that hash everywhere.
 */
class RealDebrid(
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val base: String = "https://api.real-debrid.com/rest/1.0",
    private val now: () -> Long = System::currentTimeMillis,
    private val gate: AddGate = AddGate.process,
    private val onInfringing: (hash: String) -> Unit = {},
) : Debrid {
    private val _status = MutableStateFlow(RdStatus.Ok)
    override val status: StateFlow<RdStatus> = _status

    private val listLock = Mutex()
    private var listed: List<Torrent>? = null
    private var listedAt = 0L
    private val answers = ConcurrentHashMap<RdCandidate, RdCheck>()
    @Volatile private var notPremium = false
    @Volatile private var accountRead = false

    /** Reads the account (`/user`) once per app session, off Play's path: premium ended means [RdStatus.NotPremium]. */
    suspend fun readAccount() {
        if (accountRead || token() == null) return
        runCatching { call<User>(Request.Builder().url("$base/user")) }.onSuccess {
            accountRead = true
            if (it.type != "premium") lapsed()
        }
    }

    private fun lapsed() {
        notPremium = true
        _status.value = RdStatus.NotPremium
    }

    override suspend fun inAccount(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> {
        if (token() == null) return emptyMap<RdCandidate, RdCheck>().also { _status.value = RdStatus.NoToken }
        if (notPremium) return emptyMap()
        // A new check starts clean once the add pause is over; a bad token stays until a call succeeds.
        if (now() >= gate.pausedUntil && _status.value != RdStatus.TokenInvalid) _status.value = RdStatus.Ok
        return try {
            val list = torrents()
            candidates.mapNotNull { c ->
                (answers[c] as? RdCheck.Ready)?.let { return@mapNotNull c to it }
                list.firstOrNull { it.hash.equals(c.hash, true) && it.status == "downloaded" }
                    ?.let { t -> linkOf(info(t.id), c)?.let { c to RdCheck.Ready(t.id, it).also { r -> answers[c] = r } } }
            }.toMap()
        } catch (e: RdError) {
            emptyMap()
        }
    }

    override suspend fun probe(c: RdCandidate, wait: Boolean): RdCheck? {
        if (notPremium) return RdCheck.Failed(RdError(false, "not_premium"))
        if (now() - listedAt >= KEEP_MS) answers.clear()
        answers[c]?.let { return it }
        if (now() < gate.pausedUntil) return RdCheck.Failed(RdError(false, "too_many_requests"))
        if (!turn(wait)) return null
        return add(c).also { if (it !is RdCheck.Failed || it.error.permanent) answers[c] = it }
    }

    override suspend fun link(ready: RdCheck.Ready): String =
        if (notPremium) throw RdError(false, "not_premium") else call<Unrestricted>(Request.Builder().url("$base/unrestrict/link").post(form("link", ready.link))).download

    /** Takes addMagnet's turn, waiting out the spacing; without [wait], false unless the turn is free now. */
    private suspend fun turn(wait: Boolean): Boolean {
        if (wait) gate.lock.lock() else if (!gate.lock.tryLock()) return false
        try {
            val left = gate.lastAdd + SPACING_MS - now()
            if (left > 0 && !wait) return false
            delay(left)
            if (now() < gate.pausedUntil) return false // refused while it waited
            gate.lastAdd = now()
            return true
        } finally {
            gate.lock.unlock()
        }
    }

    private suspend fun add(c: RdCandidate): RdCheck {
        var added: String? = null
        var keep = false
        return try {
            val id = call<Added>(Request.Builder().url("$base/torrents/addMagnet").post(form("magnet", "magnet:?xt=urn:btih:${c.hash.lowercase()}"))).id
            added = id
            val file = fileOf(info(id).files, c) ?: return RdCheck.NotCached
            call<Unit>(Request.Builder().url("$base/torrents/selectFiles/$id").post(form("files", "${file.id}")))
            val link = info(id).let { if (it.status == "downloaded") linkOf(it, c) else null }
            keep = link != null
            if (link == null) return RdCheck.NotCached
            listLock.withLock { listed = listed?.plus(Torrent(id, c.hash, "downloaded")) }
            RdCheck.Ready(id, link)
        } catch (e: RdError) {
            if (e.permanent) onInfringing(c.hash)
            RdCheck.Failed(e)
        } finally {
            // Uncached torrents would sit as active downloads until Real-Debrid refuses new magnets (KTD4).
            val drop = added
            if (drop != null && !keep) withContext(NonCancellable) { runCatching { call<Unit>(Request.Builder().url("$base/torrents/delete/$drop").delete()) } }
        }
    }

    /** The account's torrents, kept for [KEEP_MS] (as are the answers) and added to by probes. */
    private suspend fun torrents(): List<Torrent> = listLock.withLock {
        listed?.takeIf { now() - listedAt < KEEP_MS } ?: call<List<Torrent>>(Request.Builder().url("$base/torrents?limit=5000")).also {
            answers.clear()
            listed = it
            listedAt = now()
        }
    }

    private suspend fun info(id: String) = call<Info>(Request.Builder().url("$base/torrents/info/$id"))

    private fun form(name: String, value: String) = FormBody.Builder().add(name, value).build()

    /** [c]'s file: by name, else by index, else (no index) the largest. */
    private fun fileOf(files: List<RdFile>, c: RdCandidate): RdFile? =
        files.firstOrNull { c.filename != null && it.path.substringAfterLast('/').equals(c.filename, true) }
            ?: if (c.fileIdx != null) files.firstOrNull { it.id == c.fileIdx + 1 } else files.maxByOrNull { it.bytes }

    /** The hoster link of [c]'s file: links follow the selected files in id order. */
    private fun linkOf(info: Info, c: RdCandidate): String? {
        val selected = info.files.filter { it.selected == 1 }.sortedBy { it.id }
        if (selected.size != info.links.size) return null
        return fileOf(selected, c)?.let { info.links[selected.indexOf(it)] }
    }

    private suspend inline fun <reified T> call(request: Request.Builder): T {
        val auth = request.header("Authorization", "Bearer ${token() ?: throw RdError(false, "no_token")}").build()
        val (code, body, retryAfter) = try {
            withContext(Dispatchers.IO) {
                client.newCall(auth).execute().use { Triple(it.code, it.body.string(), it.header("Retry-After")?.toLongOrNull()) }
            }
        } catch (e: IOException) {
            throw RdError(false, "network")
        }
        if (code !in 200..299) fail(code, body, retryAfter)
        if (now() >= gate.pausedUntil && !notPremium) _status.value = RdStatus.Ok
        return when {
            T::class == Unit::class -> Unit as T
            body.isBlank() -> json.decodeFromString<T>("[]") // an empty torrent list is a 204
            else -> runCatching { json.decodeFromString<T>(body) }.getOrElse { throw RdError(false, "malformed") }
        }
    }

    private fun fail(code: Int, body: String, retryAfter: Long?): Nothing {
        val e = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
        val permanent = e?.code == 35 || e?.error == "infringing_file"
        val refusesAdds = { s: RdStatus -> _status.value = s; gate.pausedUntil = now() + (retryAfter ?: 60) * 1000 }
        when {
            permanent -> {}
            code == 401 || e?.code == 8 -> _status.value = RdStatus.TokenInvalid
            // permission_denied at addMagnet, hoster_not_free at unrestrict: the premium has ended.
            e?.code == 9 || e?.code == 20 -> lapsed()
            code == 429 || e?.code == 5 || e?.code == 34 -> refusesAdds(RdStatus.RateLimited)
            e?.code == 21 -> refusesAdds(RdStatus.TooManyDownloads)
            // Locked or not activated, IP not allowed (a VPN exit?), traffic or fair-use limit.
            code == 403 || e?.code in setOf(14, 15, 22, 23, 36) -> _status.value = RdStatus.Denied
        }
        throw RdError(permanent, e?.error ?: "http_$code")
    }

    private companion object {
        const val KEEP_MS = 15 * 60_000L
        const val SPACING_MS = 4_000L
    }
}
