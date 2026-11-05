@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * A player for one movie (R11): the same ExoPlayer as live, with the VOD specifics in its media
 * source (every container Media3 reads, mkv and mp4 here, through the shared HTTP client) and a deep
 * buffer by bytes (KTD14: [TARGET_BYTES], up to [MAX_BUFFER_MS]), since a file can be read ahead where
 * live can't; the manifest's largeHeap makes room for it. Seeks land on the nearest keyframe: a 4K
 * HEVC seek decoding up to an exact frame takes seconds on the stick, and a step is ±10s anyway.
 * Its owner releases it. [onFileSize] hears the file's full size in bytes, from the first response that says.
 */
fun moviePlayer(context: Context, http: OkHttpClient, heads: FilmHeads, load: FilmLoad = FilmLoad(), onFileSize: (Long) -> Unit = {}): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(HeadSource.Factory(OkHttpDataSource.Factory(http).setTransferListener(FileSize(load.bytes, onFileSize)), heads)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setAllocator(load.allocator)
                .setBufferDurationsMs(MAX_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .setTargetBufferBytes(TARGET_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .build()
        .apply { setSeekParameters(SeekParameters.CLOSEST_SYNC) }

/**
 * [FilmSession]'s view of a film's ExoPlayer: each open carries the place and its audio and subtitle
 * languages over; buffering after it has played (not after an open or a seek, and not while paused) is
 * a rebuffer. Main thread only.
 */
class ExoFilmPlayer(private val exo: ExoPlayer) : FilmPlayer {
    private var listener: FilmPlayer.Listener? = null
    private var played = false
    private var seeking = false
    /** This open's audio checked; the audio language it asked for. */
    private var checked = false
    private var wanted: String? = null

    private val events = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && !played) {
                played = true
                listener?.onPlaying()
            }
        }

        // Once per open: a file whose audio can't play here, or whose track in the language asked for (the film's
        // own, R2) can't while a dub could, is the wrong copy for this setup (e.g. a remux with DTS-HD English). Only
        // a file none of whose tracks plays teaches the session its formats don't play here ([StreamFailure.NoAudio]).
        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (checked || audio.isEmpty()) return
            checked = true
            fun plays(g: Tracks.Group) = (0 until g.length).any { g.isTrackSupported(it, true) }
            val asked = wanted?.let(Util::normalizeLanguageCode)
            val inLanguage = audio.filter { asked != null && it.getTrackFormat(0).language?.let(Util::normalizeLanguageCode) == asked }
            when {
                audio.none(::plays) -> listener?.onError(StreamFailure.NoAudio(audio.mapNotNull { AUDIO_NAMES[it.getTrackFormat(0).sampleMimeType] }.toSet()))
                inLanguage.isNotEmpty() && inLanguage.none(::plays) -> listener?.onError(StreamFailure.Unsupported)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = true
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (played && !seeking && exo.playWhenReady) listener?.onRebuffer()
                Player.STATE_READY -> seeking = false
                Player.STATE_ENDED -> listener?.onEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(StreamErrors.failureOf(error))
        }
    }

    init {
        exo.addListener(events)
    }

    override fun open(url: String, place: Place) {
        exo.stop()
        played = false
        seeking = false
        checked = false
        wanted = place.audio
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(place.audio)
            // A release's "default" subtitles are its own audience's (Russian on a Russian remux): only forced or chosen ones show.
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .apply { if (place.subtitles != null) setPreferredTextLanguage(place.subtitles).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
            .build()
        exo.setMediaItem(MediaItem.fromUri(url), place.position)
        exo.playWhenReady = true
        exo.prepare()
    }

    override fun stop() = exo.stop()
    override fun pause() = exo.pause()
    override fun play() = exo.play()
    override fun seek(position: Long) = exo.seekTo(position)
    override val place get() = Place(exo.currentPosition, language(C.TRACK_TYPE_AUDIO), language(C.TRACK_TYPE_TEXT))
    override val duration get() = exo.duration.coerceAtLeast(0)

    override fun setListener(listener: FilmPlayer.Listener?) {
        this.listener = listener
    }

    fun release() = exo.removeListener(events)

    private fun language(type: Int): String? = exo.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let { g.getTrackFormat(it).language } }
}

