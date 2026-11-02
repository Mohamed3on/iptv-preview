package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
) {
    private val feedFile = File(dir, "feed.json")
    private val guideFile = File(dir, "guide.db")
    private val moviesFile = File(dir, "movies.json")
    private val channelsLock = Mutex()
    private val guideLock = Mutex()
    private val moviesLock = Mutex()

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state
    private val _guide = MutableStateFlow<GuideDb?>(null)
    /** The open cached guide; replaced (and the old one closed) after each validated download. */
    val guide: StateFlow<GuideDb?> = _guide
    private val _movies = MutableStateFlow<Movies?>(null)
    /** The movie library, null until the cached copy is read or the first download lands. */
    val movies: StateFlow<Movies?> = _movies

    /** Loads whatever is cached, without touching the network. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_state.value !is FeedState.Ready) {
            runCatching { parseFeed(feedFile.readText()) }.onSuccess { _state.value = FeedState.Ready(it) }
        }
        if (_guide.value == null && guideFile.exists()) {
            runCatching { GuideDb.open(guideFile) }.onSuccess { _guide.value = it }
        }
    }

    /** Reads the cached movie list (1.5MB, so only once it's wanted), without touching the network. */
    suspend fun loadCachedMovies() = moviesLock.withLock {
        withContext(Dispatchers.IO) {
            if (_movies.value == null && moviesFile.exists()) {
                // An unreadable copy goes, so the next sync downloads it whole instead of hearing "unchanged".
                runCatching { parseMovies(moviesFile.readText()) }.onSuccess { _movies.value = it }.onFailure { moviesFile.delete() }
            }
        }
    }

    /** Returns null when the movie list is current (fetched, unchanged, or younger than [maxAge]). */
    suspend fun syncMovies(maxAge: Duration): SyncError? = moviesLock.withLock {
        withContext(Dispatchers.IO) {
            if (age(moviesFile) < maxAge && _movies.value != null) return@withContext null
            val tmp = File(dir, "movies.json.tmp")
            val error = when (val d = download("movies", moviesFile, tmp)) {
                Download.Unchanged -> null.also { moviesFile.setLastModified(now()) }
                is Download.Failed -> d.error
                is Download.Done -> try {
                    val movies = parseMovies(tmp.readText())
                    replace(tmp, moviesFile, "movies", d.etag)
                    _movies.value = movies
                    null
                } catch (e: Exception) {
                    SyncError.Malformed
                }
            }
            tmp.delete()
            error
        }
    }

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
