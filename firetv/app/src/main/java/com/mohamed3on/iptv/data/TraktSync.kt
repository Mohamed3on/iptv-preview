package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trakt's sign-in as the box reports it (GET /api/app/trakt): off, waiting (with [code] to enter at [url]),
 * expired, denied, on (as [user]) or reconnect.
 */
@Serializable
data class TraktStatus(val state: String = "off", val code: String = "", val url: String = "", val user: String = "")

/** A finished play waiting for the box: [at] is when it finished (epoch ms), the time Trakt records. */
@Serializable
data class FinishedPlay(val title: TitleId, val at: Long)

/**
 * Trakt through the box (KTD19): the Options sign-in, and films and episodes finished here (past 90%) logged
 * to Trakt history. Finished plays are kept in [store] until the box has logged them (Trakt's duplicate counts),
 * so a play finished offline, or before Trakt is signed in, goes later. Sends run on [scope], off the player.
 */
class TraktSync(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<TraktStatus?>(null)
    /** The last sign-in state the box gave; null until it answers. */
    val status: StateFlow<TraktStatus?> = _status
    private val sending = Mutex()

    init {
        scope.launch {
            while (true) {
                delay(RETRY_MS)
                flush()
            }
        }
    }

    /** One play of [title]: the first time it passes 90% it is logged, however often it is passed again. */
    fun play(title: TitleId): (pos: Long, dur: Long) -> Unit {
        var sent = false
        return { pos, dur ->
            if (!sent && dur > 0 && pos >= dur * FINISHED) {
                sent = true
                finished(title)
            }
        }
    }

    /** Queues [title] as finished now and sends it; only TMDB titles, the ones Trakt knows. */
    fun finished(title: TitleId) {
        if (!LOGGABLE.matches(title)) return
        synchronized(this) { save((pending() + FinishedPlay(title, now())).takeLast(CAP)) }
        scope.launch { flush() }
    }

    /** Sends the queued plays in order; stops at the first the box can't take now (offline, Trakt not signed in). */
    suspend fun flush() = sending.withLock {
        withContext(Dispatchers.IO) {
            for (p in pending()) {
                val body = buildJsonObject { put("title", p.title); put("at", p.at) }.toString()
                val code = runCatching { call("trakt-history", body) {} }.getOrNull()
                if (code == null || (code !in 200..299 && code != 400)) return@withContext
                synchronized(this@TraktSync) { save(pending() - p) }
            }
        }
    }

    /** Asks the box for the sign-in state. */
    suspend fun refresh() = withContext(Dispatchers.IO) { runCatching { call("trakt", null) { _status.value = json.decodeFromString(it) } } }

    /** Connects (a new code), cancels the code or disconnects ([action]); the answer is the new state. */
    suspend fun act(action: String) = withContext(Dispatchers.IO) {
        runCatching { call("trakt", buildJsonObject { put("action", action) }.toString()) { _status.value = json.decodeFromString(it) } }
        // Signed in: whatever finished meanwhile goes now.
        if (_status.value?.state == "on") flush()
    }

    /** GET (or POST [body] to) /api/app/[route]; [onBody] gets a successful answer's text. Returns the status code. */
    private fun call(route: String, body: String?, onBody: (String) -> Unit): Int {
        val request = Request.Builder().url("$baseUrl/api/app/$route?t=$token")
            .apply { if (body != null) post(body.toRequestBody(JSON)) }.build()
        return http.newCall(request).execute().use {
            if (it.isSuccessful) onBody(it.body.string())
            it.code
        }
    }

    private fun pending(): List<FinishedPlay> =
        store.get(KEY)?.let { runCatching { json.decodeFromString<List<FinishedPlay>>(it) }.getOrNull() } ?: emptyList()

    private fun save(plays: List<FinishedPlay>) = store.put(KEY, if (plays.isEmpty()) null else json.encodeToString(plays))

    companion object {
        /** Past this share of its length a play counts as finished. */
        const val FINISHED = 0.9
        private const val KEY = "trakt.finished"
        private const val CAP = 500
        private const val RETRY_MS = 10 * 60_000L
        private val LOGGABLE = Regex("""movie:\d+|tv:\d+:s\d+e\d+""")
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
