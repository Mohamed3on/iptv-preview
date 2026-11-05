package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 */
data class RdCandidate(val hash: String, val fileIdx: Int?, val filename: String? = null)

/** The Real-Debrid account as Versions shows it (source status). */
enum class RdStatus { Ok, NoToken, TokenInvalid, RateLimited, TooManyDownloads, Denied }

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
    /** Adds each candidate (pass a small batch), keeping it only when `downloaded`. Answers are kept a few minutes. */
    suspend fun probe(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck>
    /** A fresh streaming URL for [ready]; never reuse one across sessions (KTD2). Throws [RdError]. */
    suspend fun link(ready: RdCheck.Ready): String

    /**
     * The check order (KTD4), kept here so it can change in one place: the account first, then probes of the
     * candidates ranked above the best account copy, 3 at a time up to 12, stopping at a batch with a ready
     * copy or once the account refuses (rate limit, token, downloads).
     */
    suspend fun check(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> {
        val out = LinkedHashMap(inAccount(candidates))
        for (batch in candidates.take(12).takeWhile { it !in out }.chunked(3)) {
            if (status.value != RdStatus.Ok) break
            val results = probe(batch)
            out += results
            if (results.values.any { it is RdCheck.Ready }) break
        }
        return out
    }
}

@Serializable private class Torrent(val id: String, val hash: String, val status: String)
@Serializable private class RdFile(val id: Int, val path: String = "", val bytes: Long = 0, val selected: Int = 0)
@Serializable private class Info(val status: String, val files: List<RdFile> = emptyList(), val links: List<String> = emptyList())
@Serializable private class Added(val id: String)
@Serializable private class Unrestricted(val download: String)
@Serializable private class ApiError(val error: String? = null, @SerialName("error_code") val code: Int? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Real-Debrid from the stick's own IP (KTD2). A probe is addMagnet, info (the file list), selectFiles and info;
 * a torrent it added is kept only when `downloaded`, so the account list is the cache. addMagnet is
 * rate-limited hard (429 after ~5 quick adds), so a refusal stops adds for its Retry-After or a minute.
 */
class RealDebrid(
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val base: String = "https://api.real-debrid.com/rest/1.0",
    private val now: () -> Long = System::currentTimeMillis,
) : Debrid {
    private val _status = MutableStateFlow(RdStatus.Ok)
    override val status: StateFlow<RdStatus> = _status

    private val listLock = Mutex()
    private var listed: List<Torrent>? = null
    private var listedAt = 0L
    private val answers = ConcurrentHashMap<RdCandidate, RdCheck>()
    @Volatile private var addsAfter = 0L

    override suspend fun inAccount(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> {
        if (token() == null) return emptyMap<RdCandidate, RdCheck>().also { _status.value = RdStatus.NoToken }
        // A new check starts clean once the add pause is over; a bad token stays until a call succeeds.
        if (now() >= addsAfter && _status.value != RdStatus.TokenInvalid) _status.value = RdStatus.Ok
        return try {
            val list = torrents()
            candidates.mapNotNull { c ->
                list.firstOrNull { it.hash.equals(c.hash, true) && it.status == "downloaded" }
                    ?.let { t -> linkOf(info(t.id), c)?.let { c to RdCheck.Ready(t.id, it) } }
            }.toMap()
        } catch (e: RdError) {
            emptyMap()
        }
    }

    override suspend fun probe(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> {
        if (now() - listedAt >= KEEP_MS) answers.clear()
        return coroutineScope { candidates.map { c -> async { c to (answers[c] ?: add(c)) } }.awaitAll() }
            .onEach { (c, r) -> if (r !is RdCheck.Failed) answers[c] = r }.toMap()
    }

    override suspend fun link(ready: RdCheck.Ready): String =
        call<Unrestricted>(Request.Builder().url("$base/unrestrict/link").post(form("link", ready.link))).download

    private suspend fun add(c: RdCandidate): RdCheck {
        if (now() < addsAfter) return RdCheck.Failed(RdError(false, "too_many_requests"))
        var added: String? = null
        var keep = false
        return try {
            val id = call<Added>(Request.Builder().url("$base/torrents/addMagnet").post(form("magnet", "magnet:?xt=urn:btih:${c.hash.lowercase()}"))).id
            added = id
            val file = fileOf(info(id).files, c) ?: return RdCheck.NotCached
            call<Unit>(Request.Builder().url("$base/torrents/selectFiles/$id").post(form("files", "${file.id}")))
            val link = info(id).let { if (it.status == "downloaded") linkOf(it, c) else null }
            keep = link != null
            if (link != null) RdCheck.Ready(id, link) else RdCheck.NotCached
        } catch (e: RdError) {
            RdCheck.Failed(e)
        } finally {
            // Uncached torrents would sit as active downloads until Real-Debrid refuses new magnets (KTD4).
            val drop = added
            if (drop != null && !keep) withContext(NonCancellable) { runCatching { call<Unit>(Request.Builder().url("$base/torrents/delete/$drop").delete()) } }
        }
    }

    /** The account's torrents, kept for [KEEP_MS] (as are the probe answers). */
    private suspend fun torrents(): List<Torrent> = listLock.withLock {
        listed?.takeIf { now() - listedAt < KEEP_MS } ?: call<List<Torrent>>(Request.Builder().url("$base/torrents?limit=5000")).also {
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
        if (now() >= addsAfter) _status.value = RdStatus.Ok
        return when {
            T::class == Unit::class -> Unit as T
            body.isBlank() -> json.decodeFromString<T>("[]") // an empty torrent list is a 204
            else -> runCatching { json.decodeFromString<T>(body) }.getOrElse { throw RdError(false, "malformed") }
        }
    }

    private fun fail(code: Int, body: String, retryAfter: Long?): Nothing {
        val e = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
        val permanent = e?.code == 35 || e?.error == "infringing_file"
        val refusesAdds = { s: RdStatus -> _status.value = s; addsAfter = now() + (retryAfter ?: 60) * 1000 }
        when {
            permanent -> {}
            code == 401 || e?.code == 8 -> _status.value = RdStatus.TokenInvalid
            code == 429 || e?.code == 5 || e?.code == 34 -> refusesAdds(RdStatus.RateLimited)
            e?.code == 21 -> refusesAdds(RdStatus.TooManyDownloads)
            // Locked or not premium, IP not allowed (a VPN exit?), traffic or fair-use limit.
            code == 403 || e?.code in setOf(9, 14, 15, 22, 23, 36) -> _status.value = RdStatus.Denied
        }
        throw RdError(permanent, e?.error ?: "http_$code")
    }

    private companion object {
        const val KEEP_MS = 15 * 60_000L
    }
}
