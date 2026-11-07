package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Idle
import com.mohamed3on.iptv.player.IdleWatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleWatchTest {
    @Test
    fun `the idle stop reaches a playing film, not one that has closed`() = runTest {
        val stopped = mutableListOf<String>()
        val watch = IdleWatch(backgroundScope) { stopped += "live" }
        val closed = watch.alsoStop { stopped += "closed film" }
        closed()
        watch.alsoStop { stopped += "film" }

        advanceTimeBy(IdleWatch.IDLE_MS)
        runCurrent()
        assertEquals(Idle.Asking, watch.state.value)
        assertEquals(emptyList<String>(), stopped)

        advanceTimeBy(IdleWatch.ANSWER_MS)
        runCurrent()
        assertEquals(Idle.TimedOut, watch.state.value)
        assertEquals(listOf("live", "film"), stopped)
        backgroundScope.cancel()
    }
}
