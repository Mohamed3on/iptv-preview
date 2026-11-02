package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Cushion
import org.junit.Assert.assertEquals
import org.junit.Test

class CushionTest {
    private val min = 60_000L

    @Test fun eachStallOnTheSameFeedWaitsForABiggerCushionUpTo10s() {
        val c = Cushion()
        assertEquals(1_500L, c.resumeMs(0))
        assertEquals(listOf(1_500L, 3_000L, 6_000L, 10_000L, 10_000L), (1..5).map { c.onRebuffer(it * 1_000L) })
        assertEquals(10_000L, c.resumeMs(6_000))
    }

    @Test fun aNewTuneStartsBackAtTheUsualCushion() {
        val c = Cushion()
        repeat(3) { c.onRebuffer(it * 1_000L) }
        c.onTune()
        assertEquals(1_500L, c.resumeMs(4_000))
        assertEquals(1_500L, c.onRebuffer(5_000))
        assertEquals(3_000L, c.onRebuffer(6_000))
    }

    @Test fun tenMinutesWithoutAStallStepsDownOneLevelAtATime() {
        val c = Cushion()
        repeat(3) { c.onRebuffer(0) } // 6 s
        assertEquals(6_000L, c.resumeMs(10 * min - 1))
        assertEquals(3_000L, c.resumeMs(10 * min))
        assertEquals(1_500L, c.resumeMs(20 * min))
        // One stall left on the books at 20 min, gone at 30: the next stall is a first one again.
        assertEquals(1_500L, c.onRebuffer(30 * min))
    }

    @Test fun aStallRestartsTheQuietClock() {
        val c = Cushion()
        repeat(3) { c.onRebuffer(0) } // 6 s
        assertEquals(10_000L, c.onRebuffer(9 * min))
        assertEquals(10_000L, c.resumeMs(18 * min))
        assertEquals(6_000L, c.resumeMs(19 * min))
    }
}
