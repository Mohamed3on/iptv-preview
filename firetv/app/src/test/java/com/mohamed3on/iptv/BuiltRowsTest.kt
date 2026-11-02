package com.mohamed3on.iptv

import androidx.compose.runtime.BroadcastFrameClock
import com.mohamed3on.iptv.ui.BuiltRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltRowsTest {
    @Test
    fun `every row is ready when nothing is being built, however far down the list starts`() {
        val rows = BuiltRows(perFrame = 2, onScreen = 8).apply { from = 30 }
        assertTrue(rows.ready(31))
        assertTrue(rows.ready(5_000))
    }

    @Test
    fun `a new source builds a few rows a frame from the first on screen, then all of them`() = runTest {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + clock + Job())
        val rows = BuiltRows(perFrame = 2, onScreen = 6).apply { from = 30 }

        rows.buildFor("g|Spanish Sports", scope)
        assertTrue(rows.ready(31))
        assertFalse(rows.ready(32))

        clock.sendFrame(1)
        assertTrue(rows.ready(33))
        assertFalse(rows.ready(34))

        clock.sendFrame(2)
        assertTrue(rows.ready(5_000))

        // Shown again with its rows still built: nothing to build.
        rows.buildFor("g|Spanish Sports", scope)
        assertTrue(rows.ready(5_000))
        scope.coroutineContext[Job]!!.cancel()
    }
}
