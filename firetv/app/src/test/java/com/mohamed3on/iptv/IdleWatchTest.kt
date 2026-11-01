package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Idle
import com.mohamed3on.iptv.player.IdleWatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleWatchTest {
    private val hour = 60 * 60_000L
    private var stops = 0

    private fun TestScope.watch() = IdleWatch(backgroundScope) { stops++ }.also { runCurrent() }

    private fun TestScope.at(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    @Test fun noKeyFor4HoursAsksAndAKeyWithinAMinuteDismissesIt() = runTest {
        val idle = watch()
        at(4 * hour - 1)
        assertEquals(Idle.Watching, idle.state.value)
        at(4 * hour)
        assertEquals(Idle.Asking, idle.state.value)

        at(4 * hour + 59_000)
        idle.keyPressed()
        assertEquals(Idle.Watching, idle.state.value)
        at(4 * hour + 10 * 60_000)
        assertEquals("playback continues", 0, stops)
        assertEquals(Idle.Watching, idle.state.value)

        at(8 * hour + 59_000 - 1)
        assertEquals("the 4h timer restarted at the dismissing press", Idle.Watching, idle.state.value)
        at(8 * hour + 59_000)
        assertEquals(Idle.Asking, idle.state.value)
    }

    @Test fun noAnswerWithinAMinuteStopsEverySessionAndSaysWhy() = runTest {
        val idle = watch()
        at(4 * hour + 60_000 - 1)
        assertEquals(0, stops)
        at(4 * hour + 60_000)
        assertEquals(1, stops)
        assertEquals(Idle.TimedOut, idle.state.value)

        at(20 * hour)
        assertEquals("stopped once, and it stays stopped", 1, stops)
        assertEquals(Idle.TimedOut, idle.state.value)

        idle.resume()
        assertEquals(Idle.Watching, idle.state.value)
        at(24 * hour)
        assertEquals(Idle.Asking, idle.state.value)
    }

    @Test fun aKeyPressAt3h59mResetsThe4HourTimer() = runTest {
        val idle = watch()
        at(3 * hour + 59 * 60_000)
        idle.keyPressed()
        at(4 * hour)
        assertEquals(Idle.Watching, idle.state.value)
        at(7 * hour + 59 * 60_000 - 1)
        assertEquals(Idle.Watching, idle.state.value)
        at(7 * hour + 59 * 60_000)
        assertEquals(Idle.Asking, idle.state.value)
    }
}
