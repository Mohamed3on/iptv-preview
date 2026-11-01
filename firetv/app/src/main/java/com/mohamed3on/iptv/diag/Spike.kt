@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.diag

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Temporary U1 spike: N live MPEG-TS streams decoding at once, to measure the multi-view tile cap.
 * URLs come only from adb intent extras and are never logged (tile index only). Delete after U1.
 */
object Spike {
    private const val TAG = "IptvSpike"
    private const val REPORT_MS = 5_000L

    private class Tile(val index: Int, val player: ExoPlayer, val overlay: TextView) {
        @Volatile var httpStatus: Int? = null
        var decoder: String? = null
        var droppedFrames = 0L
        var lastRendered = 0
    }

    fun start(activity: ComponentActivity, urls: List<String>) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val handler = Handler(Looper.getMainLooper())
        val columns = if (urls.size == 1) 1 else 2
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val tiles = urls.mapIndexed { index, url ->
            val surface = SurfaceView(activity)
            val overlay = TextView(activity).apply {
                setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); textSize = 12f
                setPadding(12, 8, 12, 8)
            }
            val cell = FrameLayout(activity).apply {
                addView(surface)
                addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            }
            if (index % columns == 0) grid.addView(LinearLayout(activity), LinearLayout.LayoutParams(-1, 0, 1f))
            (grid.getChildAt(grid.childCount - 1) as LinearLayout).addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            createTile(activity, index, url, surface, overlay)
        }
        activity.setContentView(grid)
        tiles.forEach { it.player.play() }

        val report = object : Runnable {
            override fun run() {
                tiles.forEach { report(it) }
                handler.postDelayed(this, REPORT_MS)
            }
        }
        handler.postDelayed(report, REPORT_MS)

        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                handler.removeCallbacks(report)
                tiles.forEach { it.player.release() }
                Log.i(TAG, "released ${tiles.size} players")
            }
        })
    }

    private fun createTile(activity: ComponentActivity, index: Int, url: String, surface: SurfaceView, overlay: TextView): Tile {
        lateinit var tile: Tile
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(object : TransferListener {
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    if (tile.httpStatus == null) tile.httpStatus = (source as? HttpDataSource)?.responseCode
                }
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            })
        val player = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(http, DefaultExtractorsFactory()))
            .build()
        tile = Tile(index, player, overlay)
        // Multi-view plays one tile's audio; the rest decode video only.
        if (index > 0) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
        player.setVideoSurfaceView(surface)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                tile.droppedFrames += droppedFrames
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                tile.decoder = decoderName
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val code = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                if (code != null && tile.httpStatus == null) tile.httpStatus = code
                Log.w(TAG, "tile $index error ${error.errorCodeName} http=${code ?: "-"}${if (code == 458) " (provider busy)" else ""}")
            }
        })
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build())
        player.prepare()
        return tile
    }

    private fun report(tile: Tile) {
        val player = tile.player
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val fps = (rendered - tile.lastRendered) * 1000.0 / REPORT_MS
        tile.lastRendered = rendered
        val format = player.videoFormat?.let {
            "${it.width}x${it.height}@${if (it.frameRate == Format.NO_VALUE.toFloat()) "?" else "%.2f".format(it.frameRate)} ${it.codecs ?: it.sampleMimeType}"
        } ?: "-"
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "?"
        }
        val line = "tile ${tile.index}: http=${tile.httpStatus ?: "-"} state=$state${player.playerError?.let { " error=${it.errorCodeName}" } ?: ""} " +
            "video=$format decoder=${tile.decoder ?: "-"} dropped=${tile.droppedFrames} renderedFps=${"%.1f".format(fps)}"
        Log.i(TAG, line)
        tile.overlay.text = line
    }
}
