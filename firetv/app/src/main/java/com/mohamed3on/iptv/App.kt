package com.mohamed3on.iptv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mohamed3on.iptv.data.FeedSync
import com.mohamed3on.iptv.data.Lineup
import com.mohamed3on.iptv.data.PrefsStore
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.TitleMigration
import com.mohamed3on.iptv.data.VpnMonitor
import com.mohamed3on.iptv.data.WatchedStore
import com.mohamed3on.iptv.data.WatchHistory
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.Setup
import com.mohamed3on.iptv.system.VoiceFix
import com.mohamed3on.iptv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.sync.loadCached()
            graph.sync.refresh(Duration.ZERO, Duration.ZERO) // launch refresh; playback never waits on it (R25)
            // The movie library is read once the first tune has had the stick to itself, so Movies opens at once.
            delay(5.seconds)
            graph.sync.movies.loadCached()
            withContext(Dispatchers.Main) { graph.setup } // its callbacks land on the main thread too
            graph.migration.run(graph.sync::titleIds)
        }
        graph.scope.launch { VoiceFix.onceThisBoot(this@App) }
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
    /** Favourites and each group's own channel order. */
    val lineup = Lineup(prefs)
    /** Where each film was left, by title id (the provider-id keys before them, "movies.resume" and co, are left alone). */
    val resume = ResumeStore(prefs, "titles.movies.resume")
    /** Where each show was left: its episode and place. */
    val showResume = ResumeStore(prefs, "titles.shows.resume")
    /** Films and episodes watched to the end. */
    val watchedMovies = WatchedStore(prefs, "titles.movies.watched")
    val watchedEpisodes = WatchedStore(prefs, "titles.shows.watched")
    /** Carries the marks saved under provider ids over to title ids, once. */
    val migration = TitleMigration(prefs, resume, showResume, watchedMovies, watchedEpisodes)
    /** Typical film bandwidth per source and hour band. */
    val capacity = Capacity(prefs)
    val sync = FeedSync(http, BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN, context.filesDir, prefs, onEpisodes = migration::adoptEpisodes)
    /** What the TV, stick and soundbar can show and play; built off the launch path. */
    val setup by lazy { Setup(context) }
    val updater = Updater(context, http, BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN, scope)
}
