package com.mohamed3on.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mohamed3on.iptv.diag.DiagnosticsScreen
import com.mohamed3on.iptv.diag.Spike

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Temporary U1 device spike, started over adb with --esa spike_urls (see Spike.kt).
        val spikeUrls = intent.getStringArrayExtra("spike_urls")
        if (spikeUrls != null) Spike.start(this, spikeUrls.toList()) else setContent { DiagnosticsScreen() }
    }
}
