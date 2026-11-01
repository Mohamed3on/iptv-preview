package com.mohamed3on.iptv

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.FeedState
import com.mohamed3on.iptv.data.StartAction
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.data.startAction
import com.mohamed3on.iptv.diag.DiagnosticsScreen
import com.mohamed3on.iptv.diag.Spike
import com.mohamed3on.iptv.player.FrameRate
import com.mohamed3on.iptv.player.Idle
import com.mohamed3on.iptv.player.IdleWatch
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.PlayerPool
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Scrim
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.focusLift
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    // Placeholder player wiring until U8: plays the start channel full-screen, Up/Down zap within
    // its group, OK retries or takes the wrong-route switch, Menu toggles diagnostics.
    private val surface by lazy { SurfaceView(this).apply { keepScreenOn = true } }
    private val session = MutableStateFlow<StreamSession?>(null)
    private val diagnostics = MutableStateFlow(false)
    private val idle = MutableStateFlow<IdleWatch?>(null)

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
            val playing by session.collectAsState()
            val diag by diagnostics.collectAsState()
            val idleState = idle.collectAsState().value?.state?.collectAsState()?.value
            Root(state, playing, surface, diag, idleState, onRetry = { graph.scope.launch { sync.retry() } })
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
        // The pool lives from onStart to onStop (KTD7).
        val pool = PlayerPool(this, graph.http)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val player = pool.acquire()
                player.exo.setVideoSurfaceView(surface)
                val s = StreamSession(player, graph.route, this, SystemClock::elapsedRealtime)
                val frameRate = FrameRate(window, player.exo, s.state, this)
                idle.value = IdleWatch(this) { s.stop() }
                launch {
                    s.state.collect {
                        Log.i(TAG, "${it.phase} ${it.feed?.name ?: "-"}")
                        if (it.phase == Phase.Playing) it.requested?.let(graph.history::watched)
                    }
                }
                launch {
                    val feed = sync.state.filterIsInstance<FeedState.Ready>().first().feed
                    startChannel(feed)?.let { s.zap(it, feed) }
                }
                session.value = s
                try {
                    awaitCancellation()
                } finally {
                    session.value = null
                    idle.value = null
                    frameRate.detach()
                    s.stop()
                    pool.releaseAll()
                }
            }
        }
    }

    /** The last channel; with none, the first of the list's start group (until the overlay, U9). */
    private fun startChannel(feed: Feed) = when (val action = feed.startAction(graph.history.last)) {
        is StartAction.Play -> action.channel
        is StartAction.OpenList -> feed.channelsIn(action.group).firstOrNull()
    }

    // Every key resets the idle stop (R24). While it asks, any key answers and goes no further;
    // once it has stopped playback, only OK (resume) and Back do anything.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val watch = idle.value ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (watch.state.value) {
            Idle.Watching -> watch.keyPressed()
            Idle.Asking -> {
                if (down) watch.keyPressed()
                return true
            }
            Idle.TimedOut -> {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (down && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    watch.resume()
                    session.value?.retry()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || (keyCode == KeyEvent.KEYCODE_BACK && diagnostics.value)) {
            diagnostics.value = !diagnostics.value
            return true
        }
        val s = session.value
        val feed = (graph.sync.state.value as? FeedState.Ready)?.feed
        if (diagnostics.value || s == null || feed == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = s.state.value.requested ?: return true
                val group = feed.channelsIn(now.group)
                val i = group.indexOfFirst { it.identity == now.identity }
                if (i < 0) return true
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                s.zap(group[(i + step).mod(group.size)], feed)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (s.state.value.phase) {
                is Phase.WrongRoute -> s.switchRoute()
                Phase.Offline, Phase.GaveUp -> s.retry()
                else -> {}
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private companion object {
        const val TAG = "IptvPlayer"
    }
}

@Composable
private fun Root(state: FeedState, session: StreamSession?, surface: SurfaceView, diagnostics: Boolean, idle: Idle?, onRetry: () -> Unit) {
    when (state) {
        FeedState.Loading -> FullScreen { Text("Loading channels", style = MaterialTheme.typography.headlineSmall) }
        is FeedState.Failed -> FullScreen {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Text(state.error.message(), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Retry") }
        }
        is FeedState.Ready -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
            val phase = session?.state?.collectAsState()?.value?.phase
            if (phase != null && phase != Phase.Playing) {
                Text(phase.toString(), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            if (diagnostics) DiagnosticsScreen()
            when (idle) {
                Idle.Asking -> StillWatching()
                Idle.TimedOut -> IdleStopped()
                else -> {}
            }
        }
    }
}

/** The R24 prompt. Its button is only the visible answer: MainActivity takes any key as one. */
@Composable
private fun StillWatching() {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).material().padding(Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text("Still watching?", style = Type.title)
            Text("Playback stops in a minute unless you press a button.", style = Type.body)
            val focus = remember { FocusRequester() }
            var focused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Text(
                "Keep watching",
                style = Type.headline,
                modifier = Modifier
                    .padding(top = Space.m)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .focusLift(focused)
                    .padding(horizontal = Space.xl, vertical = Space.m),
            )
        }
    }
}

@Composable
private fun IdleStopped() {
    Column(
        Modifier.fillMaxSize().background(Palette.ink),
        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Stopped after 4 hours with no activity", style = Type.title)
        Text("Press OK to resume", style = Type.body)
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
