package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Mode
import com.mohamed3on.iptv.player.matchingMode
import com.mohamed3on.iptv.player.measuredFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTest {
    // The stick's modes as Diagnostics reported them (ids are made up).
    private val uhd5994 = Mode(1, 3840, 2160, 59.94006f)
    private val uhd60 = Mode(2, 3840, 2160, 60f)
    private val uhd50 = Mode(3, 3840, 2160, 50f)
    private val uhd25 = Mode(4, 3840, 2160, 25f)
    private val fhd50 = Mode(5, 1920, 1080, 50f)
    private val fhd24 = Mode(6, 1920, 1080, 24f)
    private val stick = listOf(uhd5994, uhd60, uhd50, uhd25, fhd50, fhd24)

    @Test fun a50fpsStreamOn59Hz4kSwitchesTo4k50() = assertEquals(3, matchingMode(uhd5994, stick, 50f))

    @Test fun a25fpsStreamAlsoGoesTo50HzNot25() = assertEquals(3, matchingMode(uhd5994, stick, 25f))

    @Test fun alreadyAt50HzStaysPut() {
        assertNull(matchingMode(uhd50, stick, 50f))
        assertNull("25fps already plays evenly at 50Hz", matchingMode(uhd50, stick, 25f))
    }

    @Test fun noModeForTheRateAtTheCurrentResolutionMeansNoSwitch() =
        assertNull("24Hz exists only at 1080p", matchingMode(uhd5994, stick, 24f))

    @Test fun aNearbyRateIsNotAMatch() {
        assertEquals("59.94fps on 60Hz judders: move to 59.94Hz", 1, matchingMode(uhd60, stick, 59.94f))
        assertNull("no 60Hz mode to offer", matchingMode(uhd5994, listOf(uhd5994), 60f))
    }

    private fun pts(fps: Double, frames: Int, start: Long = 1_000_000L) =
        (0 until frames).map { start + Math.round(it * 1_000_000.0 / fps) }

    @Test fun theFrameRateIsMeasuredFromPresentationTimes() {
        assertEquals(50f, measuredFps(pts(50.0, 60))!!, 0f)
        assertEquals(25f, measuredFps(pts(25.0, 60))!!, 0f)
        assertEquals(59.94f, measuredFps(pts(60_000 / 1001.0, 60))!!, 0f)
        assertEquals(23.976f, measuredFps(pts(24_000 / 1001.0, 60))!!, 0f)
    }

    @Test fun droppedFramesDoNotSkewTheMeasurement() {
        val dropped = pts(50.0, 60).filterIndexed { i, _ -> i % 7 != 3 }
        assertEquals(50f, measuredFps(dropped)!!, 0f)
    }

    @Test fun aRateFarFromAnyStandardIsNotGuessed() = assertNull(measuredFps(pts(40.0, 60)))
}
