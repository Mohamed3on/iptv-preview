package com.mohamed3on.iptv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The paths the stick should run compiled from the first frame after an update. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE, maxIterations = 6, stableIterations = 2) {
        launchAndSettle()
        browseChannels()
        playerLayers()
    }
}
