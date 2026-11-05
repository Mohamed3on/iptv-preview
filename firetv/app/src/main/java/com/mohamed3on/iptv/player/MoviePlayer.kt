@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
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
fun moviePlayer(context: Context, http: OkHttpClient, load: FilmLoad = FilmLoad(), onFileSize: (Long) -> Unit = {}): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http).setTransferListener(FileSize(load.bytes, onFileSize))))
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