/** A film player's loading, read once a second for [BandwidthMeter]: its bytes so far and whether it is filling. */
class FilmLoad {
    internal val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    val bytes = AtomicLong()

    /**
     * The loader wants data: the buffer is well short of its byte and time caps, so bytes come in as fast
     * as the line allows. Near a cap the loader only tops up what playback used, at the film's bitrate.
     */
    fun filling(exo: ExoPlayer) =
        exo.isLoading && allocator.totalBytesAllocated < TARGET_BYTES - TOP_UP_BYTES && exo.totalBufferedDuration < MAX_BUFFER_MS - TOP_UP_MS
}

/** Counts a film's bytes, and reads its total size off its HTTP responses: Content-Range's total, or a whole-file Content-Length. */
private class FileSize(private val bytes: AtomicLong, private val onSize: (Long) -> Unit) : TransferListener {
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val headers = (source as? HttpDataSource)?.responseHeaders ?: return
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val total = header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: header("Content-Length")?.toLongOrNull()?.takeIf { dataSpec.position == 0L }
        if (total != null && total > 0) onSize(total)
    }
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        bytes.addAndGet(bytesTransferred.toLong())
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

/**
 * What a playing film really is, for /api/app/film-quality: the decoded picture and sound named as ffprobe
 * names them, and its bitrate from the file's size over its length. Null until the player knows the picture.
 */
fun filmReading(exo: ExoPlayer, fileBytes: Long): Map<String, Any>? {
    val v = exo.videoFormat?.takeIf { it.width > 0 && it.height > 0 } ?: return null
    val dolbyVision = v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || v.codecs?.startsWith("dvh") == true
    val codec = when (v.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "h264"
        MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> "hevc"
        MimeTypes.VIDEO_AV1 -> "av1"
        MimeTypes.VIDEO_VP9 -> "vp9"
        MimeTypes.VIDEO_MPEG2 -> "mpeg2video"
        else -> v.sampleMimeType?.substringAfter('/')?.filter { it.isLetterOrDigit() }?.take(24) ?: return null
    }
    val hdr = when {
        dolbyVision -> "Dolby Vision"
        v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> ""
    }
    val a = exo.audioFormat
    val audio = when (a?.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dts"
        MimeTypes.AUDIO_OPUS -> "opus"
        else -> null
    }
    val secs = exo.duration.takeIf { it > 0 }?.div(1000.0)
    val kbps = if (secs != null && fileBytes > 0) (fileBytes * 8 / secs / 1000).toInt() else null
    return buildMap {
        put("w", v.width)
        put("h", v.height)
        put("codec", codec)
        put("hdr", hdr)
        v.frameRate.takeIf { it > 0 }?.let { put("fps", it.toDouble()) }
        audio?.let { put("audio", it) }
        a?.channelCount?.takeIf { it > 0 }?.let { put("channels", it) }
        if (a?.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC) put("atmos", true)
        kbps?.takeIf { it > 0 }?.let { put("kbps", it) }
    }
}

private const val TARGET_BYTES = 160 * 1024 * 1024
private const val MAX_BUFFER_MS = 120_000
private const val START_BUFFER_MS = 1_500
private const val REBUFFER_MS = 5_000
/** Margins under the caps, beyond the loader's 1 MB top-ups, where it counts as filling. */
private const val TOP_UP_BYTES = 8 * 1024 * 1024
private const val TOP_UP_MS = 5_000
/** Audio formats the stick can only pass through, by MIME type, as the ranker names them. */
private val AUDIO_NAMES = mapOf(
    MimeTypes.AUDIO_TRUEHD to "truehd", MimeTypes.AUDIO_DTS to "dts", MimeTypes.AUDIO_DTS_HD to "dtshd",
    MimeTypes.AUDIO_DTS_EXPRESS to "dtshd", MimeTypes.AUDIO_DTS_X to "dtsx",
)
