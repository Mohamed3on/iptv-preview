package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Why a sync failed. Never carries the URL or token (the repo is public). */
sealed interface SyncError {
    /** No response: no network, DNS failure, refused, timed out, or cut off mid-download. */
    data object Offline : SyncError
    data class Rejected(val status: Int) : SyncError
    /** A 200 whose body is not a usable feed or guide (e.g. a Cloudflare challenge page). */
    data object Malformed : SyncError
}

/** The channel feed as the UI sees it. Failed only happens with no cached feed (first run). */
sealed interface FeedState {
    data object Loading : FeedState
    data class Failed(val error: SyncError) : FeedState
    data class Ready(val feed: Feed) : FeedState
}

/**
 * Keeps feed.json and guide.db in [dir] current. Each download goes to a temp file, is validated,
 * then renamed over the old file, so a failed or malformed refresh keeps the previous data (R25).
 */
class FeedSync(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val dir: File,
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Each show's episodes as they arrive (old watched marks move to title ids then). */
    private val onEpisodes: (List<Episode>) -> Unit = {},
) {
    private val feedFile = File(dir, "feed.json")
    private val guideFile = File(dir, "guide.db")
    private val channelsLock = Mutex()
    private val guideLock = Mutex()

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state
    private val _guide = MutableStateFlow<GuideDb?>(null)
    /** The open cached guide; replaced (and the old one closed) after each validated download. */
    val guide: StateFlow<GuideDb?> = _guide
    /** The Real-Debrid token from the feed, for [RealDebrid]; null until a feed is loaded or when the server has none. */
    val realDebridToken: String? get() = (_state.value as? FeedState.Ready)?.feed?.realDebrid
    /** The TorBox token from the feed, for [TorBox]; null until a feed is loaded or when the server has none. */
    val torboxToken: String? get() = (_state.value as? FeedState.Ready)?.feed?.torbox
    /** The 4K films and UFC replays (v2): the IPTV copy Play falls back on when the box can't list copies. */
    val movies = Library("movies") { text -> parseMovies(text).also { require(it.movies[0].titleId.isNotEmpty()) } }
    /** Movies & Shows (U11): title-first rows of films and shows, revalidated by its ETag (a 304 when unchanged). */
    val library = Library("library", ::parseCatalog)
    /** The owner's history (U16): Trakt and Letterboxd marks, Watchlist and followed shows' next episodes. */
    val history = Library("history", ::parseHistory)

    /** Loads whatever is cached, without touching the network. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_state.value !is FeedState.Ready) {
            runCatching { parseFeed(feedFile.readText()) }.onSuccess { _state.value = FeedState.Ready(it) }
        }
        if (_guide.value == null && guideFile.exists()) {
            runCatching { GuideDb.open(guideFile) }.onSuccess { _guide.value = it }
        }
    }

    /**
     * An on-demand list (/api/app/[kind]) as [parse] reads it: read from disk only once it's wanted, and
     * synced at most every [sync]'s maxAge.
     */
    inner class Library<T : Any>(private val kind: String, private val parse: (String) -> T) {
        private val file = File(dir, "$kind.json")
        private val lock = Mutex()
        private val _items = MutableStateFlow<T?>(null)
        /** Null until the cached copy is read or the first download lands. */
        val items: StateFlow<T?> = _items

        /** Reads the cached copy, without touching the network. */
        suspend fun loadCached() = lock.withLock {
            withContext(Dispatchers.IO) {
                if (_items.value == null && file.exists()) {
                    // An unreadable copy goes, so the next sync downloads it whole instead of hearing "unchanged";
                    // so does one [parse] rejects (saved before titles had ids, say: resume and watched marks are keyed by them).
                    runCatching { parse(file.readText()) }
                        .onSuccess { _items.value = it }.onFailure { file.delete() }
                }
            }
        }

        /** Returns null when the list is current (fetched, unchanged, or younger than [maxAge]); [query] goes on the URL ("&shows=…"). */
        suspend fun sync(maxAge: Duration, query: String = ""): SyncError? = lock.withLock {
            withContext(Dispatchers.IO) {
                if (age(file) < maxAge && _items.value != null) return@withContext null
                val tmp = File(dir, "$kind.json.tmp")
                val error = when (val d = download(kind, file, tmp, query)) {
                    Download.Unchanged -> null.also { file.setLastModified(now()) }
                    is Download.Failed -> d.error
                    is Download.Done -> try {
                        val items = parse(tmp.readText())
                        replace(tmp, file, kind, d.etag)
                        _items.value = items
                        null
                    } catch (e: Exception) {
                        SyncError.Malformed
                    }
                }
                tmp.delete()
                error
            }
        }
    }

    /** A film's plot and running time; fetched when it is first focused, then kept for the app session. */
    suspend fun movieInfo(id: Int): Result<MovieInfo> = withContext(Dispatchers.IO) {
        movieInfos[id]?.let { return@withContext Result.success(it) }
        runCatching { parseMovieInfo(get("movie?id=$id")).also { movieInfos[id] = it } }
    }
    private val movieInfos = java.util.concurrent.ConcurrentHashMap<Int, MovieInfo>()

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    /** GET /api/app/[path] (its query in it), or POST it [body] as JSON; the answer as text, [HttpStatus] when refused. */
    private fun get(path: String, body: String? = null): String {
        val request = Request.Builder().url("$baseUrl/api/app/$path${if ('?' in path) '&' else '?'}t=$token")
        if (body != null) request.post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
        return client.newCall(request.build()).execute().use { res ->
            if (!res.isSuccessful) throw HttpStatus(res.code)
            res.body.string()
        }
    }

    private fun q(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Films and shows for a search (a title, or a genre or language word), from the box; each query's answer is
     * kept for the app session, so typing back over it answers at once.
     */
    suspend fun search(query: String): Result<List<Movie>> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase()
        searches[key]?.let { return@withContext Result.success(it) }
        runCatching { parseTitles(get("search?q=${q(query.trim())}")).also { searches[key] = it } }
    }
    private val searches = java.util.concurrent.ConcurrentHashMap<String, List<Movie>>()

    /**
     * A library title's page: TMDB's plot, people, More like this and scores (/api/app/title); kept for the app session,
     * and asked again when [fresh] (its page opening) once over 2 minutes old: TrueScore's extension adds scores any
     * time. One without scores is asked again (the box fills them on demand), and its scores, once they come, go to [scores].
     */
    suspend fun title(id: TitleId, fresh: Boolean = false): Result<MovieInfo> = withContext(Dispatchers.IO) {
        val kept = titles[id]
        if (kept != null && kept.known && (!fresh || now() - (titleAt[id] ?: 0) < TITLE_MS)) return@withContext Result.success(kept)
        runCatching { parseMovieInfo(get("title?id=${q(id)}")).also { titles[id] = it; titleAt[id] = now(); if (it.known) scores[id] = it } }
            .let { if (it.isFailure && kept != null) Result.success(kept) else it }
    }
    private val titles = java.util.concurrent.ConcurrentHashMap<TitleId, MovieInfo>()
    private val titleAt = java.util.concurrent.ConcurrentHashMap<TitleId, Long>()
    private val TITLE_MS = 2 * 60_000L
    /** Scores title pages learnt for titles the library had none for, so their cards show them too (Compose state). */
    val scores = androidx.compose.runtime.mutableStateMapOf<TitleId, Scored>()

    /** A person's films and shows, best score first; kept for the app session. */
    suspend fun person(id: Int): Result<List<Movie>> = withContext(Dispatchers.IO) {
        people[id]?.let { return@withContext Result.success(it) }
        runCatching { parseTitles(get("person?id=$id")).also { people[id] = it } }
    }
    fun peekPerson(id: Int): List<Movie>? = people[id]
    private val people = java.util.concurrent.ConcurrentHashMap<Int, List<Movie>>()

    /**
     * What a title page says about [m]: TMDB's for a title it knows (the provider's when that fails), else the
     * provider's own, a show's from its episodes call.
     */
    suspend fun info(m: Movie, fresh: Boolean = false): Result<MovieInfo> {
        if (m.titleId.isTmdb) title(m.titleId, fresh).let { if (it.isSuccess || m.id <= 0) return it }
        if (m.id <= 0) return Result.failure(IOException("no provider id"))
        return if (m.titleId.isShow) episodes(m.id).map { it.info } else movieInfo(m.id)
    }

    /** What [info] already holds for [m], without waiting: a screen's first frame. */
    fun peekInfo(m: Movie): MovieInfo? =
        (if (m.titleId.isTmdb) titles[m.titleId] else null) ?: if (m.titleId.isShow) showCache[m.id]?.second?.info else movieInfos[m.id]

    /**
     * A show's episodes: the provider's when it lists the show ([m]'s own id, or the series TMDB's answer names),
     * else TMDB's, which only copies (Real-Debrid's, say) play.
     */
    suspend fun showOf(m: Movie, fresh: Boolean = false): Result<ShowEpisodes> {
        if (m.id > 0) return episodes(m.id, fresh)
        val info = title(m.titleId).getOrElse { return Result.failure(it) }
        if (info.series > 0) return episodes(info.series, fresh)
        return Result.success(ShowEpisodes(info.episodes, info.plot, info.backdrop, info.genre, info.cast, info.rating))
    }

    /** What [showOf] already holds for [m]. */
    fun peekShowOf(m: Movie): ShowEpisodes? {
        if (m.id > 0) return peekShow(m.id)
        val info = titles[m.titleId] ?: return null
        return if (info.series > 0) peekShow(info.series) else ShowEpisodes(info.episodes, info.plot, info.backdrop, info.genre, info.cast, info.rating)
    }

    /**
     * What a film really is, measured while it played ([reading] from player.filmReading), so the server
     * knows its quality rather than its category's claim. Once per film per app session; best effort.
     */
    suspend fun reportFilmQuality(id: Int, reading: Map<String, Any>) = withContext(Dispatchers.IO) {
        if (!filmsReported.add(id)) return@withContext
        runCatching {
            val body = kotlinx.serialization.json.JsonObject((reading + ("id" to id)).mapValues { (_, v) ->
                when (v) {
                    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                }
            }).toString()
            get("film-quality", body)
            movieInfos.remove(id)
        }
    }
    private val filmsReported = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** What [episodes] already holds for a show, without waiting. */
    fun peekShow(id: Int): ShowEpisodes? = showCache[id]?.second

    /**
     * A show's facts and episodes, in order; kept for [SHOW_KEEP_MS] (focus moving over shows asks for them
     * often), then fetched again so new episodes appear. [fresh] skips the kept copy (opening a show).
     */
    suspend fun episodes(seriesId: Int, fresh: Boolean = false): Result<ShowEpisodes> = withContext(Dispatchers.IO) {
        showCache[seriesId]?.takeIf { !fresh && System.currentTimeMillis() - it.first < SHOW_KEEP_MS }?.let { return@withContext Result.success(it.second) }
        runCatching {
            parseEpisodes(get("episodes?id=$seriesId")).also {
                showCache[seriesId] = System.currentTimeMillis() to it
                onEpisodes(it.episodes)
            }
        }
    }

    /** Title ids for the provider ids marks were saved under (the one-time move to title ids); throws on failure. */
    suspend fun titleIds(request: TitleIdRequest): TitleIdMap = withContext(Dispatchers.IO) {
        json.decodeFromString(TitleIdMap.serializer(), get("title-ids", json.encodeToString(TitleIdRequest.serializer(), request)))
    }

    /** Every copy of a film or episode the box knows, IPTV's and Real-Debrid's (/api/app/copies); not kept here (FilmCopies keeps them). */
    suspend fun copies(title: TitleId): Result<CopiesAnswer> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(CopiesAnswer.serializer(), get("copies?title=${q(title)}")) }
    }

    /** English, then Arabic, subtitles for the copy playing (/api/app/subtitles): its OpenSubtitles [hash] ("" unknown) and release [name] pick the best. */
    suspend fun subtitles(title: TitleId, hash: String, size: Long, name: String): Result<SubtitlesAnswer> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(SubtitlesAnswer.serializer(), get("subtitles?title=${q(title)}&hash=$hash&size=$size&name=${q(name)}")) }
    }

    /** One subtitle file as SRT text; null when today's downloads are spent (HTTP 429). */
    suspend fun subtitle(id: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                get("subtitle?id=$id")
            } catch (e: HttpStatus) {
                if (e.code == 429) null else throw e
            }
        }
    }

    /** Torrents Real-Debrid refused (infringing_file), so the box drops them from every title's copies; best effort. */
    suspend fun rdInfringing(hashes: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("hashes", kotlinx.serialization.json.JsonArray(hashes.map(::JsonPrimitive)))
            }.toString()
            get("rd-infringing", body)
        }
    }

    /** Puts a film or show on the Trakt watchlist, or takes it off, through the box: false when that failed. */
    suspend fun setWatchlist(title: TitleId, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            get("watchlist", kotlinx.serialization.json.buildJsonObject {
                put("title", JsonPrimitive(title))
                put("on", JsonPrimitive(on))
            }.toString())
        }.isSuccess
    }

    /** Marks a title watched or not ([watched]), or rates it 1–10 or clears its rating (null, with [rate]), on Trakt through the box: false when that failed. */
    suspend fun mark(title: TitleId, watched: Boolean? = null, rating: Int? = null, rate: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            get("mark", kotlinx.serialization.json.buildJsonObject {
                put("title", JsonPrimitive(title))
                if (watched != null) put("watched", JsonPrimitive(watched))
                if (rate) put("rating", rating?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            }.toString())
        }.isSuccess
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val showCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, ShowEpisodes>>()
    private val SHOW_KEEP_MS = 30 * 60_000L

    /** Syncs each file older than its max age; ZERO forces a fetch. */
    suspend fun refresh(channelsMaxAge: Duration, guideMaxAge: Duration) {
        syncChannels(channelsMaxAge)
        syncGuide(guideMaxAge)
    }

    /** The first-run Retry button. */
    suspend fun retry() {
        if (_state.value !is FeedState.Ready) _state.value = FeedState.Loading
        refresh(Duration.ZERO, Duration.ZERO)
    }

    /** Returns null when the feed is current (fetched, unchanged, or younger than [maxAge]). */
    suspend fun syncChannels(maxAge: Duration = Duration.ZERO): SyncError? = channelsLock.withLock {
        withContext(Dispatchers.IO) {
            if (age(feedFile) < maxAge) return@withContext null
            val tmp = File(dir, "feed.json.tmp")
            val error = when (val d = download("channels", feedFile, tmp)) {
                Download.Unchanged -> null.also { feedFile.setLastModified(now()) }
                is Download.Failed -> d.error
                is Download.Done -> try {
                    val feed = parseFeed(tmp.readText())
                    replace(tmp, feedFile, "channels", d.etag)
                    _state.value = FeedState.Ready(feed)
                    null
                } catch (e: Exception) {
                    SyncError.Malformed
                }
            }
            tmp.delete()
            if (error != null && _state.value !is FeedState.Ready) _state.value = FeedState.Failed(error)
            error
        }
    }

    /** Returns null when the guide is current (fetched, unchanged, or younger than [maxAge]). */
    suspend fun syncGuide(maxAge: Duration = Duration.ZERO): SyncError? = guideLock.withLock {
        withContext(Dispatchers.IO) {
            if (age(guideFile) < maxAge) return@withContext null
            val gz = File(dir, "guide.db.gz.tmp")
            val tmp = File(dir, "guide.db.tmp")
            val error = when (val d = download("guide", guideFile, gz)) {
                Download.Unchanged -> null.also { guideFile.setLastModified(now()) }
                is Download.Failed -> d.error
                is Download.Done -> try {
                    GZIPInputStream(gz.inputStream()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    GuideDb.open(tmp).close()
                    replace(tmp, guideFile, "guide", d.etag)
                    val old = _guide.value
                    _guide.value = GuideDb.open(guideFile)
                    old?.close()
                    null
                } catch (e: Exception) {
                    SyncError.Malformed
                }
            }
            gz.delete()
            tmp.delete()
            error
        }
    }

    private fun age(file: File) = if (file.exists()) (now() - file.lastModified()).milliseconds else Duration.INFINITE

    private sealed interface Download {
        data object Unchanged : Download
        data class Failed(val error: SyncError) : Download
        data class Done(val etag: String?) : Download
    }

    /** Fetches /api/app/[kind] into [to], sending the stored ETag when [cached] exists. */
    private fun download(kind: String, cached: File, to: File, query: String = ""): Download {
        val request = Request.Builder().url("$baseUrl/api/app/$kind?t=$token$query").apply {
            if (cached.exists()) store.get("etag.$kind")?.let { header("If-None-Match", it) }
        }.build()
        return try {
            client.newCall(request).execute().use { res ->
                when {
                    res.code == 304 -> Download.Unchanged
                    !res.isSuccessful -> Download.Failed(SyncError.Rejected(res.code))
                    else -> {
                        to.outputStream().use { res.body.byteStream().copyTo(it) }
                        Download.Done(res.header("ETag"))
                    }
                }
            }
        } catch (e: IOException) {
            Download.Failed(SyncError.Offline)
        }
    }

    private fun replace(tmp: File, file: File, kind: String, etag: String?) {
        if (!tmp.renameTo(file)) throw IOException("rename failed")
        store.put("etag.$kind", etag)
    }
}

/** `/api/app/copies`: a title's copies, its original language (ISO 639-1, "" unknown), each source's state ("ok", "pending", "unavailable") and an episode's [intro] ([start, end] ms, empty unknown). */
@Serializable
data class CopiesAnswer(val titleId: String = "", val originalLanguage: String = "", val runtime: Int = 0, val sources: CopySources = CopySources(), val copies: List<ServerCopy> = emptyList(), val intro: List<Long> = emptyList())

/** `/api/app/subtitles`: [status] "ok", "none" or "quota" (no subtitles today); [subs] best first, English before Arabic. */
@Serializable
data class SubtitlesAnswer(val status: String = "none", val subs: List<FoundSubtitle> = emptyList())

/** A subtitle file the box found: [lang] "en" or "ar"; [hash] when it was made for this very file. */
@Serializable
data class FoundSubtitle(val id: String, val lang: String, val hash: Boolean = false, val release: String = "")

@Serializable
data class CopySources(val iptv: String = "ok", val rd: String = "ok")

/**
 * One copy as the box describes it (U3): an IPTV stream ([id], [ext]) or a Real-Debrid candidate ([hash], [file],
 * [filename], [size], [cached] for Torrentio's [RD+]); attributes are the box's names, 0 or empty when unknown.
 */
@Serializable
data class ServerCopy(
    val src: String, val id: Int = 0, val ext: String = "mkv", val name: String = "",
    val hash: String = "", val seeds: Int = 0, val file: Int? = null, val filename: String? = null, val size: Long = 0, val cached: Boolean = false,
    val res: Int = 0, val hdr: List<String> = emptyList(), val dvProfile: Int? = null, val codec: String = "", val audio: String = "",
    val ch: Int = 0, val atmos: Boolean = false, val langs: List<String> = emptyList(), val subs: List<String> = emptyList(), val kbps: Double = 0.0,
)
