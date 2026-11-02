@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * A player for one movie (R11): the same ExoPlayer as live, with the VOD specifics in its media
 * source (every container Media3 reads, mkv and mp4 here, through the shared HTTP client) and a deeper
 * buffer, since a file can be read ahead where live can't. Seeks land on the nearest keyframe: a 4K
 * HEVC seek decoding up to an exact frame takes seconds on the stick, and a step is ±10s anyway.
 * Its owner releases it.
 */
fun moviePlayer(context: Context, http: OkHttpClient): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .build(),
        )
        .build()
        .apply { setSeekParameters(SeekParameters.CLOSEST_SYNC) }

private const val MIN_BUFFER_MS = 20_000
private const val MAX_BUFFER_MS = 50_000
private const val START_BUFFER_MS = 1_500
private const val REBUFFER_MS = 3_000
