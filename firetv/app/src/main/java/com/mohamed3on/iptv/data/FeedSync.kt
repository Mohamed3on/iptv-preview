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
    /** The 4K films and UFC replays (v2). */
    val movies = Library("movies")
    /** The shows (series), in the same shape; each show's episodes come from [episodes]. */
    val shows = Library("shows")

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
     * An on-demand library (/api/app/[kind]): read from disk only once it's wanted (megabytes), and
     * synced at most every [sync]'s maxAge.
     */
    inner class Library(private val kind: String) {
        private val file = File(dir, "$kind.json")
        private val lock = Mutex()
        private val _items = MutableStateFlow<Movies?>(null)
        /** Null until the cached copy is read or the first download lands. */
        val items: StateFlow<Movies?> = _items

        /** Reads the cached copy, without touching the network. */
        suspend fun loadCached() = lock.withLock {
            withContext(Dispatchers.IO) {
                if (_items.value == null && file.exists()) {
                    // An unreadable copy goes, so the next sync downloads it whole instead of hearing "unchanged";
                    // so does one saved before titles had ids (resume and watched marks are keyed by them).
                    runCatching { parseMovies(file.readText()).also { require(it.movies[0].titleId.isNotEmpty()) } }
                        .onSuccess { _items.value = it }.onFailure { file.delete() }
                }
            }
        }

        /** Returns null when the list is current (fetched, unchanged, or younger than [maxAge]). */
        suspend fun sync(maxAge: Duration): SyncError? = lock.withLock {
            withContext(Dispatchers.IO) {
                if (age(file) < maxAge && _items.value != null) return@withContext null
                val tmp = File(dir, "$kind.json.tmp")
                val error = when (val d = download(kind, file, tmp)) {
                    Download.Unchanged -> null.also { file.setLastModified(now()) }
                    is Download.Failed -> d.error
                    is Download.Done -> try {
                        val items = parseMovies(tmp.readText())
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
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/app/movie?t=$token&id=$id").build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                parseMovieInfo(res.body.string()).also { movieInfos[id] = it }
            }
        }
    }
    private val movieInfos = java.util.concurrent.ConcurrentHashMap<Int, MovieInfo>()

    /** Films matching [query] across the provider's whole catalogue (not only the library's rows), best copy first. */
    suspend fun vodSearch(query: String): Result<List<Movie>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder().url("$baseUrl/api/app/vod-search?t=$token&q=$q").build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                parseVodResults(res.body.string())
            }
        }
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
            val request = Request.Builder().url("$baseUrl/api/app/film-quality?t=$token")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)).build()
            client.newCall(request).execute().use { res -> if (res.isSuccessful) movieInfos.remove(id) }
        }
    }
    private val filmsReported = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** What [movieInfo] or [episodes] already holds for a title, without waiting: a screen's first frame. */
    fun peekMovieInfo(id: Int): MovieInfo? = movieInfos[id]
    fun peekShow(id: Int): ShowEpisodes? = showCache[id]?.second

    /**
     * A show's facts and episodes, in order; kept for [SHOW_KEEP_MS] (focus moving over shows asks for them
     * often), then fetched again so new episodes appear. [fresh] skips the kept copy (opening a show).
     */
    suspend fun episodes(seriesId: Int, fresh: Boolean = false): Result<ShowEpisodes> = withContext(Dispatchers.IO) {
        showCache[seriesId]?.takeIf { !fresh && System.currentTimeMillis() - it.first < SHOW_KEEP_MS }?.let { return@withContext Result.success(it.second) }
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/app/episodes?t=$token&id=$seriesId").build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                parseEpisodes(res.body.string()).also {
                    showCache[seriesId] = System.currentTimeMillis() to it
                    onEpisodes(it.episodes)
                }
            }
        }
    }

    /** Title ids for the provider ids marks were saved under (the one-time move to title ids); throws on failure. */
    suspend fun titleIds(request: TitleIdRequest): TitleIdMap = withContext(Dispatchers.IO) {
        val body = json.encodeToString(TitleIdRequest.serializer(), request)
        val call = Request.Builder().url("$baseUrl/api/app/title-ids?t=$token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)).build()
        client.newCall(call).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            json.decodeFromString(TitleIdMap.serializer(), res.body.string())
        }
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
    private fun download(kind: String, cached: File, to: File): Download {
        val request = Request.Builder().url("$baseUrl/api/app/$kind?t=$token").apply {
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
