package com.mohamed3on.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.mohamed3on.iptv.data.FeedState
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.diag.DiagnosticsScreen
import com.mohamed3on.iptv.diag.Spike
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Temporary U1 device spike, started over adb with --esa spike_urls (see Spike.kt).
        val spikeUrls = intent.getStringArrayExtra("spike_urls")
        if (spikeUrls != null) {
            Spike.start(this, spikeUrls.toList())
            return
        }
        val sync = graph.sync
        setContent {
            val state by sync.state.collectAsState()
            Root(state, onRetry = { graph.scope.launch { sync.retry() } })
        }
        // While visible: on resume refresh anything older than 1h, then channels every 30 min, guide every 3h.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sync.refresh(30.minutes, 1.hours)
                while (true) {
                    delay(1.minutes)
                    sync.refresh(30.minutes, 3.hours)
                }
            }
        }
    }
}

@Composable
private fun Root(state: FeedState, onRetry: () -> Unit) {
    when (state) {
        FeedState.Loading -> FullScreen { Text("Loading channels", style = MaterialTheme.typography.headlineSmall) }
        is FeedState.Failed -> FullScreen {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Text(state.error.message(), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Retry") }
        }
        // Placeholder until the player and channel overlay land: launch goes to feed.startAction(history.last).
        is FeedState.Ready -> DiagnosticsScreen()
    }
}

private fun SyncError.message() = when (this) {
    SyncError.Offline -> "Can't reach the server. Check the network connection."
    is SyncError.Rejected -> "The server rejected the app (HTTP $status)."
    SyncError.Malformed -> "The server sent something that isn't a channel list."
}

@Composable
private fun FullScreen(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}
