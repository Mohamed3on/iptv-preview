package com.mohamed3on.iptv.data

import android.content.SharedPreferences

/** The few small values the app persists, behind an interface so JVM tests can use a map. */
interface KeyValueStore {
    fun get(key: String): String?
    /** Stores [value], or removes the key when it is null. */
    fun put(key: String, value: String?)
}

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
