package com.mohamed3on.iptv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Keeps voice typing on in the Fire TV keyboard (Search's mic). At boot that keyboard starts before
 * its downloaded settings and keeps a built-in list of voice languages without en-GB, the stick's
 * language: it logs "Voice input is disabled since user's locale = en_GB is not supported" until it
 * is bound again, and bound again it reads the downloaded list, which has en-GB. So once per boot
 * the app switches the keyboard off and on, as `ime disable` + `ime set` would; that needs
 * WRITE_SECURE_SETTINGS, granted once over adb (`pm grant <app> android.permission.WRITE_SECURE_SETTINGS`).
 */
object VoiceFix {
    private const val TAG = "IptvVoiceFix"
    /** Late enough after boot that the keyboard's downloaded settings are there to read. */
    private const val AFTER_BOOT_MS = 120_000L
    private const val DONE_FOR_BOOT = "voiceFixBoot"
    private const val ENABLED = Settings.Secure.ENABLED_INPUT_METHODS
    private const val SUBTYPE = "selected_input_method_subtype" // hidden constant

    suspend fun onceThisBoot(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        val boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.getSharedPreferences("voice-fix", Context.MODE_PRIVATE)
        if (boot < 0 || prefs.getInt(DONE_FOR_BOOT, -1) == boot) return
        delay((AFTER_BOOT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        // Android unbinds a keyboard only when none is selected and none is left to pick, so both are
        // cleared for a moment and then put back.
        val keys = listOf(ENABLED, Settings.Secure.DEFAULT_INPUT_METHOD, SUBTYPE)
        val saved = keys.associateWith { Settings.Secure.getString(resolver, it) ?: return }
        keys.take(2).forEach { Settings.Secure.putString(resolver, it, "") }
        delay(1_000)
        keys.forEach { Settings.Secure.putString(resolver, it, saved[it]) }
        prefs.edit().putInt(DONE_FOR_BOOT, boot).apply()
        Log.i(TAG, "switched the keyboard off and on for boot $boot")
    }
}
