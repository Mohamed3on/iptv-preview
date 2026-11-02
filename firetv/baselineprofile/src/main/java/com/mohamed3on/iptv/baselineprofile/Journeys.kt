package com.mohamed3on.iptv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE = "com.mohamed3on.iptv"

/** Presses [code] [times] times, [pauseMs] apart, as a remote would. */
internal fun MacrobenchmarkScope.press(code: Int, times: Int = 1, pauseMs: Long = 250) {
    repeat(times) {
        device.pressKeyCode(code)
        Thread.sleep(pauseMs)
    }
}

/** Launched, with the channels loaded and the hidden channel list built (about 6 s after launch). */
internal fun MacrobenchmarkScope.launchAndSettle() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.gone(By.text("Loading channels…")), 30_000)
    Thread.sleep(8_000)
}

/** The channel list: open on the playing row, scroll, step through the groups, a schedule, close. */
internal fun MacrobenchmarkScope.browseChannels() {
    press(KeyEvent.KEYCODE_DPAD_CENTER, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 8, pauseMs = 150)
    press(KeyEvent.KEYCODE_DPAD_UP, times = 8, pauseMs = 150)
    press(KeyEvent.KEYCODE_DPAD_LEFT, pauseMs = 600)
    // Single presses that rest on each group (each builds its rows), then a quick run past several.
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 6, pauseMs = 500)
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 6, pauseMs = 80)
    press(KeyEvent.KEYCODE_DPAD_UP, times = 12, pauseMs = 300) // back to the group it started on
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 800)
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3, pauseMs = 200)
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 1_000) // the schedule
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 800)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    // Opened on the groups column (Left from the player), and closed again.
    press(KeyEvent.KEYCODE_DPAD_LEFT, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
}

/** The player's other layers and zapping: Recent, the quick panel, Up/Down. */
internal fun MacrobenchmarkScope.playerLayers() {
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 1_000) // recent channels
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_MENU, pauseMs = 1_000) // quick panel
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_DPAD_UP, pauseMs = 2_000) // zap
    press(KeyEvent.KEYCODE_DPAD_DOWN, pauseMs = 2_000)
}
