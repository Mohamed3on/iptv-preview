@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import okhttp3.OkHttpClient

/**
 * Long-lived ExoPlayers reused across zaps (KTD7): one for single view; multi-view takes one per
 * tile with [acquire]. Players are built in onStart and all released in onStop ([releaseAll]).
 * Live streams go through one shared HTTP client (OkHttp follows the provider's cross-host,
 * https-to-http redirects; the network security config allows the cleartext).
 */
class PlayerPool(private val context: Context, http: OkHttpClient) {
    private val liveSources = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(http), ExtractorsFactory { arrayOf(liveTs()) })
        // The session owns every retry (KTD8); ExoPlayer's own retries would hide a 458 for seconds.
        .setLoadErrorHandlingPolicy(NoRetry)
    private val players = mutableListOf<ExoStreamPlayer>()

    /** A multi-view [tile] buffers less: up to three streams share the line and the stick's memory. */
    fun acquire(tile: Boolean = false): ExoStreamPlayer {
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (tile) TILE_MIN_BUFFER_MS else MIN_BUFFER_MS,
                        if (tile) TILE_MAX_BUFFER_MS else MAX_BUFFER_MS,
                        START_BUFFER_MS,
                        REBUFFER_MS,
                    )
                    .build()
            )
            .build()
        return ExoStreamPlayer(exo, liveSources).also { players += it }
    }

    fun release(player: ExoStreamPlayer) {
        players -= player
        player.exo.release()
    }

    fun releaseAll() = players.toList().forEach(::release)

    private object NoRetry : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
    }

    private companion object {
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 500
        const val REBUFFER_MS = 1_500
        const val TILE_MIN_BUFFER_MS = 4_000
        const val TILE_MAX_BUFFER_MS = 8_000

        /** The TS extractor as Media3's default factory builds it, minus the sniffing of every other container. */
        fun liveTs() = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT, 0, DefaultSubtitleParserFactory(), TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(0, emptyList()), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
    }
}

/** A player multi-view can mute: only the selected tile is heard (R18). */
interface TilePlayer : StreamPlayer {
    fun setVolume(volume: Float)
}

/**
 * A pooled ExoPlayer as a [StreamPlayer]. [exo] is exposed for the surface, audio tracks (U14)
 * and, later, VOD: live specifics live in the per-item media source, not in the player (R11).
 */
class ExoStreamPlayer internal constructor(val exo: ExoPlayer, private val liveSources: MediaSource.Factory) : TilePlayer {
    private var listener: StreamPlayer.Listener? = null

    init {
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) listener?.onPlaying()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener?.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(StreamErrors.failureOf(error))
            }
        })
    }

    override fun open(url: String) {
        exo.stop()
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
        exo.setMediaSource(liveSources.createMediaSource(item))
        exo.prepare()
    }

    override fun stop() = exo.stop()

    override val position get() = exo.currentPosition

    override fun setVolume(volume: Float) {
        exo.volume = volume
    }

    override fun setListener(listener: StreamPlayer.Listener?) {
        this.listener = listener
    }
}
