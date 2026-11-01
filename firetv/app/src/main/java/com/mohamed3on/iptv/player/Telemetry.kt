package com.mohamed3on.iptv.player

import android.os.SystemClock
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt

/** What the playing stream really is, measured from it: the banner's badges come from this. */
data class LiveQuality(val width: Int, val height: Int, val fps: Float?, val codec: String, val atmos: Boolean) {
    /** Same rules as the server's probed badges (api/_app-feed.ts). */
    val badges: List<String>
        get() = buildList {
            if (height >= 2160) add("4K") else if (height >= 1080) add("FHD")
            fps?.takeIf { it >= 49f }?.let { add("${it.roundToInt()}fps") }
            if (atmos) add("Atmos")
        }
}

/** The playing stream's quality from the player; null until it has a video format. */
fun liveQuality(exo: ExoPlayer, fps: Float?): LiveQuality? {
    val v = exo.videoFormat?.takeIf { it.width > 0 && it.height > 0 } ?: return null
    // Named as ffprobe names them, so app readings and probe readings compare in the cache.
    val codec = when (v.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "h264"
        MimeTypes.VIDEO_H265 -> "hevc"
        MimeTypes.VIDEO_MPEG2 -> "mpeg2video"
        else -> v.sampleMimeType?.substringAfter('/') ?: "unknown"
    }
    return LiveQuality(v.width, v.height, fps, codec, exo.audioFormat?.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC)
}

/**
 * Reports what happens while watching to the app's own server: tunes with their time to
 * picture, stalls, feed switches and problems (/api/app/events), and each stream's measured
 * quality (/api/app/quality, merged into the quality cache there). Best effort: a failed send
 * is dropped, never retried, and nothing here can affect playback. No URLs are logged.
 */
class Telemetry(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val queue = ArrayList<JsonObject>()
    private val qualityReported = HashSet<Int>()

    init {
        scope.launch {
            while (true) {
                delay(FLUSH_MS)
                flush()
            }
        }
    }

    /** Derives events from a session's state changes; no hooks inside the engine. */
    fun observe(session: StreamSession, route: Route, tile: Int = 0): Job = scope.launch {
        var prev = SessionState()
        var tunedAt = now()
        var shown = false // the current tune has reached a picture
        session.state.collect { s ->
            val before = prev
            val channel = s.requested
            if (channel?.identity != before.requested?.identity) {
                tunedAt = now()
                shown = false
            }
            val feed = s.feed
            val routeName = route.effective.value.name.lowercase()
            when {
                s.phase == Phase.Playing && before.phase != Phase.Playing && !shown && channel != null && feed != null -> {
                    shown = true
                    record("tune") {
                        put("id", feed.id); put("channel", channel.id); put("group", channel.group)
                        put("rank", feed.rank); put("route", routeName); put("ms", now() - tunedAt); put("tile", tile)
                    }
                }
                channel != null && channel.identity == before.requested?.identity && feed != null && before.feed != null && feed.id != before.feed.id && feed.family == before.feed.family ->
                    record("failover") { put("from", before.feed.id); put("to", feed.id); put("playing", before.phase == Phase.Playing) }
                before.phase == Phase.Playing && s.phase == Phase.Connecting && feed != null && feed.id == before.feed?.id ->
                    record("stall") { put("id", feed.id) }
                s.phase != before.phase && s.phase.problem() != null ->
                    record("problem") {
                        put("kind", s.phase.problem()); put("id", (feed ?: channel)?.id ?: 0); put("route", routeName)
                    }
            }
            prev = s
        }
    }

    /** Sends [feed]'s measured quality once per app run, once its frame rate is known. */
    fun reportQuality(feed: Channel, q: LiveQuality) {
        val fps = q.fps ?: return
        if (!qualityReported.add(feed.id)) return
        val body = buildJsonObject {
            put("id", feed.id); put("name", feed.name); put("w", q.width); put("h", q.height)
            put("fps", fps); put("codec", q.codec); put("atmos", q.atmos)
        }
        post("quality", body.toString())
    }

    private fun record(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val event = buildJsonObject { put("type", type); fields() }
        val full = synchronized(queue) {
            if (queue.size < MAX_QUEUE) queue += event
            queue.size >= BATCH
        }
        if (full) scope.launch { flush() }
    }

    private fun flush() {
        val batch = synchronized(queue) { queue.toList().also { queue.clear() } }
        if (batch.isNotEmpty()) post("events", JsonArray(batch).toString())
    }

    private fun post(route: String, json: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder().url("$baseUrl/api/app/$route?t=$token").post(json.toRequestBody(JSON)).build(),
                ).execute().close()
            }
        }
    }

    private fun Phase.problem(): String? = when (this) {
        Phase.Busy -> "busy"
        Phase.GaveUp -> "gaveUp"
        is Phase.WrongRoute -> "wrongRoute"
        Phase.Offline -> "offline"
        else -> null
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val FLUSH_MS = 60_000L
        const val BATCH = 20
        const val MAX_QUEUE = 200
    }
}
