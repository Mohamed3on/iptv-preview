package com.mohamed3on.iptv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame times of the channel list right after a cold start, with no compilation (as after an update
 * without this profile) and with the baseline profile. On an emulator the numbers are only indicative.
 */
@RunWith(AndroidJUnit4::class)
class ListBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun withoutProfile() = browse(CompilationMode.None())

    @Test
    fun withProfile() = browse(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun browse(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 3,
        setupBlock = { launchAndSettle() },
    ) {
        browseChannels()
    }
}
