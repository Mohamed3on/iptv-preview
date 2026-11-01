package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.KeyValueStore

class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
