package com.mohamed3on.iptv.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One playable audio track: [language] as an ISO 639-1 code where known, [codec] a short name ("AC3"). */
data class AudioOption(val language: String?, val codec: String, val channels: Int, val group: TrackGroup, val track: Int)

/** The quick panel offers a choice only when [options] has more than one entry (R13). */
data class AudioState(val options: List<AudioOption> = emptyList(), val selected: AudioOption? = null)

/**
 * The current feed's audio tracks and the switch between them (R13). Tracks the device can't
 * decode (e.g. MP2 on the Fire TV stick, KTD12) are left out, so they're never offered.
 * Main thread only, like the player.
 */
class AudioTracks(private val player: Player) {
    private val _state = MutableStateFlow(audioState(player.currentTracks))
    val state: StateFlow<AudioState> = _state

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.value = audioState(tracks)
        }
    }

    init {
        player.addListener(listener)
    }

    fun select(option: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.track))
            .build()
    }

    fun release() = player.removeListener(listener)
}

private fun audioState(tracks: Tracks): AudioState {
    var selected: AudioOption? = null
    val options = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        // "Supported" counts a decoder that exists but doubts the parameters: only a missing decoder hides a track.
        (0 until group.length).filter { group.isTrackSupported(it, true) }.map { i ->
            val format = group.getTrackFormat(i)
            AudioOption(format.language, codecName(format.sampleMimeType), format.channelCount, group.mediaTrackGroup, i)
                .also { if (group.isTrackSelected(i)) selected = it }
        }
    }
    return AudioState(options, selected)
}

private fun codecName(mime: String?) = when (mime) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    else -> mime?.substringAfter('/')?.uppercase() ?: "?"
}
