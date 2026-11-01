package com.mohamed3on.iptv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mohamed3on.iptv.data.FeedSync
import com.mohamed3on.iptv.data.PrefsStore
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.VpnMonitor
import com.mohamed3on.iptv.data.WatchHistory
import com.mohamed3on.iptv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.time.Duration

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.sync.loadCached()
            graph.sync.refresh(Duration.ZERO, Duration.ZERO) // launch refresh; playback never waits on it (R25)
        }
    }

    /** Channel logos load through the shared HTTP client; the banner swaps them on every zap, so no crossfade. */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
        .crossfade(false)
        .build()
}

val Context.graph get() = (applicationContext as App).graph

/** App-wide singletons. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The one HTTP client: feed sync now, Media3's OkHttp data source later. */
    val http = OkHttpClient()
    val prefs = PrefsStore(context.getSharedPreferences("iptv", Context.MODE_PRIVATE))
    val route = Route(prefs, VpnMonitor(context).active, scope)
    val history = WatchHistory(prefs)
    val sync = FeedSync(http, BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN, context.filesDir, prefs)
    val updater = Updater(context, http, BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN, scope)
}
