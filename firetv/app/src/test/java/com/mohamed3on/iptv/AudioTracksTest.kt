@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.mohamed3on.iptv.player.AudioTracks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A real ExoPlayer whose current tracks the test sets, as the TS extractor would report them. */
private class TracksPlayer(exo: ExoPlayer) : ForwardingPlayer(exo) {
    private val listeners = mutableListOf<Player.Listener>()
    private var tracks = Tracks.EMPTY

    override fun addListener(listener: Player.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
    }

    override fun getCurrentTracks() = tracks

    fun emit(tracks: Tracks) {
        this.tracks = tracks
        listeners.forEach { it.onTracksChanged(tracks) }
    }
}

@RunWith(RobolectricTestRunner::class)
class AudioTracksTest {
    private val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    private val player = TracksPlayer(exo)

    @After fun tearDown() = exo.release()

    private fun audio(id: String, mime: String, language: String, channels: Int) = TrackGroup(
        id,
        Format.Builder().setId(id).setSampleMimeType(mime).setLanguage(language).setChannelCount(channels).setSampleRate(48_000).build(),
    )

    private fun video() = TrackGroup("v", Format.Builder().setId("v").setSampleMimeType(MimeTypes.VIDEO_H264).build())

    /** [support] is what the renderer reports: FORMAT_UNSUPPORTED_SUBTYPE when the device has no decoder. */
    private fun group(track: TrackGroup, selected: Boolean, support: Int = C.FORMAT_HANDLED) =
        Tracks.Group(track, false, intArrayOf(support), booleanArrayOf(selected))

    private val english = audio("1/257", MimeTypes.AUDIO_AAC, "eng", 2)
    private val german = audio("1/258", MimeTypes.AUDIO_AC3, "deu", 6)

    @Test fun aFeedWithEnglishAacAndGermanAc3ListsBothAndPickingGermanSwitchesToIt() {
        val tracks = AudioTracks(player)
        player.emit(Tracks(listOf(group(video(), true), group(english, true), group(german, false))))

        val options = tracks.state.value.options
        assertEquals(listOf("en" to "AAC", "de" to "AC3"), options.map { it.language to it.codec })
        assertEquals(listOf(2, 6), options.map { it.channels })
        assertEquals("en", tracks.state.value.selected?.language)

        tracks.select(options.single { it.language == "de" })
        assertEquals(
            "the player is asked for the German AC3 track",
            TrackSelectionOverride(german, 0),
            exo.trackSelectionParameters.overrides[german],
        )
        assertEquals(1, exo.trackSelectionParameters.overrides.size)

        // The player applies it and reports German as selected.
        player.emit(Tracks(listOf(group(video(), true), group(english, false), group(german, true))))
        assertEquals("de", tracks.state.value.selected?.language)
        assertEquals("AC3", tracks.state.value.selected?.codec)
    }

    @Test fun aTrackWhoseCodecHasNoDecoderIsNotListed() {
        val mp2 = audio("1/259", MimeTypes.AUDIO_MPEG_L2, "ita", 2)
        val tracks = AudioTracks(player)
        player.emit(Tracks(listOf(group(english, true), group(mp2, false, C.FORMAT_UNSUPPORTED_SUBTYPE))))

        assertEquals(listOf("en"), tracks.state.value.options.map { it.language })
    }
}
