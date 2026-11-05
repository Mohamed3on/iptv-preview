package com.mohamed3on.iptv

import android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.nextProfile
import com.mohamed3on.iptv.player.setupProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupTest {
    private fun profile(hdr: IntArray, vararg encodings: Int) = setupProfile(
        "Built-in Screen", hdr, setOf(5, 8), setOf("video/hevc"),
        AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT, *encodings), 8),
    )

    private val beam = intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)

    @Test fun hdr10TvWithABeamHasAtmosButNoDolbyVisionOrTrueHd() {
        val p = profile(intArrayOf(HDR_TYPE_HDR10, HDR_TYPE_HLG), *beam)
        assertEquals(setOf(Hdr.Hdr10, Hdr.Hlg), p.hdr)
        assertTrue(Passthrough.EAc3Joc in p.passthrough)
        assertFalse(Passthrough.TrueHd in p.passthrough)
    }

    @Test fun aBlankReadingKeepsTheLastProfile() {
        val last = profile(intArrayOf(HDR_TYPE_HDR10), *beam)
        assertEquals(last, nextProfile(last, profile(intArrayOf())))
        // TV on, audio switched to PCM: a real reading, so it replaces the last one.
        assertEquals(emptySet<Passthrough>(), nextProfile(last, profile(intArrayOf(HDR_TYPE_HDR10))).passthrough)
    }

    @Test fun dolbyVisionChangesTheFingerprint() {
        assertNotEquals(
            profile(intArrayOf(HDR_TYPE_HDR10), *beam).fingerprint,
            profile(intArrayOf(HDR_TYPE_HDR10, HDR_TYPE_DOLBY_VISION), *beam).fingerprint,
        )
    }
}
