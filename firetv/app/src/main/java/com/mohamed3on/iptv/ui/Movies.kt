package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedContent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import com.mohamed3on.iptv.data.tmdbAt
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.episodeUrl
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.MovieInfo
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.mohamed3on.iptv.data.Catalog
import com.mohamed3on.iptv.data.History
import com.mohamed3on.iptv.data.NextEpisode
import com.mohamed3on.iptv.data.resume
import com.mohamed3on.iptv.data.LIBRARY_CHECK
import com.mohamed3on.iptv.data.LibTitle
import com.mohamed3on.iptv.data.Person
import com.mohamed3on.iptv.data.Scored
import com.mohamed3on.iptv.data.isShow
import com.mohamed3on.iptv.data.isTmdb
import com.mohamed3on.iptv.data.known
import com.mohamed3on.iptv.data.tmdbImage
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.util.Locale
import com.mohamed3on.iptv.data.Resume
import com.mohamed3on.iptv.data.TitleId
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.data.groupLabel
import com.mohamed3on.iptv.data.movieUrl
import com.mohamed3on.iptv.data.posterUrl
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.system.Check
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.Prepared
import com.mohamed3on.iptv.player.Support
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.ActionButton
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.slideFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours
import android.view.KeyEvent as AndroidKeyEvent

private val CARD = DpSize(132.dp, 198.dp)
private const val CARD_SCALE = 1.08f
/** A poster fades in as it loads; Coil skips the fade for posters already in memory. */
private const val POSTER_FADE_MS = 150
private val CARD_SHAPE = RoundedCornerShape(Radius.row)
private val RING = 3.dp
private val EDGE = 48.dp
private val HERO_HEIGHT = 128.dp
/** A film's plot in the hero: two lines at a readable measure. */
private val PLOT_WIDTH = 720.dp
/** Type.headline's line: a row's title above its cards. */
private val ROW_TITLE = 27.dp
/** Where a card's top sits inside its row: under the title and the row's lift room. */
private val CARD_TOP = ROW_TITLE + Space.s + Space.m
private val KEYBOARD_WIDTH = 620.dp
/** Rows slide under the hero through a short fade, never a hard edge; the focused row sits just below it. */
private val FADE = Space.l
/** Focus rests this long before the hero changes, so holding a key never churns it. */
private const val HERO_REST_MS = 90L
private const val FOCUS_FRAMES = 30
/** Title pages and Up next set the name larger than anywhere else: the title is the screen's subject. */
private val BIG_TITLE = Type.display.copy(fontSize = 36.sp, lineHeight = 42.sp)
private val UPNEXT_WIDTH = 520.dp
private val PAGE_WIDTH = 560.dp
private val SHOW_INFO_WIDTH = 380.dp
private val PROGRESS_WIDTH = 200.dp
/** Up next's cards: 16:9, so a backdrop fits whole. */
private val WIDE = DpSize(184.dp, 104.dp)
private val STILL = DpSize(128.dp, 72.dp)
/** Room kept at the hero's right for the Search and Hide watched buttons. */
private val SEARCH_ROOM = 168.dp
/** Prefs key: "1" while the rows leave watched titles out. */
private const val HIDE_WATCHED = "movies.hideWatched"
/** Backdrops decode at 720p: darkened behind text, more pixels show nothing and cost the stick's memory. */
private val BACKDROP_PX = 1280 to 720
private const val BACKDROP_FADE_MS = 400
/** How tall the backdrop's fade into the ground is, above where the rows start. */
private val SOLID_BAND = 72.dp
/** A dark band along the top, so Search reads over a white sky. */
private val TOP_SCRIM = 120.dp
/** How far the backdrop sits back behind the rows page's hero. */
private const val HERO_DIM = 0.35f

/** What plays: a film, or a show's episode (Movies & Shows browses both). */
enum class LibraryKind(val title: String, val noun: String) {
    Movies("Movies & Shows", "films and shows"),
    Shows("Shows", "shows"),
}

/** What is on screen: Movies & Shows while browsing; a film or an episode while one plays. */
internal val LocalLibrary = staticCompositionLocalOf { LibraryKind.Movies }

/** A page over the browsing screen: a title's, or a person's titles. Back takes the top one off. */
private sealed interface LibraryPage {
    data class Title(val movie: Movie, val versions: Boolean = false) : LibraryPage
    data class Cast(val person: Person) : LibraryPage
}

/** Pages kept under the top one, so Back lands where each was left; past this the oldest goes. */
private const val MAX_PAGES = 6

/** A show's episode on screen: the show, its episodes, and which one plays. */
private data class ShowPlay(val show: Movie, val episodes: List<Episode>, val index: Int) {
    val episode get() = episodes[index]
}

/**
 * What plays: the movie (for a show, the episode as one), its [title] id and where it starts (ms);
 * [fallback] is the library's IPTV copy, for when the box can't say what else there is.
 */
private data class Playing(val movie: Movie, val from: Long, val title: TitleId, val fallback: Copy?, val show: ShowPlay? = null)

/** The library's own copy of a film or episode (provider stream [id]); none for a title the provider lacks. */
private fun libraryCopy(id: Int) = if (id > 0) Copy("iptv:$id", Capacity.Source.Iptv) else null

/** How long focus rests on an Up next card or an episode row before its title is prepared, so passing over one costs nothing. */
private const val WARM_REST_MS = 400L
/** Search asks once typing pauses this long. */
private const val SEARCH_DEBOUNCE_MS = 250L
/** The next episode is prepared this long after one starts, so its start has the line to itself. */
private const val NEXT_WARM_MS = 15_000L

/**
 * Movies & Shows: one title-first library of films and shows over the still-playing live channel, which
 * stops only once something plays (the account allows one stream) and resumes when the screen is left with
 * Back. It opens on Up next (what you were watching, Resume focused) when there is any, else on the
 * library's rows. Any title opens its title page (a film's, or a show's with its episodes), whose cast and
 * More like this open further pages; the next episode plays when one ends. [shown] is its entrance (0 → 1);
 * [open] is false while it leaves. [onPlaying] says whether a film or episode is on screen, so the live
 * picture steps aside for it.
 */
@Composable
fun MoviesLayer(
    feed: Feed,
    session: StreamSession,
    tiles: MultiView<ExoStreamPlayer>,
    shown: () -> Float,
    open: Boolean,
    onPlaying: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val sync = graph.sync.library
    /** Where a title was left: films and shows keep separate places (a show's names its episode). */
    fun storeOf(title: TitleId) = if (title.isShow) graph.showResume else graph.resume
    val history by graph.sync.history.items.collectAsState()
    // Watched marks: the stick's own, Trakt's, Letterboxd's and IMDb's together (U16).
    val watched by graph.marks.watched.collectAsState()
    val library by sync.items.collectAsState()
    val filmPlaces by graph.resume.entries.collectAsState()
    val showPlaces by graph.showResume.entries.collectAsState()
    val resume = remember(filmPlaces, showPlaces) { (filmPlaces + showPlaces).sortedByDescending { it.at } }
    val episodesHere by graph.watchedEpisodes.ids.collectAsState()
    val dismissed by graph.dismissed.ids.collectAsState()
    // Followed shows' next episodes (R31): none for a show part-way through (that episode shows instead), nor one watched here or taken out.
    val nextUp = remember(history, showPlaces, episodesHere, dismissed) {
        history?.next.orEmpty().filter { n -> showPlaces.none { it.title == n.show.titleId } && n.episode !in episodesHere && n.episode !in dismissed }
    }
    val live by rememberUpdatedState(session)
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Playing?>(null) }
    // The pages on screen over the browsing screen, the top one last.
    val pages = remember { mutableStateListOf<LibraryPage>() }
    var failed by remember { mutableStateOf<SyncError?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val refreshed = remember { booleanArrayOf(false) }
    LaunchedEffect(attempt) {
        failed = null
        // The history too: from disk at once, then asked again beside the library.
        launch {
            graph.sync.history.loadCached()
            graph.syncHistory()
        }
        // The copy on disk shows at once; the box is asked whether it changed (a 304 when not).
        sync.loadCached()
        val error = sync.sync(LIBRARY_CHECK)
        refreshed[0] = true
        Check.log("movies_refreshed", "ok" to (error == null))
        if (sync.items.value == null) failed = error ?: SyncError.Malformed
        // The provider's films: Play's IPTV copy when the box can't list a film's copies (KTD13).
        graph.sync.movies.loadCached()
        graph.sync.movies.sync(12.hours)
    }
    fun push(page: LibraryPage) {
        if (pages.size >= MAX_PAGES) pages.removeAt(0)
        pages.add(page)
    }
    // Old marks move to title ids at launch; if that failed (offline), opening Movies tries again.
    LaunchedEffect(Unit) { graph.migration.run(graph.sync::titleIds) }
    // The setup reader is built on the main thread (its callbacks land there) before copies are ranked off it.
    remember { graph.setup }
    // While an episode plays, the next one is prepared, so Next episode and the end of this one start at once (KTD13).
    LaunchedEffect(playing) {
        val s = playing?.show ?: return@LaunchedEffect
        delay(NEXT_WARM_MS)
        s.episodes.getOrNull(s.index + 1)?.titleId?.ifEmpty { null }?.let(graph.films::warm)
    }

    // However the layer goes, the live picture comes back.
    DisposableEffect(Unit) { onDispose { onPlaying(false) } }

    // Live TV stays stopped while Movies & Shows is open, even when the app starts it again (Home and back): the
    // provider's one connection and the bandwidth are the films' (KTD20). Leaving re-tunes the same channel.
    LaunchedEffect(session, open) {
        if (!open) {
            if (session.state.value.phase == Phase.Stopped) session.retry()
            return@LaunchedEffect
        }
        session.state.collect {
            if (it.phase == Phase.Stopped) return@collect
            tiles.close()
            session.stop()
        }
    }

    fun leave() = onClose()

    fun play(next: Playing) {
        tiles.close()
        live.stop()
        playing = next
        onPlaying(true)
        // Closing the app mid-film reopens it playing from its saved place (a show: its episode up next).
        graph.prefs.put(Check.LAST_SCREEN, "play:${next.show?.show?.titleId ?: next.title}")
    }

    fun episode(show: Movie, episodes: List<Episode>, index: Int, from: Long): Playing {
        val e = episodes[index]
        val name = listOf(show.title.title, e.code, e.title).filter(String::isNotEmpty).joinToString(" · ")
        val movie = Movie(id = e.id, name = name, poster = show.poster, group = show.group, ext = e.ext)
        return Playing(movie, from, e.titleId, libraryCopy(e.id), ShowPlay(show, episodes, index))
    }

    /** An IPTV copy's stream: the container the box named, else the library's. */
    fun iptvUrl(p: Playing): (Copy) -> String = { c ->
        val id = c.id.removePrefix("iptv:").toInt()
        val ext = graph.films.state(p.title).value.answer?.ext(c.id) ?: p.movie.ext
        if (p.show != null) feed.route.episodeUrl(graph.route.effective.value, id, ext) else feed.route.movieUrl(graph.route.effective.value, id, ext)
    }

    /** The library's IPTV copy of a film: its own provider id, else the provider's film list's (none for a title it lacks). */
    fun fallbackOf(movie: Movie) = libraryCopy(movie.id.takeIf { it > 0 } ?: graph.sync.movies.items.value?.findTitle(movie.titleId)?.id ?: 0)

    /** A film from its saved place, or from the start. */
    fun playFilm(movie: Movie, fromStart: Boolean) =
        play(Playing(movie, if (fromStart) 0 else graph.resume.startAt(movie.titleId), movie.titleId, fallbackOf(movie)))

    /** A show's episode [index], from its saved place or the start. */
    fun playEpisode(show: Movie, episodes: List<Episode>, index: Int, fromStart: Boolean) =
        play(episode(show, episodes, index, if (fromStart) 0 else graph.showResume.startAt(show.titleId, episodes[index].titleId)))

    /** Up next's buttons: a film at its place; a show's episode up next (its episodes are kept a while, so this is quick). */
    fun resumeTitle(movie: Movie, fromStart: Boolean) {
        if (!movie.titleId.isShow) return playFilm(movie, fromStart)
        scope.launch {
            val eps = graph.sync.showOf(movie).getOrNull()?.episodes?.takeIf { it.isNotEmpty() }
            val seen = graph.marks.watched.value
            val place = graph.showResume.find(movie.titleId) ?: nextUp.firstOrNull { it.show.titleId == movie.titleId }?.place()
            if (eps == null) push(LibraryPage.Title(movie)) else playEpisode(movie, eps, upNextIndex(eps, place, seen), fromStart)
        }
    }

    /** The episode after [s] plays, if there is one. */
    fun nextEpisode(s: ShowPlay?): Boolean {
        val next = s?.takeIf { it.index < it.episodes.lastIndex } ?: return false
        // Continue watching moves on to the next episode even if it is left in its first minute.
        graph.showResume.save(next.show, 0, 0, next.episodes[next.index + 1].titleId, next.episodes[next.index + 1].code)
        playing = episode(next.show, next.episodes, next.index + 1, 0)
        return true
    }

    fun stopped() {
        playing = null
        onPlaying(false)
        graph.prefs.put(Check.LAST_SCREEN, (pages.lastOrNull() as? LibraryPage.Title)?.let { "title:${it.movie.titleId}" } ?: "movies")
    }

    // firetv/check.sh (system/Check): the browsing screen is shown (two frames: its rows build a frame late), the
    // Real-Debrid account's state, and the screen an open target names, once the library is in.
    LaunchedEffect(library != null) {
        if (library == null) return@LaunchedEffect
        repeat(2) { withFrameNanos {} }
        Check.log("movies_shown", "from" to if (refreshed[0]) "box" else "disk")
    }
    LaunchedEffect(Unit) { graph.debrid.status.collect { Check.log("rd", "status" to it) } }
    val check by Check.open.collectAsState()
    LaunchedEffect(check, library) {
        val target = check ?: return@LaunchedEffect
        val verb = target.substringBefore(':')
        if (verb == "live") return@LaunchedEffect leave().also { Check.open.value = null }
        val lib = library ?: return@LaunchedEffect
        if (verb != "search") Check.open.value = null // MoviesBrowse takes a search
        if (verb != "play" && playing != null) stopped()
        pages.clear()
        val id = target.substringAfter(':')
        val title = lib.find(id) ?: Movie(id = -1, name = id, group = "", titleId = id)
        when (verb) {
            "title", "versions" -> push(LibraryPage.Title(title, versions = verb == "versions"))
            "play" -> resumeTitle(title, fromStart = false)
        }
    }

    CompositionLocalProvider(LocalLibrary provides LibraryKind.Movies) {
        Box(Modifier.fillMaxSize()) {
            MoviesBrowse(
                library = library,
                resume = resume,
                history = history,
                nextUp = nextUp,
                watched = watched,
                failed = failed,
                onRetry = { attempt++ },
                onRemove = {
                    storeOf(it.titleId).remove(it.titleId)
                    // Its next episode stays out too, until a later one airs.
                    history?.next?.firstOrNull { n -> n.show.titleId == it.titleId }?.let { n -> graph.dismissed.mark(n.episode) }
                },
                active = open && playing == null && pages.isEmpty(),
                hidden = playing != null || pages.isNotEmpty(),
                shown = shown,
                // A person found by search opens their page; anything else its title page.
                onOpen = { m -> m.titleId.removePrefix("person:").takeIf { m.titleId.startsWith("person:") }?.toIntOrNull()?.let { push(LibraryPage.Cast(Person(it, m.name))) } ?: push(LibraryPage.Title(m)) },
                onResume = { resumeTitle(it, fromStart = false) },
                onStartOver = { resumeTitle(it, fromStart = true) },
                onBack = ::leave,
            )
            pages.forEachIndexed { i, page ->
                key(i, page) {
                    val top = i == pages.lastIndex
                    val active = open && playing == null && top
                    val hidden = playing != null || !top
                    val back = { pages.removeAt(pages.lastIndex).also { if (pages.isEmpty()) graph.prefs.put(Check.LAST_SCREEN, "movies") } }
                    when (page) {
                        is LibraryPage.Cast -> PersonPage(page.person, watched, active, hidden, onOpen = { push(LibraryPage.Title(it)) }, onBack = { back() })
                        is LibraryPage.Title -> {
                            val title = page.movie
                            val saved = resume.firstOrNull { it.title == title.titleId } ?: nextUp.firstOrNull { it.show.titleId == title.titleId }?.place()
                            if (!title.titleId.isShow) FilmPage(
                                film = title,
                                openVersions = page.versions,
                                resume = saved,
                                watched = watched,
                                active = active,
                                hidden = hidden,
                                onPlay = { fromStart -> playFilm(title, fromStart) },
                                onOpen = { push(LibraryPage.Title(it)) },
                                onPerson = { push(LibraryPage.Cast(it)) },
                                onBack = { back() },
                            ) else ShowPage(
                                show = title,
                                resume = saved,
                                watched = watched,
                                active = active,
                                hidden = hidden,
                                onPlay = { episodes, e, fromStart -> playEpisode(title, episodes, e, fromStart) },
                                onOpen = { push(LibraryPage.Title(it)) },
                                onPerson = { push(LibraryPage.Cast(it)) },
                                onBack = { back() },
                            )
                        }
                    }
                }
            }
            playing?.let { p ->
                key(p) {
                    val s = p.show
                    // Past 90% the play is logged to Trakt, once this play.
                    val trakt = remember { graph.trakt.play(s?.episode?.titleId ?: p.movie.titleId) }
                    // The player words its states for a film or an episode.
                    CompositionLocalProvider(LocalLibrary provides if (s != null) LibraryKind.Shows else LibraryKind.Movies) {
                        MoviePlayback(
                            movie = p.movie,
                            title = p.title,
                            from = p.from,
                            fallback = p.fallback,
                            iptvUrl = iptvUrl(p),
                            onSave = { pos, dur ->
                                trakt(pos, dur)
                                if (dur > 0 && pos >= dur * 0.95) {
                                    (if (s != null) graph.watchedEpisodes else graph.watchedMovies).mark(s?.episode?.titleId ?: p.movie.titleId)
                                    // The show is followed from here: its next or newly aired episode comes to Up next (R31).
                                    if (s != null && graph.followed.find(s.show.titleId)?.episode != s.episode.titleId)
                                        graph.followed.merge(listOf(s.show.resume(pos, dur, s.episode.titleId, s.episode.code, System.currentTimeMillis())))
                                }
                                if (s == null) graph.resume.save(p.movie, pos, dur) else graph.showResume.save(s.show, pos, dur, s.episode.titleId, s.episode.code)
                            },
                            onEnded = { if (!nextEpisode(s)) stopped() },
                            onClose = ::stopped,
                            onNext = s?.takeIf { it.index < it.episodes.lastIndex }?.let { { nextEpisode(it) } },
                        )
                    }
                }
            }
        }
    }
}

/** A title the provider doesn't carry, as its saved place names it: no provider id (negative, never asked about), so only its copies play it. */
private fun placeTitle(r: Resume) =
    Movie(id = -1 - (r.title.hashCode() and Int.MAX_VALUE), name = listOfNotNull(r.name, r.year?.let { "($it)" }).joinToString(" "), poster = r.poster, group = "", titleId = r.title)

/** A show's episode up next: the one in progress, else the first not yet watched, else the first. */
private fun upNextIndex(episodes: List<Episode>, resume: Resume?, watched: Set<TitleId>): Int =
    episodes.indexOfFirst { it.titleId == resume?.episode }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.titleId !in watched }.takeIf { it >= 0 }
        ?: 0

/** A next episode as Up next's place: the show and its episode, nothing played yet ([Resume.at] 0). */
private fun NextEpisode.place() = Resume(show.titleId, show.title.title, show.poster, show.title.year, 0, 0, 0, episode, label)

/** A row of the library: one group's titles. [progress] is every saved place, by title id. */
private const val CONTINUE_ROW = "h|continue"
/** What was played (or a followed show's episode aired) within this long leads the screen as Up next; older is a Continue watching row. */
private const val RECENT_MS = 7 * 86_400_000L

private class MovieRow(val key: String, val title: String, val badge: String?, val movies: List<Movie>, val progress: Map<TitleId, Float>)

/**
 * The owner's Watchlist when it has titles (R26), then the library's rows (TrueScore's recent and all-time, trending, new,
 * genres, languages; the provider's groups when TMDB's are missing); what is part-way through lives in Up next, not here.
 * [hideWatched] leaves watched titles out of every row (a row left empty goes). What is part-way through but not recent
 * ([continuing]) is a plain Continue watching row after TrueScore's.
 */
private fun rowsOf(library: Catalog?, resume: List<Resume>, history: History?, watched: Set<TitleId>, hideWatched: Boolean, continuing: List<Movie>): List<MovieRow> {
    library ?: return emptyList()
    val progress = resume.filter { it.dur > 0 }.associate { it.title to it.pos.toFloat() / it.dur }
    fun shown(movies: List<Movie>) = if (hideWatched) movies.filter { it.titleId !in watched } else movies
    // The library's own card for a title it has (its genres), else the history's.
    val mine = listOfNotNull(
        history?.watchlist?.let(::shown)?.map { library.find(it.titleId) ?: it }?.takeIf { it.isNotEmpty() }?.let { MovieRow("h|watchlist", "Watchlist", null, it, progress) },
    )
    val rows = library.rows.mapNotNull { row ->
        val label = groupLabel(row.name)
        shown(row.movies).takeIf { it.isNotEmpty() }?.let { MovieRow("r|${row.id}", label.name, label.badge, it, progress) }
    }
    val at = rows.indexOfLast { it.key.startsWith("r|ts-") } + 1
    val later = continuing.takeIf { it.isNotEmpty() }?.let { MovieRow(CONTINUE_ROW, "Continue watching", null, it, progress) }
    return mine + rows.take(at) + listOfNotNull(later) + rows.drop(at)
}

/** Which part of the browsing screen is up. */
private enum class BrowsePage { UpNext, Rows, Search }

/** Where focus sits on the browsing screen, so a page change knows when its own target has it. */
private enum class FocusAt { UpNext, Rows, Search, SearchButton, Other }

/**
 * The browsing screen over the focused title's backdrop: Up next (what you were watching, Resume
 * focused), the library's rows (Down from Up next), or search. While a title page or a film is up it
 * stays composed but undrawn ([hidden]), so Back lands where you were. [active] is when it should hold focus.
 */
@Composable
private fun MoviesBrowse(
    library: Catalog?,
    resume: List<Resume>,
    history: History?,
    nextUp: List<NextEpisode>,
    watched: Set<TitleId>,
    failed: SyncError?,
    onRetry: () -> Unit,
    onRemove: (Movie) -> Unit,
    active: Boolean,
    hidden: Boolean,
    shown: () -> Float,
    onOpen: (Movie) -> Unit,
    onResume: (Movie) -> Unit,
    onStartOver: (Movie) -> Unit,
    onBack: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val prefs = LocalContext.current.graph.prefs
    var hideWatched by remember { mutableStateOf(prefs.get(HIDE_WATCHED) == "1") }
    // Each saved place with its title as last played (its provider id, for its episodes and IPTV copy) and as the
    // library has it now (tier and score), newest first; one played only from Real-Debrid from its own name and poster (R21).
    // Then followed shows' next episodes, latest aired first: one takes the hero only when nothing is part-way through.
    // Only what was played, or aired, in the last week is Up next; the rest is the Continue watching row.
    val (upNext, older) = remember(library, resume, nextUp) {
        if (library == null) return@remember emptyList<Pair<Movie, Resume>>() to emptyList<Movie>()
        val since = System.currentTimeMillis() - RECENT_MS
        val sinceDay = java.time.LocalDate.now().minusDays(RECENT_MS / 86_400_000L).toString()
        val all = resume.map { r ->
            val now = library.find(r.title)
            Triple(r.iptv?.let { if (now != null) it.copy(lib = now.lib, group = now.group) else it } ?: now ?: placeTitle(r), r, r.at >= since)
        } + nextUp.map { n -> Triple(library.find(n.show.titleId) ?: n.show, n.place(), n.air >= sinceDay) }
        all.filter { it.third }.map { it.first to it.second } to all.filterNot { it.third }.map { it.first }
    }
    val rows = remember(library, resume, history, watched, hideWatched, older) { rowsOf(library, resume, history, watched, hideWatched, older) }
    val newShows = remember(nextUp) { nextUp.filter { it.new }.mapTo(HashSet()) { it.show.titleId } }
    var page by remember { mutableStateOf(BrowsePage.UpNext) }
    var beforeSearch by remember { mutableStateOf(BrowsePage.UpNext) }
    // Up next only while there is something to resume.
    val shownPage = if (page == BrowsePage.UpNext && upNext.isEmpty()) BrowsePage.Rows else page
    // The title the hero and the backdrop describe.
    val focused = remember { mutableStateOf<Movie?>(null) }
    var query by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(FocusAt.Other) }
    val content = remember { FocusRequester() }
    val resumeButton = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    val keys = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    // The page is built a frame after the rest, so neither frame of the opening is long enough to drop.
    var built by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        built = true
    }
    val kind = when {
        library != null -> if (built) 2 else -1
        failed != null -> 1
        else -> 0
    }

    // Focus goes to the page's own target whenever the page changes or the screen comes back (after a
    // film or a title page); leaving search goes back to its button.
    val leftSearch = remember { booleanArrayOf(false) }
    // The search result a title page was opened from: back from it, focus returns to that card, not the keyboard.
    var openedResult by remember { mutableStateOf<Int?>(null) }
    val resultBack = remember { FocusRequester() }
    LaunchedEffect(active, kind, shownPage) {
        if (!active) return@LaunchedEffect
        val (target, part) = when {
            kind != 2 -> content to FocusAt.Other
            leftSearch[0] -> searchButton to FocusAt.SearchButton
            shownPage == BrowsePage.Search -> results to FocusAt.Search
            shownPage == BrowsePage.UpNext -> resumeButton to FocusAt.UpNext
            else -> content to FocusAt.Rows
        }
        leftSearch[0] = false
        val card = openedResult?.takeIf { shownPage == BrowsePage.Search }
        try {
            repeat(FOCUS_FRAMES) { i ->
                withFrameNanos {}
                val landed = if (card != null && i < FOCUS_FRAMES / 2) focused.value?.id == card else at == part || part == FocusAt.Other
                if (hasFocus && landed) return@LaunchedEffect
                // The opened card first; gone (a new search), the page's own target.
                runCatching { (if (card != null && i < FOCUS_FRAMES / 2) resultBack else target).requestFocus() }
            }
        } finally {
            if (card != null) openedResult = null
        }
    }
    fun openSearch() {
        beforeSearch = shownPage
        focused.value = null
        page = BrowsePage.Search
    }
    fun closeSearch() {
        leftSearch[0] = true
        focused.value = null
        page = beforeSearch
    }
    // firetv/check.sh's `search:<query>` (system/Check), once the rows are built.
    val check by Check.open.collectAsState()
    LaunchedEffect(check, kind) {
        val q = check?.takeIf { it.startsWith("search:") && kind == 2 }?.removePrefix("search:") ?: return@LaunchedEffect
        Check.open.value = null
        if (page != BrowsePage.Search) openSearch()
        query = q
    }

    val rested = rememberRested(focused)
    val heroInfo = rememberInfo(rested)
    Box(
        Modifier
            .fillMaxSize()
            // Undrawn under a title page or a film: an alpha-0 layer costs the GPU nothing.
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onFocusChanged { hasFocus = it.hasFocus }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) when {
                    shownPage == BrowsePage.Search -> closeSearch()
                    shownPage == BrowsePage.Rows && upNext.isNotEmpty() -> page = BrowsePage.UpNext
                    else -> onBack()
                }
                true // the key-up too, so Back never reaches the player or leaves the app
            }
            .focusGroup(),
    ) {
        Backdrop(backdropOf(rested, heroInfo), solidFrom = if (shownPage == BrowsePage.UpNext) null else Space.safeY + HERO_HEIGHT, modifier = Modifier.graphicsLayer { alpha = shown().coerceIn(0f, 1f) })
        Box(Modifier.fillMaxSize().slideFade(shown, Edge.Bottom, reduced)) {
            when {
                kind == 1 -> Box(Modifier.padding(top = Space.xxxl)) { Unavailable(content, onRetry) }
                kind == 0 -> Box(Modifier.padding(top = Space.xxxl)) { LoadingMovies(content) }
                kind != 2 -> {}
                shownPage == BrowsePage.UpNext -> UpNextPage(
                    entries = upNext,
                    newShows = newShows,
                    resumeButton = resumeButton,
                    onFocused = { focused.value = it },
                    onResume = onResume,
                    onStartOver = onStartOver,
                    onOpen = onOpen,
                    onRemove = onRemove,
                    onDown = { page = BrowsePage.Rows },
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.UpNext },
                )
                else -> Column(Modifier.fillMaxSize().padding(top = Space.safeY)) {
                    val search = shownPage == BrowsePage.Search
                    Hero(
                        if (search && focused.value == null) null else rested, heroInfo, resume,
                        placeholder = if (search) "Search ${LocalLibrary.current.noun}" else LocalLibrary.current.title,
                        modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(start = EDGE, end = EDGE + SEARCH_ROOM),
                    )
                    if (search) Box(Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.Search }) {
                        SearchGrid(
                            library!!, query, { query = it }, results, keys, onFocused = { focused.value = it },
                            back = openedResult?.let { it to resultBack },
                            stray = { !hasFocus },
                            // Coming back to a result is no arrival at Search: the Fire TV keyboard stays shut.
                            arrive = { openedResult == null },
                            onPlay = {
                                openedResult = it.id
                                focused.value = null // so "back on the card" is only true once it is
                                onOpen(it)
                            },
                        )
                    } else Box(Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.Rows }) {
                        Rows(
                            rows, watched, content, active,
                            onFocused = { focused.value = it },
                            onOpen = onOpen,
                            onRemove = onRemove,
                            // Up from the first row: back to Up next, else to Search.
                            onTop = { if (upNext.isNotEmpty()) page = BrowsePage.UpNext else runCatching { searchButton.requestFocus() } },
                        )
                    }
                }
            }
            if (kind == 2 && shownPage != BrowsePage.Search) Column(
                Modifier.align(Alignment.TopEnd).padding(top = Space.safeY, end = EDGE),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                PageButton(
                    "Search", ::openSearch,
                    Modifier.focusRequester(searchButton).onFocusChanged { if (it.isFocused) at = FocusAt.SearchButton },
                )
                // Watched titles in the rows, or not (Up next and search keep them); kept across launches.
                PageButton(if (hideWatched) "Show watched" else "Hide watched", {
                    hideWatched = !hideWatched
                    prefs.put(HIDE_WATCHED, if (hideWatched) "1" else null)
                })
            }
        }
    }
}

/** [focused] once it has rested for [HERO_REST_MS], so holding a key never churns what depends on it. */
@Composable
private fun rememberRested(focused: State<Movie?>): Movie? {
    var rested by remember { mutableStateOf(focused.value) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest {
            delay(HERO_REST_MS)
            rested = it
        }
    }
    return rested
}

/**
 * A title's plot, backdrop, runtime, genres and cast: TMDB's for a title it knows (/api/app/title, with its
 * people and More like this), else the provider's (a film's /api/app/movie, a show's episodes call). All are
 * kept for the app session, so a title seen before answers on its first frame. A TMDB title answered without
 * scores is asked once more 3 s later, by when the box has usually fetched them.
 */
@Composable
private fun rememberInfo(movie: Movie?): MovieInfo? {
    val sync = LocalContext.current.graph.sync
    val peek = movie?.let(sync::peekInfo)
    return produceState(peek, movie?.titleId, movie?.id) {
        val m = movie ?: return@produceState
        value = sync.info(m).getOrNull() ?: value
        if (m.titleId.isTmdb && value?.known == false) {
            delay(3_000)
            value = sync.info(m).getOrNull() ?: value
        }
    }.value
}

/** A title's backdrop: its page's, else the library's (known before the page answers). */
private fun backdropOf(movie: Movie?, info: MovieInfo?): String? =
    info?.backdrop?.ifEmpty { null } ?: movie?.lib?.b?.ifEmpty { null }?.let { tmdbImage(it, "w1280") }

/**
 * The focused title's backdrop over the whole screen, darkened from the left and the bottom where text
 * sits; with [solidFrom], it fades to the plain ground there, so rows below sit on ink (and their edge
 * fades, drawn in ink, match it). A new backdrop is loaded before it cross-fades in,
 * so the screen never flashes to ink between titles; a title with none fades to the plain ground.
 * The scrims are baked into the still ([bakedBackdrop]), so a frame draws one or two pictures and no
 * offscreen layer: compositing the layered crossfade cost the stick's GPU ~50 ms a frame.
 */
@Composable
private fun Backdrop(url: String?, solidFrom: Dp?, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val topShare = TOP_SCRIM.value / LocalConfiguration.current.screenHeightDp
    // [shown] fades in over [under] (drawn whole), or, when null, [under] fades out to ink.
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    val lastSolid = remember { arrayOf(0.dp) }
    val drawn = remember { booleanArrayOf(false) }
    LaunchedEffect(url) {
        val next = if (url.isNullOrEmpty()) null else bakedBackdrop(context, url, topShare) ?: return@LaunchedEffect
        if (next == shown) return@LaunchedEffect
        // The first picture is there at once: fading it in made a title page's opening frames drop.
        if (next != null && !drawn[0]) {
            drawn[0] = true
            shown = next
            return@LaunchedEffect
        }
        under = shown
        shown = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(BACKDROP_FADE_MS))
        under = null
    }
    // Up next to the rows: the ground rises from the bottom to [solidFrom].
    val cover = animateFloatAsState(if (solidFrom != null) 1f else 0f, Motion.panel(), label = "cover")
    val solid = solidFrom ?: lastSolid[0]
    if (solidFrom != null) lastSolid[0] = solidFrom
    Box(
        modifier.fillMaxSize().drawWithCache {
            val ink = Palette.ink
            val band = SOLID_BAND.toPx()
            val whole = IntSize(size.width.toInt(), size.height.toInt())
            onDrawBehind {
                val p = fade.value
                val top = shown
                val base = under
                if (top == null || (base == null && p < 1f)) drawRect(ink)
                if (top == null) {
                    base?.let { drawImage(it, dstSize = whole, alpha = 1f - p) }
                } else {
                    base?.let { drawImage(it, dstSize = whole) }
                    drawImage(top, dstSize = whole, alpha = p)
                }
                val c = cover.value.coerceIn(0f, 1f)
                // The rows page sets a wider hero over the picture: it sits back further.
                if (c > 0f) {
                    drawRect(ink, alpha = HERO_DIM * c)
                    val y = size.height - (size.height - solid.toPx()) * c
                    drawRect(Brush.verticalGradient(listOf(ink.copy(alpha = 0f), ink), startY = y - band, endY = y), topLeft = Offset(0f, y - band), size = Size(size.width, band))
                    drawRect(ink, topLeft = Offset(0f, y), size = Size(size.width, size.height - y))
                }
            }
        },
    )
}

/** The last few baked backdrops, so a title page opened from its hero, or Back to the hero, starts at once. */
private val baked = LruCache<String, ImageBitmap>(3)

/**
 * [url]'s still at 720p with the scrims drawn in, on the CPU off the main thread, and handed to the GPU
 * early: ink across the text column, dense enough to the left (~60% of the width) that plot text clears
 * 4.5:1 over a white sky, then off by 88%; a bottom scrim; a top band ([topShare] of the height) so Search
 * reads. Null when it doesn't load.
 */
private suspend fun bakedBackdrop(context: PlatformContext, url: String, topShare: Float): ImageBitmap? {
    baked.get(url)?.let { return it }
    val request = ImageRequest.Builder(context).data(tmdbAt(url, "w1280")).size(BACKDROP_PX.first, BACKDROP_PX.second).allowHardware(false).build()
    val src = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    val out = withContext(Dispatchers.Default) {
        val (w, h) = BACKDROP_PX
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // Centre-cropped to the screen's 16:9.
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = w / scale
        val ch = h / scale
        val from = Rect(((src.width - cw) / 2).toInt(), ((src.height - ch) / 2).toInt(), ((src.width + cw) / 2).toInt(), ((src.height + ch) / 2).toInt())
        canvas.drawColor(Palette.ink.toArgb())
        canvas.drawBitmap(src, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        fun ink(alpha: Float) = Palette.ink.copy(alpha = alpha).toArgb()
        val paint = Paint()
        fun scrim(x1: Float, y1: Float, colors: IntArray, stops: FloatArray, bottom: Float = h.toFloat()) {
            paint.shader = LinearGradient(0f, 0f, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), bottom, paint)
        }
        scrim(w.toFloat(), 0f, intArrayOf(ink(0.94f), ink(0.86f), ink(0f)), floatArrayOf(0f, 0.6f, 0.88f))
        scrim(0f, h.toFloat(), intArrayOf(ink(0f), ink(0.96f)), floatArrayOf(0.4f, 1f))
        scrim(0f, h * topShare, intArrayOf(ink(0.7f), ink(0f)), floatArrayOf(0f, 1f), bottom = h * topShare)
        bitmap.setHasAlpha(false)
        bitmap.prepareToDraw()
        bitmap.asImageBitmap()
    }
    baked.put(url, out)
    return out
}

/**
 * Up next: the title you were watching (or the card focused in the strip) told large over its
 * backdrop, Resume focused, then the rest of what is part-way through as wide cards. OK on a card
 * opens its title page; Menu takes it out; Down goes on to the library's rows.
 */
@Composable
private fun UpNextPage(
    entries: List<Pair<Movie, Resume>>,
    newShows: Set<TitleId>,
    resumeButton: FocusRequester,
    onFocused: (Movie) -> Unit,
    onResume: (Movie) -> Unit,
    onStartOver: (Movie) -> Unit,
    onOpen: (Movie) -> Unit,
    onRemove: (Movie) -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The hero's title: the card focused last in the strip, else the newest.
    var currentId by remember { mutableStateOf<String?>(null) }
    val (movie, saved) = entries.firstOrNull { it.first.key == currentId } ?: entries.first()
    val shows = movie.titleId.isShow
    val films = LocalContext.current.graph.films
    LaunchedEffect(movie.key) {
        onFocused(movie)
        // Resume here skips the title page, so the copies are prepared once focus rests on a title (KTD13).
        delay(WARM_REST_MS)
        (if (shows) saved.episode else saved.title)?.ifEmpty { null }?.let(films::warm)
    }
    val info = rememberInfo(movie)
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize().padding(bottom = Space.safeY), verticalArrangement = Arrangement.Bottom) {
        // A fixed set of lines, so the buttons never move as the hero changes title.
        AnimatedContent(
            targetState = Triple(movie, saved, info),
            contentKey = { it.first.key to (it.third != null) },
            transitionSpec = { fadeIn(Motion.spring(0.3f)) togetherWith fadeOut(Motion.spring(0.2f)) },
            label = "upnext",
        ) { (m, r, i) ->
            Column(Modifier.padding(start = EDGE).widthIn(max = UPNEXT_WIDTH), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FitText(m.title.title, style = BIG_TITLE, active = true)
                MetaLine(m, i)
                Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(r.label, r.takeIf { it.dur > 0 && it.pos > 0 }?.let { leftLabel(it.dur - it.pos) }).joinToString(" · "), style = Type.headline, maxLines = 1)
                    if (r.dur > 0) ProgressLine({ r.pos.toFloat() / r.dur }, Modifier.width(PROGRESS_WIDTH))
                    if (r.at == 0L && m.titleId in newShows) Text(NEW_EPISODE, style = NEW_STYLE, maxLines = 1)
                }
                Text(i?.plot.orEmpty(), style = Type.body, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
            }
        }
        val alone = entries.size == 1
        Row(
            Modifier
                .padding(start = EDGE, top = Space.l)
                // With one title there is no strip below: Down goes on to the rows, and Menu takes it out, from here.
                .onKeyEvent {
                    if (!alone || it.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (it.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> onDown()
                        AndroidKeyEvent.KEYCODE_MENU -> onRemove(movie)
                        else -> return@onKeyEvent false
                    }
                    true
                },
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // A next episode (no place saved) plays from its start.
            PageButton(if (shows) "${if (saved.at == 0L) "Play" else "Resume"} ${saved.label.orEmpty()}".trim() else "Resume", { onResume(movie) }, Modifier.focusRequester(resumeButton))
            if (shows) PageButton("Episodes", { onOpen(movie) }) else {
                PageButton("Start over", { onStartOver(movie) })
                PageButton("Details", { onOpen(movie) })
            }
        }
        if (alone) {
            Text("Menu removes it · Down for all ${LocalLibrary.current.noun}", style = Type.caption, maxLines = 1, modifier = Modifier.padding(start = EDGE, top = Space.l))
            return@Column
        }
        Row(Modifier.padding(start = EDGE, top = Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text("Continue watching", style = Type.headline, maxLines = 1)
            Text("Menu removes a title · Down for all ${LocalLibrary.current.noun}", style = Type.caption, maxLines = 1)
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    val down = it.type == KeyEventType.KeyDown
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (down) onDown()
                            true
                        }
                        // The focused title leaves Up next; focus steps to a neighbour first (or up to Resume).
                        AndroidKeyEvent.KEYCODE_MENU -> {
                            if (down && it.nativeKeyEvent.repeatCount == 0) {
                                val i = entries.indexOfFirst { e -> e.first.key == movie.key }
                                val side = if (i < entries.lastIndex) FocusDirection.Right else FocusDirection.Left
                                if (entries.size == 1 || !focusManager.moveFocus(side)) runCatching { resumeButton.requestFocus() }
                                onRemove(movie)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(start = EDGE, top = Space.m, bottom = Space.s, end = EDGE),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            items(entries, key = { it.first.key }) { (m, r) ->
                WideCard(m, r, new = r.at == 0L && m.titleId in newShows, onFocused = { currentId = m.key }, onClick = { onOpen(m) })
            }
        }
    }
}

/**
 * The picture in a word, read by colour first: Reference (green), Great (accent), OK (plain), Soft (amber).
 * From resolution and codec-adjusted bits per pixel (server, api/_quality-labels.ts).
 */
@Composable
internal fun TierChip(tier: String) {
    val color = tierColor(tier)
    Text(
        tier,
        style = Type.caption.copy(color = color, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(Radius.chip)).padding(horizontal = Space.s, vertical = 1.dp),
    )
}


private fun tierColor(tier: String) = when (tier) {
    "Reference" -> Palette.good
    "Great" -> Palette.textPrimary
    "Soft" -> Palette.warning
    else -> Palette.textSecondary
}

private val SCORE_STYLE = Type.body.copy(color = Palette.textPrimary, fontWeight = FontWeight.SemiBold)

/** A followed show's episode that aired after it was last watched (R31): state, so in the accent, like "Watched". */
private const val NEW_EPISODE = "New episode"
private val NEW_STYLE = Type.caption.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold)

/**
 * Tier (unless [tier] is off: a film's page names the copy's on its Plays line), IMDb's and Letterboxd's averages and
 * the owner's rating ([scoresLine]), then year, running time and genres (the provider's rating when
 * no average is known); the group when nothing names genres.
 */
@Composable
private fun MetaLine(movie: Movie, info: MovieInfo?, extra: String? = null, tier: Boolean = true) {
    val genre = info?.genre?.split(',', '/')?.map(String::trim)?.filter(String::isNotEmpty)?.take(2)?.joinToString(", ")?.ifEmpty { null }
    val runtime = (info?.secs?.takeIf { it > 0 }?.let { it * 1000L } ?: movie.lib?.rt?.takeIf { it > 0 }?.let { it * 60_000L })?.let(::runtimeLabel)
    val scored: Scored? = movie.lib?.takeIf { it.known } ?: info
    val rating = info?.rating?.ifEmpty { null }?.takeIf { scored?.lb == null && scored?.ir == null }?.let { "$it/10" }
    val yours = LocalContext.current.graph.marks.ratings.collectAsState().value[movie.titleId]
    val scores = remember(scored, yours) { scoresLine(scored, yours) }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        if (tier) (info?.tier?.ifEmpty { null } ?: movie.lib?.t?.ifEmpty { null })?.let { TierChip(it) }
        if (scores != null) Text(scores, style = SCORE_STYLE, maxLines = 1)
        movie.title.badge?.let { Badges(remember(it) { listOf(it) }) }
        val group = movie.group.takeIf { movie.lib != null } ?: groupLabel(movie.group).name
        FitText(listOfNotNull(movie.title.year, extra, runtime, genre ?: group.ifEmpty { null }, rating).joinToString(" · "), style = Type.body, active = true)
    }
}

/**
 * "IMDb 8.8 (2.9M) · Letterboxd 8.4 (4.6M) · You 9/10": IMDb's and Letterboxd's averages out of 10 (Letterboxd's doubled)
 * with their vote counts, and the owner's rating; each number in its colour on the review-score ramp, the rest
 * text-secondary; null when none is known.
 */
private fun scoresLine(scored: Scored?, yours: Int?): AnnotatedString? {
    val parts = listOfNotNull(
        scored?.ir?.let { Triple("IMDb ", it, scored.iv) },
        scored?.lb?.let { Triple("Letterboxd ", it * 2, scored.lv) },
        yours?.let { Triple("You ", it.toDouble(), null) },
    )
    if (parts.isEmpty()) return null
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.textSecondary)) {
            parts.forEachIndexed { i, (label, value, votes) ->
                if (i > 0) append(" · ")
                append(label)
                withStyle(SpanStyle(color = Palette.outOf10(value))) { append(if (label == "You ") "${value.toInt()}/10" else "%.1f".format(Locale.ROOT, value)) }
                if (votes != null) append(" (${compactCount(votes)})")
            }
        }
    }
}

/** 2.9M, 41K, 830. */
private fun compactCount(n: Int) = when {
    n >= 999_500 -> "%.1fM".format(Locale.ROOT, n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "${(n + 500) / 1000}K"
    else -> "$n"
}

private fun yoursLabel(rating: Int) = "You $rating/10"

/** A card's corner: tier in its colour, then the combined score out of 10 in its own ([scored]'s when a title page learnt it); null when neither is known. */
private fun cardChip(t: LibTitle, scored: Scored): AnnotatedString? {
    val score = scored.s10
    if (t.t.isEmpty() && score == null) return null
    return buildAnnotatedString {
        if (t.t.isNotEmpty()) withStyle(SpanStyle(color = tierColor(t.t), fontWeight = FontWeight.SemiBold)) { append(t.t) }
        if (t.t.isNotEmpty() && score != null) append("  ")
        if (score != null) withStyle(SpanStyle(color = Palette.outOf10(score))) { append("%.1f".format(Locale.ROOT, score)) }
    }
}

/** Dark enough under the chip's 13 sp text to clear 4.5:1 over any poster. */
private val CHIP_GROUND = Color(0xE00B0E12)
private val CHIP_SHAPE = RoundedCornerShape(Radius.chip)
private val CHIP_TEXT = Type.caption.copy(color = Palette.textPrimary)

/**
 * A title page's or Up next's button: a quiet translucent pill that turns solid light when focused, so
 * the one OK will press reads from across the room.
 */
@Composable
/** "+ Watchlist" / "✓ Watchlist" on a title page: OK puts the film or show on the Trakt watchlist or takes it off (not one only Letterboxd's list holds). */
private fun WatchlistButton(title: TitleId) {
    val watchlist = LocalContext.current.graph.watchlist
    if (!watchlist.canHold(title)) return
    val ids by watchlist.ids.collectAsState()
    val letterboxd by watchlist.letterboxd.collectAsState()
    var told by remember(title) { mutableStateOf(false) }
    PageButton(watchlistLabel(title in ids, title in letterboxd, told), { told = !watchlist.toggle(title) })
}

private fun watchlistLabel(on: Boolean, letterboxd: Boolean, told: Boolean) = when {
    told -> LETTERBOXD_ONLY
    on && letterboxd -> "✓ Watchlist (Letterboxd)"
    on -> "✓ Watchlist"
    else -> "+ Watchlist"
}

/** A toggle on a title only Letterboxd's watchlist holds: there is no API to take it off. */
private const val LETTERBOXD_ONLY = "Only Letterboxd can remove it"

@Composable
private fun PageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.focus(), label = "press")
    Text(
        label,
        style = Type.headline.copy(color = if (focused) Palette.ink else Palette.textPrimary),
        maxLines = 1,
        modifier = modifier
            .graphicsLayer {
                val s = (if (reduced) 1f else 1f + 0.06f * lift.value) * press
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val p = lift.value.coerceIn(0f, 1f)
                val r = CornerRadius(Radius.row.toPx())
                drawRoundRect(lerp(Palette.material, Palette.textPrimary, p), cornerRadius = r)
                if (p < 1f) drawRoundRect(Palette.edge, cornerRadius = r, style = Stroke(1.dp.toPx()), alpha = 1f - p)
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.m),
    )
}

/**
 * A title part-way through as a 16:9 card: its backdrop (else its poster), name and where it is
 * ("S1 E3"), how far along. Focus lifts it with the accent ring, like a poster.
 */
@Composable
private fun WideCard(movie: Movie, saved: Resume, new: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val info = rememberInfo(movie)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    val image = backdropOf(movie, info)?.let { tmdbAt(it, "w780") } ?: movie.poster.ifEmpty { null }?.let(::posterUrl)
    val request = remember(image) {
        image?.let { with(density) { ImageRequest.Builder(context).data(it).size(WIDE.width.roundToPx(), WIDE.height.roundToPx()).crossfade(POSTER_FADE_MS).build() } }
    }
    Box(
        Modifier
            .size(WIDE)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + (CARD_SCALE - 1f) * lift.value
                scaleX = s
                scaleY = s
                shape = CARD_SHAPE
                clip = true
            }
            .drawWithContent {
                drawRect(Palette.raised)
                drawContent()
                val p = lift.value.coerceIn(0f, 1f)
                if (p > 0f) {
                    val ring = RING.toPx()
                    drawRoundRect(
                        Palette.accent, topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
                        cornerRadius = CornerRadius(Radius.row.toPx() - ring / 2), style = Stroke(ring), alpha = p,
                    )
                }
            },
    ) {
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // The name and progress read over any picture.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xE6000000))) })
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Space.s, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (new) Text(NEW_EPISODE, style = NEW_STYLE, maxLines = 1)
            FitText(listOfNotNull(movie.title.title, saved.label).joinToString(" · "), style = Type.caption.copy(color = Palette.textPrimary), active = focused)
            if (saved.dur > 0) ProgressLine({ saved.pos.toFloat() / saved.dur })
        }
    }
}

/**
 * The focused title on the rows and search pages: name, meta, how much is left of a started one,
 * and two lines of plot. It cross-fades once focus rests, in a fixed box, so the rows below never move.
 */
@Composable
private fun Hero(movie: Movie?, info: MovieInfo?, resume: List<Resume>, placeholder: String, modifier: Modifier) {
    AnimatedContent(
        targetState = movie to info,
        contentKey = { it.first?.key to (it.second != null) },
        transitionSpec = { fadeIn(Motion.spring(0.3f)) togetherWith fadeOut(Motion.spring(0.2f)) using SizeTransform(clip = false) },
        modifier = modifier,
        label = "hero",
    ) { (m, i) ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (m == null) {
                Text(placeholder, style = Type.display, maxLines = 1)
                return@Column
            }
            FitText(m.title.title, style = Type.display, active = true)
            // A show also says which episode it is on: "S1 E3 · 40 min left".
            val left = remember(m, resume) {
                resume.firstOrNull { it.title == m.titleId }?.let { r ->
                    listOfNotNull(r.label, r.takeIf { it.dur > 0 && it.pos > 0 }?.let { leftLabel(it.dur - it.pos) }).joinToString(" · ").ifEmpty { null }
                }
            }
            MetaLine(m, i, extra = left)
            i?.plot?.ifBlank { null }?.let { Text(it, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = PLOT_WIDTH)) }
        }
    }
}

/** "1 h 12 min left", "42 min left". */
private fun leftLabel(ms: Long): String = "${runtimeLabel(ms)} left"

/** "2 h 10 min", "42 min". */
private fun runtimeLabel(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** A row's scroll: the focused card keeps a card's width from either edge, so the next one always shows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberKeepClear(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val margin = with(density) { (CARD.width + Space.l).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val before = offset - margin
                val after = offset + size + margin - containerSize
                return when {
                    before < 0 -> before
                    after > 0 -> after
                    else -> 0f
                }
            }
        }
    }
}

/**
 * Rows of posters. The focused row rises to the top of the list; within a row the focused card keeps a
 * card's width from either edge. Each row comes back to the card last focused in it, and the screen to
 * the card focused last ([focus] restores it). Up and Down move a row at a time on purpose, scrolling the
 * next row in first, so a row above the screen is never skipped for a button; Up from the first row is [onTop].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Rows(rows: List<MovieRow>, watched: Set<TitleId>, focus: FocusRequester, active: Boolean, onFocused: (Movie) -> Unit, onOpen: (Movie) -> Unit, onRemove: (Movie) -> Unit, onTop: () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // The card each row was left on, for a row built again after scrolling far away.
    val columns = remember { HashMap<String, Int>() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var focusedRow by remember { mutableStateOf(-1) }
    val pivot = remember(density) {
        val top = with(density) { (CARD_TOP + FADE).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - top
        }
    }
    val keepClear = rememberKeepClear()
    // The card a title page was opened from (row key, movie id). Back lands on it explicitly.
    val list = rememberLazyListState()
    val back = remember { FocusRequester() }
    var opened by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val lastFocused = remember { arrayOfNulls<Pair<String, Int>>(1) }
    LaunchedEffect(active) {
        val target = opened?.takeIf { active } ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == target.first }
        if (index >= 0) {
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (lastFocused[0] == target) return@repeat
                runCatching { back.requestFocus() }
            }
        }
        opened = null
    }
    fun goRow(i: Int) {
        scope.launch {
            val requester = rowFocus[rows[i].key] ?: return@launch
            repeat(FOCUS_FRAMES) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == i }) list.scrollToItem(i)
                withFrameNanos {}
                if (focusedRow == i) return@launch
                runCatching { requester.requestFocus() }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(
            Modifier.fillMaxSize().edgeFade(Palette.ink, top = FADE).focusRequester(focus).focusRestorer(first).focusGroup(),
            state = list,
            contentPadding = PaddingValues(top = FADE, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "row" }) { i, row ->
                CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                    PosterRow(
                        row,
                        watched = watched,
                        rowFocus = rowFocus.getOrPut(row.key) { FocusRequester() },
                        entry = if (i == 0) first else null,
                        startAt = columns[row.key] ?: 0,
                        back = opened?.takeIf { it.first == row.key }?.let { it.second to back },
                        onFocused = { col, movie ->
                            columns[row.key] = col
                            focusedRow = i
                            lastFocused[0] = row.key to movie.id
                            onFocused(movie)
                        },
                        onOpen = { movie ->
                            opened = row.key to movie.id
                            onOpen(movie)
                        },
                        // Continue watching: Menu takes a title out, as on Up next.
                        // Focus stays in the row (the next card), else the rows' first when it was the last.
                        onMenu = if (row.key != CONTINUE_ROW) null else { movie ->
                            onRemove(movie)
                            scope.launch {
                                withFrameNanos {}
                                if (row.movies.size > 1) runCatching { rowFocus[row.key]?.requestFocus() } else runCatching { focus.requestFocus() }
                            }
                        },
                        onVertical = { down ->
                            when {
                                !down && i == 0 -> onTop()
                                down && i == rows.lastIndex -> {}
                                else -> goRow(if (down) i + 1 else i - 1)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    row: MovieRow, watched: Set<TitleId>, rowFocus: FocusRequester, entry: FocusRequester?, startAt: Int, back: Pair<Int, FocusRequester>?,
    onFocused: (Int, Movie) -> Unit, onOpen: (Movie) -> Unit, onVertical: (down: Boolean) -> Unit, onMenu: ((Movie) -> Unit)? = null,
) {
    val own = remember { FocusRequester() }
    val restore = entry ?: own
    val at = startAt.coerceIn(0, (row.movies.size - 1).coerceAtLeast(0))
    // Saveable: a row built again after scrolling far away is back where it was.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 1).coerceAtLeast(0))
    // A row's end is felt, not crossed: Left on the first card or Right on the last nudges the row and springs it back.
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) }
    val focusedCol = remember { intArrayOf(at) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.padding(start = EDGE), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = Type.headline, maxLines = 1)
            row.badge?.let { Badges(remember(it) { listOf(it) }) }
        }
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = nudge.value * Space.s.toPx() }
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    // Up and Down belong to the rows (one row a step), never to focus search.
                    if (code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                        if (it.type == KeyEventType.KeyDown) onVertical(code == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
                        return@onKeyEvent true
                    }
                    val left = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                    val edge = (left && focusedCol[0] == 0) || (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && focusedCol[0] == row.movies.lastIndex)
                    if (edge && !reduced && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) scope.launch {
                        nudge.animateTo(if (left) -1f else 1f, Motion.spring(0.1f))
                        nudge.animateTo(0f, Motion.spring(0.3f))
                    }
                    edge
                }
                .focusRequester(rowFocus)
                // Asking for the opened card enters through here: falling back to the entry card would win.
                .focusRestorer(back?.second ?: restore)
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            itemsIndexed(row.movies, key = { _, m -> m.key }, contentType = { _, _ -> "card" }) { col, movie ->
                PosterCard(
                    movie, row.progress[movie.titleId], watched = movie.titleId in watched,
                    focus = if (col == at) restore else null,
                    back = back?.takeIf { it.first == movie.id }?.second,
                    onFocused = {
                        focusedCol[0] = col
                        onFocused(col, movie)
                    },
                    onClick = { onOpen(movie) },
                    onMenu = onMenu?.let { remove -> { remove(movie) } },
                )
            }
        }
    }
}

/**
 * A 2:3 poster at a fixed size, so nothing moves while it loads; the title's monogram when there is
 * none. Focus lifts it toward the viewer with an accent ring, on one spring read only while drawing.
 */
@Composable
private fun PosterCard(movie: Movie, progress: Float?, watched: Boolean = false, focus: FocusRequester?, back: FocusRequester? = null, onFocused: () -> Unit, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    val watchlist = LocalContext.current.graph.watchlist
    val reduced = LocalReducedMotion.current
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    var told by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    var failed by remember(movie.poster) { mutableStateOf(false) }
    val request = remember(movie.poster) {
        movie.poster.takeIf { it.isNotEmpty() }?.let {
            with(density) { ImageRequest.Builder(context).data(posterUrl(it)).size(CARD.width.roundToPx(), CARD.height.roundToPx()).crossfade(POSTER_FADE_MS).build() }
        }
    }
    Box(
        Modifier
            .size(CARD)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .then(if (back != null) Modifier.focusRequester(back) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() else told = false
            }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu || (onMenu == null && !watchlist.canHold(movie.titleId))) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) if (onMenu != null) onMenu() else told = !watchlist.toggle(movie.titleId)
                true
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + (CARD_SCALE - 1f) * lift.value
                scaleX = s
                scaleY = s
                shape = CARD_SHAPE
                clip = true
            }
            .drawWithContent {
                drawRect(Palette.raised)
                drawContent()
                val p = lift.value.coerceIn(0f, 1f)
                if (p > 0f) {
                    val ring = RING.toPx()
                    drawRoundRect(
                        Palette.accent, topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
                        cornerRadius = CornerRadius(Radius.row.toPx() - ring / 2), style = Stroke(ring), alpha = p,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (request == null || failed) {
            Text(remember(movie.name) { monogram(movie.title.title) }, style = Type.title.copy(color = Palette.textSecondary), maxLines = 1)
        }
        if (request != null) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
        if (progress != null) {
            ProgressLine({ progress }, Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.s, vertical = Space.s))
        }
        if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(Space.s))
        if (focused && watchlist.canHold(movie.titleId)) {
            val onList = movie.titleId in watchlist.ids.collectAsState().value
            val letterboxd = onList && movie.titleId in watchlist.letterboxd.collectAsState().value
            Text(
                if (told || letterboxd) watchlistLabel(onList, letterboxd, told) else if (onList) "✓ Watchlist · ☰" else "☰ Watchlist", style = CHIP_TEXT, maxLines = 2, // the Letterboxd notes take two lines on a card
                modifier = Modifier.align(Alignment.BottomStart).padding(Space.xs).background(CHIP_GROUND, CHIP_SHAPE).padding(horizontal = Space.xs + 2.dp, vertical = 1.dp),
            )
        }
        val learnt = movie.lib?.takeIf { !it.known }?.let { LocalContext.current.graph.sync.scores[movie.titleId] }
        val chip = remember(movie.lib, learnt) { movie.lib?.let { cardChip(it, learnt ?: it) } }
        if (chip != null) Text(
            chip, style = CHIP_TEXT, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(Space.xs).background(CHIP_GROUND, CHIP_SHAPE).padding(horizontal = Space.xs + 2.dp, vertical = 1.dp),
        )
    }
}

private fun monogram(title: String): String =
    title.split(' ').mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("") { it.uppercase() }

/**
 * Search: the app's keyboard over a grid of matching posters: titles from TMDB and the provider's whole
 * catalogue, or a genre's or language's ("comedy", "arabic"). Back leaves it. [focus] restores the result
 * last focused, else the keyboard ([keys]).
 */
@Composable
private fun SearchGrid(
    library: Catalog,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    keys: FocusRequester,
    onFocused: (Movie?) -> Unit,
    back: Pair<Int, FocusRequester>?,
    stray: () -> Boolean,
    arrive: () -> Boolean,
    onPlay: (Movie) -> Unit,
) {
    var found by remember { mutableStateOf<List<Movie>?>(null) }
    val graph = LocalContext.current.graph
    val sync = graph.sync
    val recent by graph.recentSearches.all.collectAsState()
    // Runs once typing pauses (the box keeps each answer, and so does the app); the last results stay
    // meanwhile. Offline, the library's own titles.
    LaunchedEffect(query, library) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        found = sync.search(query).getOrNull() ?: withContext(Dispatchers.Default) { library.search(query) }
    }
    val first = remember { FocusRequester() }
    // A spoken query: once its results are in, the first card takes focus. None leaves focus on the keys.
    var spoken by remember { mutableStateOf(false) }
    LaunchedEffect(found) {
        if (!spoken) return@LaunchedEffect
        spoken = false
        if (found.isNullOrEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(CARD.width),
        modifier = Modifier.fillMaxSize().focusRequester(focus).focusRestorer(keys).focusGroup(),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, top = Space.m, bottom = Space.xxxl),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item(key = "keys", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.widthIn(max = KEYBOARD_WIDTH)) {
                SearchBox(
                    query, busy = false, onQuery = onQuery, entry = keys,
                    onFocused = { onFocused(null) },
                    onDown = { runCatching { first.requestFocus() } },
                    onSpoken = { spoken = true },
                    stray = stray,
                    arrive = arrive,
                )
            }
        }
        val list = found
        when {
            query.isBlank() && recent.isNotEmpty() -> item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                // Past searches that led to a title: OK searches again.
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    recent.forEachIndexed { i, q -> ActionButton(q, { onQuery(q) }, if (i == 0) Modifier.focusRequester(first) else Modifier) }
                }
            }
            query.isBlank() -> item(key = "hint", span = { GridItemSpan(maxLineSpan) }) { HintLine("Type a title, or a genre or language such as comedy or Arabic") }
            list == null -> {}
            list.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) { HintLine("No ${LocalLibrary.current.noun} match “${query.trim()}”") }
            else -> itemsIndexed(list, key = { _, m -> m.key }) { i, movie ->
                PosterCard(movie, null, focus = if (i == 0) first else null, back = back?.takeIf { it.first == movie.id }?.second, onFocused = { onFocused(movie) }, onClick = { graph.recentSearches.add(query); onPlay(movie) })
            }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    Text(text, style = Type.body, modifier = Modifier.padding(start = Space.m))
}

/** First ever open only: later ones read the copy on disk. Waits 400ms, so a quick load never flashes it. */
@Composable
private fun LoadingMovies(focus: FocusRequester) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        shown = true
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.panel(), label = "loading")
    // Holds focus meanwhile, so Back still answers.
    Column(
        Modifier.padding(start = EDGE, top = Space.xl).focusRequester(focus).focusable().graphicsLayer { this.alpha = alpha },
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Text("Loading ${LocalLibrary.current.noun}…", style = Type.body)
        ActivityLine()
    }
}

@Composable
private fun Unavailable(focus: FocusRequester, onRetry: () -> Unit) {
    Column(Modifier.padding(start = EDGE, top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("${LocalLibrary.current.title} didn’t load", style = Type.title)
        Text("Check that the Fire TV is online, then try again.", style = Type.body)
        ActionButton("Try again", onRetry, Modifier.padding(top = Space.m).focusRequester(focus))
    }
}

/** Every source has answered and no copy plays here: the title stays, its page says so (R21). */
private val Prepared.unavailable get() = plays == null && rdChecked && ranking != null && answer?.rdPending == false

/**
 * Where focus goes back to on a title page after a page its cast or More like this opened: the item [opened]
 * ([back] is on it); [first] is the first More like this card (else the first person), an unavailable title's landing.
 */
private class ExtrasFocus {
    val first = FocusRequester()
    val back = FocusRequester()
    var opened by mutableStateOf<String?>(null)
}

/**
 * A film's title page: its backdrop over the whole screen and, low-left, everything about it (meta and
 * scores, how much is left, plot), the copy Play will use, then Resume or Play, Start over once it is
 * started, and Versions; below, its cast and director and More like this. Opening it prepares the film's
 * copies (KTD13). A film nothing plays says so instead, focus on More like this (R21). Back returns to
 * where it was opened from, or first closes Versions. [hidden] while the film, or a page over it, is up.
 */
@Composable
private fun FilmPage(
    film: Movie, openVersions: Boolean, resume: Resume?, watched: Set<TitleId>, active: Boolean, hidden: Boolean,
    onPlay: (fromStart: Boolean) -> Unit, onOpen: (Movie) -> Unit, onPerson: (Person) -> Unit, onBack: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val info = rememberInfo(film)
    val prepared by remember(film.titleId) { graph.films.state(film.titleId) }.collectAsState()
    val unavailable = prepared.unavailable
    val primary = remember { FocusRequester() }
    val versionsButton = remember { FocusRequester() }
    val noCopy = remember { FocusRequester() }
    val extras = remember { ExtrasFocus() }
    val hasExtras = info != null && (info.people.isNotEmpty() || info.more.isNotEmpty())
    var hasFocus by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(openVersions) }
    val closedVersions = remember { booleanArrayOf(false) }
    // ☰'s marks panel.
    var marking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        Check.log("title_shown", "title" to film.titleId)
        graph.prefs.put(Check.LAST_SCREEN, "title:${film.titleId}")
    }
    LaunchedEffect(active, versions || marking, unavailable, hasExtras) {
        if (!active || versions || marking) return@LaunchedEffect
        val target = when {
            closedVersions[0] -> versionsButton
            extras.opened != null -> extras.back
            unavailable -> if (hasExtras) extras.first else noCopy
            else -> primary
        }
        closedVersions[0] = false
        try {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (hasFocus) return@LaunchedEffect
                runCatching { target.requestFocus() }
            }
        } finally {
            extras.opened = null
        }
    }
    val started = resume != null && resume.pos > 0
    // The provider's own copy, named on the Plays line when the box can't answer.
    val iptv = film.id > 0 || graph.sync.movies.items.value?.findTitle(film.titleId) != null
    val onMenu = if (!versions && !marking && graph.marks.canMark(film.titleId)) ({ marking = true }) else null
    TitleFrame(backdropOf(film, info), hidden, onBack, onFocus = { hasFocus = it }, onMenu = onMenu) {
        TitleScroll(
            main = {
                Column(Modifier.padding(start = EDGE, bottom = Space.xl).widthIn(max = PAGE_WIDTH), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(film.title.title, style = BIG_TITLE, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // The tier is the copy's that plays, on the Plays line.
                    MetaLine(film, info, tier = false)
                    if (started && resume!!.dur > 0) Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Text(leftLabel(resume.dur - resume.pos), style = Type.headline, maxLines = 1)
                        ProgressLine({ resume.pos.toFloat() / resume.dur }, Modifier.width(PROGRESS_WIDTH))
                    }
                    info?.plot?.ifBlank { null }?.let { Text(it, style = Type.body, maxLines = if (unavailable) 4 else 6, overflow = TextOverflow.Ellipsis) }
                    // The provider's facts name people only as text; TMDB's are the row below.
                    if (info?.people.isNullOrEmpty()) {
                        info?.cast?.ifBlank { null }?.let { Text("Starring $it", style = Type.caption, maxLines = 2) }
                        info?.director?.substringBefore(",")?.trim()?.ifBlank { null }?.let { Text("Directed by $it", style = Type.caption, maxLines = 2) }
                    }
                    if (unavailable) {
                        Text("No copy available", style = Type.headline.copy(color = Palette.warning), modifier = Modifier.padding(top = Space.s).focusRequester(noCopy).focusable())
                        Text("Neither IPTV nor Real-Debrid has one that plays here. It stays in the library for when one turns up.", style = Type.caption)
                    } else {
                        // The Plays line may run past the column, across the page's whole width.
                        PlaysLine(
                            film.titleId, fallback = if (iptv) listOf("IPTV") + info?.quality.orEmpty() else null,
                            Modifier.padding(top = Space.s).wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = LocalConfiguration.current.screenWidthDp.dp - EDGE * 2),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            PageButton(if (started) "Resume" else "Play", { onPlay(false) }, Modifier.focusRequester(primary))
                            if (started) PageButton("Start over", { onPlay(true) })
                            PageButton("Versions", { versions = true }, Modifier.focusRequester(versionsButton))
                            WatchlistButton(film.titleId)
                        }
                    }
                }
            },
            extras = { TitleExtras(info, watched, extras, onPerson, onOpen) },
        )
        if (versions) VersionsPanel(
            film.titleId,
            onPlay = { onPlay(false) },
            onClose = {
                closedVersions[0] = true
                versions = false
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
        )
        if (marking) {
            val seen = film.titleId in watched
            MarksPanel(
                film.title.title,
                listOf((if (seen) "Mark unwatched" else "Mark watched") to { graph.marks.setWatched(film.titleId, !seen); marking = false }),
                graph.marks.ratings.collectAsState().value[film.titleId],
                onRate = { graph.marks.rate(film.titleId, it); marking = false },
                onClose = { marking = false },
                modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
            )
        }
    }
}

/**
 * "Plays: Real-Debrid · 4K · HDR10 · E-AC3 5.1 · 14.2 GB" (no video codec) with its tier: the copy Play will open, in a
 * one-line slot that is there from the start, so nothing moves when it fills in or changes (R29). Until
 * the ranking and Real-Debrid's check are in, it stays empty; after Play's own wait it names the best
 * IPTV copy, and the library's ([fallback] labels) when the box can't answer.
 */
@Composable
private fun PlaysLine(title: TitleId, fallback: List<String>?, modifier: Modifier = Modifier) {
    val films = LocalContext.current.graph.films
    val p by remember(title) { films.state(title) }.collectAsState()
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(title) {
        delay(FilmSession.WAIT_MS)
        late = true
    }
    val copy = p.plays ?: p.ranking?.takeIf { late }?.copies?.firstOrNull { it.copy.source == Capacity.Source.Iptv && (it.support != Support.Wont || it.pinned) }?.copy
    val text = when {
        copy != null -> copyLine(copy, p.answer)
        p.failed || (late && p.ranking == null) -> fallback?.joinToString(" · ")
        p.rdChecked && p.ranking != null -> "no copy plays here right now"
        else -> null
    }
    LaunchedEffect(text) { if (text != null) Check.log("plays", "title" to title, "copy" to copy?.id, "src" to (copy?.source ?: "library")) }
    Row(modifier.height(PLAYS_HEIGHT), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        if (text == null) return@Row
        copy?.let(::copyTier)?.let { TierChip(it) }
        FitText("Plays: $text", style = Type.body, active = true)
    }
}

private val PLAYS_HEIGHT = 24.dp

/** A title page's ground: the backdrop, Back to leave, focus kept inside; undrawn while [hidden]. */
@Composable
private fun TitleFrame(backdrop: String?, hidden: Boolean, onBack: () -> Unit, onFocus: (Boolean) -> Unit, onMenu: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onFocusChanged { onFocus(it.hasFocus) }
            .onKeyEvent {
                val key = remoteKey(it.nativeKeyEvent.keyCode)
                // ☰ that nothing on the page took (an episode row, a card) opens the title's marks.
                if (key == RemoteKey.Menu && onMenu != null) {
                    if (it.type == KeyEventType.KeyDown) onMenu()
                    return@onKeyEvent true
                }
                if (key != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onBack()
                true // the key-up too, so Back never reaches the screen behind
            }
            .focusGroup(),
    ) {
        Backdrop(backdrop, solidFrom = null)
        content()
    }
}

/** How much of the cast row's heading shows under a title page's first screen, so Down has somewhere to go. */
private val PEEK = 40.dp

@OptIn(ExperimentalFoundationApi::class)
private val NO_SCROLL = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * A title page's two screens: [main] fills the first, bottom-aligned; [extras] (cast, More like this) wait
 * below it. Focus going down to them scrolls them up whole, and back up to [main] scrolls back; nothing
 * else scrolls the page, so a row moving its cards never drags it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleScroll(main: @Composable BoxScope.() -> Unit, extras: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val inherited = LocalBringIntoViewSpec.current
    val keepClear = rememberKeepClear()
    // The extras come a frame after the page, so its opening frame lays out only what shows.
    var below by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        below = true
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        CompositionLocalProvider(LocalBringIntoViewSpec provides NO_SCROLL) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                    Box(
                        Modifier.fillMaxWidth().height(screen - PEEK).onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } },
                        contentAlignment = Alignment.BottomStart,
                        content = main,
                    )
                }
                Box(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) {
                    if (below) CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear, content = extras)
                }
            }
        }
    }
}

/**
 * A title's cast and director (OK opens their titles) and More like this, from its TMDB details (R32);
 * nothing when TMDB has neither. [focus] marks the item opened, so Back lands on it.
 */
@Composable
private fun TitleExtras(info: MovieInfo?, watched: Set<TitleId>, focus: ExtrasFocus, onPerson: (Person) -> Unit, onOpen: (Movie) -> Unit) {
    val people = info?.people.orEmpty()
    val more = info?.moreMovies.orEmpty()
    if (people.isEmpty() && more.isEmpty()) return
    val focusManager = LocalFocusManager.current
    // More like this a frame after the cast: laying out both in one frame took ~30 ms on the stick.
    var posters by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        posters = true
    }
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (people.isNotEmpty()) {
            Text("Cast and crew", style = Type.headline, maxLines = 1, modifier = Modifier.padding(start = EDGE))
            LazyRow(
                Modifier.fillMaxWidth().focusRestorer().focusGroup(),
                contentPadding = PaddingValues(horizontal = EDGE, vertical = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                itemsIndexed(people, key = { _, p -> p.id }) { i, p ->
                    val first = i == 0 && more.isEmpty()
                    val back = focus.opened == "p:${p.id}"
                    PageButton(
                        if (p.role == "Director" || p.role == "Creator") "${p.name} · ${p.role.lowercase()}" else p.name,
                        {
                            focus.opened = "p:${p.id}"
                            onPerson(p)
                        },
                        Modifier.then(if (first) Modifier.focusRequester(focus.first) else Modifier).then(if (back) Modifier.focusRequester(focus.back) else Modifier),
                    )
                }
            }
        }
        if (more.isNotEmpty() && posters) {
            val opened = focus.opened?.removePrefix("m:")
            PosterRow(
                remember(more) { MovieRow("more", "More like this", null, more, emptyMap()) },
                watched = watched,
                rowFocus = remember { FocusRequester() },
                entry = focus.first,
                startAt = 0,
                back = more.firstOrNull { it.key == opened }?.let { it.id to focus.back },
                onFocused = { _, _ -> },
                onOpen = {
                    focus.opened = "m:${it.key}"
                    onOpen(it)
                },
                onVertical = { down -> if (!down) focusManager.moveFocus(FocusDirection.Up) },
            )
        }
    }
}

/**
 * A person's films and shows as a poster grid under their name, best score first (R32). Focus starts on the
 * first, and comes back to the one opened. Back returns to the title page.
 */
@Composable
private fun PersonPage(person: Person, watched: Set<TitleId>, active: Boolean, hidden: Boolean, onOpen: (Movie) -> Unit, onBack: () -> Unit) {
    val sync = LocalContext.current.graph.sync
    var titles by remember { mutableStateOf(sync.peekPerson(person.id)) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        sync.person(person.id).onSuccess { titles = it }.onFailure { failed = titles == null }
    }
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val status = remember { FocusRequester() }
    var opened by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    val list = titles
    // The cards come a frame after the page, as a title page's extras do, so its opening frame stays light.
    var cards by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        cards = true
    }
    LaunchedEffect(active, list != null, failed) {
        if (!active) return@LaunchedEffect
        val target = when {
            list.isNullOrEmpty() -> status
            opened != null -> back
            else -> first
        }
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { target.requestFocus() }
        }
    }
    TitleFrame(null, hidden, onBack, onFocus = { hasFocus = it }) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CARD.width),
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, top = Space.safeY, bottom = Space.xxxl),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            item(key = "name", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(person.name, style = BIG_TITLE, maxLines = 2)
                    Text(
                        when {
                            !list.isNullOrEmpty() -> "${list.size} films and shows, best rated first"
                            failed -> "Their films and shows didn’t load. Check that the Fire TV is online."
                            list != null -> "No films or shows"
                            else -> "Loading…"
                        },
                        style = Type.body, maxLines = 1,
                        // Something holds focus while there are no cards, so Back still answers.
                        modifier = if (list.isNullOrEmpty()) Modifier.focusRequester(status).focusable() else Modifier,
                    )
                    if (failed) ActionButton("Try again", { attempt++ }, Modifier.padding(top = Space.s))
                }
            }
            if (cards && !list.isNullOrEmpty()) itemsIndexed(list, key = { _, m -> m.key }, contentType = { _, _ -> "card" }) { i, m ->
                PosterCard(
                    m, null, watched = m.titleId in watched,
                    focus = if (i == 0) first else null,
                    back = if (m.key == opened) back else null,
                    onFocused = {},
                    onClick = {
                        opened = m.key
                        onOpen(m)
                    },
                )
            }
        }
    }
}

/** A row of a show's episode list: a season's heading, or an episode (its index in the show's list). */
private sealed interface EpisodeLine {
    data class Season(val season: Int) : EpisodeLine
    data class Item(val index: Int, val episode: Episode) : EpisodeLine
}

/**
 * A show's title page: its backdrop, then on the left everything about it and Resume (or Play) on the
 * episode up next; on the right its episodes by season, each with its still, length and progress.
 * Right enters the list on the episode up next; OK on any plays it from its place. Below, its cast and
 * More like this. The episodes are the provider's when it lists the show, else TMDB's (Real-Debrid plays
 * those). Back returns to where it was opened. [hidden] while an episode, or a page over it, is up.
 * [watched] is the films watched (for More like this); episodes keep their own marks.
 */
@Composable
private fun ShowPage(
    show: Movie, resume: Resume?, watched: Set<TitleId>, active: Boolean, hidden: Boolean,
    onPlay: (List<Episode>, Int, fromStart: Boolean) -> Unit, onOpen: (Movie) -> Unit, onPerson: (Person) -> Unit, onBack: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val sync = graph.sync
    // The stick's marks with the box's (U16).
    val seen = watched
    val ratings by graph.marks.ratings.collectAsState()
    val info = rememberInfo(show)
    val extras = remember { ExtrasFocus() }
    var data by remember { mutableStateOf(sync.peekShowOf(show)) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        Check.log("title_shown", "title" to show.titleId)
    }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        // Fresh on opening, so a new episode shows; the kept copy (if any) is on screen meanwhile.
        sync.showOf(show, fresh = true).onSuccess { data = it }.onFailure { if (data == null) failed = true }
    }
    val episodes = data?.episodes
    val lines = remember(episodes) {
        buildList {
            episodes?.forEachIndexed { i, e ->
                if (i == 0 || episodes[i - 1].season != e.season) add(EpisodeLine.Season(e.season))
                add(EpisodeLine.Item(i, e))
            }
        }
    }
    var lastPlayed by remember { mutableStateOf<Int?>(null) }
    val target = episodes?.takeIf { it.isNotEmpty() }?.let { lastPlayed ?: upNextIndex(it, resume, seen) }
    val primary = remember { FocusRequester() }
    val targetRow = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var hasFocus by remember { mutableStateOf(false) }
    // The episode whose Versions are open, and whose marks are (Menu on its row; -1 the show's, Menu elsewhere).
    var versionsFor by remember { mutableStateOf<Int?>(null) }
    var marksFor by remember { mutableStateOf<Int?>(null) }
    val panel = versionsFor != null || marksFor != null
    // Where focus goes once the panel closes: the list (back on its row) or the page's first button.
    val backTo = remember { arrayOfNulls<FocusRequester>(1) }
    LaunchedEffect(panel) {
        val back = backTo[0]
        if (panel || back == null || !active) return@LaunchedEffect
        backTo[0] = null
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { back.requestFocus() }
        }
    }
    LaunchedEffect(active, target != null, failed) {
        if (!active) return@LaunchedEffect
        if (target != null && extras.opened == null) list.scrollToItem((lines.indexOfFirst { it is EpisodeLine.Item && it.index == target } - 1).coerceAtLeast(0))
        try {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (hasFocus) return@LaunchedEffect
                runCatching { (if (extras.opened != null) extras.back else primary).requestFocus() }
            }
        } finally {
            extras.opened = null
        }
    }
    val onMenu = if (!panel && episodes != null && graph.marks.canMark(show.titleId)) ({ backTo[0] = primary; marksFor = -1 }) else null
    TitleFrame(backdropOf(show, info) ?: data?.backdrop, hidden, onBack, onFocus = { hasFocus = it }, onMenu = onMenu) {
        TitleScroll(
            main = {
                Row(Modifier.fillMaxSize().padding(start = EDGE, end = EDGE), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    Column(Modifier.width(SHOW_INFO_WIDTH).fillMaxHeight().padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.Bottom)) {
                        Text(show.title.title, style = BIG_TITLE, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val seasons = episodes?.map { it.season }?.distinct()?.size
                        MetaLine(show, info ?: data?.info, extra = seasons?.let { if (it == 1) "1 season" else "$it seasons" })
                        (info?.plot?.ifBlank { null } ?: data?.plot?.ifBlank { null })?.let { Text(it, style = Type.body, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                        if (info?.people.isNullOrEmpty()) data?.cast?.ifBlank { null }?.let { Text("Starring $it", style = Type.caption, maxLines = 2) }
                        val next = target?.let { episodes[it] }
                        // The copy the episode up next plays on, prepared as the page opens; TMDB's episodes have no IPTV copy of their own.
                        if (next != null) key(next.titleId) { PlaysLine(next.titleId, fallback = if (next.id > 0) listOf("IPTV") + next.quality else null) }
                        val started = next != null && resume?.episode == next.titleId && resume.pos > 0
                        Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            when {
                                next != null -> {
                                    PageButton("${if (started) "Resume" else "Play"} ${next.code}", { onPlay(episodes, target, false) }, Modifier.focusRequester(primary))
                                    if (started) PageButton("Start over", { onPlay(episodes, target, true) })
                                    WatchlistButton(show.titleId)
                                }
                                failed -> PageButton("Try again", { attempt++ }, Modifier.focusRequester(primary))
                                // Something holds focus meanwhile, so Back still answers.
                                else -> Text(if (episodes == null) "Loading episodes…" else "No episodes yet", style = Type.body, modifier = Modifier.focusRequester(primary).focusable())
                            }
                        }
                        if (failed) Text("Episodes didn’t load. Check that the Fire TV is online.", style = Type.caption)
                    }
                    if (!episodes.isNullOrEmpty()) LazyColumn(
                        // On the app's floating material, so the list reads over any backdrop.
                        Modifier.weight(1f).padding(vertical = Space.safeY).material().edgeFade(Palette.material, top = Space.m, bottom = Space.xl)
                            .focusRequester(listFocus).focusRestorer(targetRow).focusGroup(),
                        state = list,
                        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        items(lines, key = { if (it is EpisodeLine.Item) it.episode.id else "s${(it as EpisodeLine.Season).season}" }) { line ->
                            when (line) {
                                is EpisodeLine.Season -> Text("Season ${line.season}", style = Type.headline, modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs))
                                is EpisodeLine.Item -> EpisodeRow(
                                    line.episode,
                                    watched = line.episode.titleId in seen,
                                    rating = ratings[line.episode.titleId],
                                    progress = resume?.takeIf { it.episode == line.episode.titleId && it.dur > 0 && it.pos > 0 }?.let { it.pos.toFloat() / it.dur },
                                    focus = if (line.index == target) targetRow else null,
                                    onClick = {
                                        lastPlayed = line.index
                                        onPlay(episodes, line.index, false)
                                    },
                                    onMenu = {
                                        backTo[0] = listFocus
                                        if (graph.marks.canMark(line.episode.titleId)) marksFor = line.index else versionsFor = line.index
                                    },
                                )
                            }
                        }
                    }
                }
            },
            extras = { TitleExtras(info, watched, extras, onPerson, onOpen) },
        )
        versionsFor?.let { i ->
            VersionsPanel(
                episodes!![i].titleId,
                onPlay = {
                    lastPlayed = i
                    onPlay(episodes, i, false)
                },
                onClose = { versionsFor = null },
                modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
            )
        }
        marksFor?.let { i ->
            val close = { marksFor = null }
            val at = Modifier.align(Alignment.CenterEnd).padding(Space.safe)
            if (i < 0) {
                val all = episodes.orEmpty().filter { it.season > 0 && it.titleId.isNotEmpty() }
                val season = target?.let { episodes!![it].season }?.takeIf { s -> s > 0 && all.any { it.season != s } }
                MarksPanel(
                    show.title.title,
                    listOfNotNull(
                        season?.let { s -> "Mark season $s watched" to { graph.marks.setWatched("${show.titleId}:s$s", true, all.filter { it.season == s }.map { it.titleId }); close() } },
                        "Mark show watched" to { graph.marks.setWatched(show.titleId, true, all.map { it.titleId }); close() },
                    ),
                    ratings[show.titleId], onRate = { graph.marks.rate(show.titleId, it); close() }, onClose = close, modifier = at,
                )
            } else {
                val e = episodes!![i]
                val done = e.titleId in seen
                MarksPanel(
                    listOfNotNull(e.code, e.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }).joinToString(" · "),
                    listOf(
                        (if (done) "Mark unwatched" else "Mark watched") to { graph.marks.setWatched(e.titleId, !done); close() },
                        "Versions" to { marksFor = null; versionsFor = i },
                    ),
                    ratings[e.titleId], onRate = { graph.marks.rate(e.titleId, it); close() }, onClose = close, modifier = at,
                )
            }
        }
    }
}

/**
 * An episode: its still (else its number), title, length, how far it was watched, a check once seen;
 * the owner's rating; focused, the copy it plays on (prepared once focus rests there), and Menu opens its marks and Versions.
 */
@Composable
private fun EpisodeRow(episode: Episode, watched: Boolean, rating: Int?, progress: Float?, focus: FocusRequester?, onClick: () -> Unit, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(episode.image) {
        episode.image.ifEmpty { null }?.let { with(density) { ImageRequest.Builder(context).data(tmdbAt(it, "w300")).size(STILL.width.roundToPx(), STILL.height.roundToPx()).crossfade(POSTER_FADE_MS).build() } }
    }
    // "Episode 4" says nothing a number doesn't.
    val title = episode.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown && episode.titleId.isNotEmpty()) onMenu()
                true
            }
            .pressable(onClick)
            .padding(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(Modifier.size(STILL).graphicsLayer { shape = CARD_SHAPE; clip = true }.drawBehind { drawRect(Palette.raised) }, contentAlignment = Alignment.Center) {
            if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("${episode.episode}", style = Type.title.copy(color = Palette.textSecondary))
            if (progress != null) ProgressLine({ progress }, Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.xs, vertical = Space.xs))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FitText(listOfNotNull("${episode.episode}", title).joinToString(". "), style = Type.headline, active = focused)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (episode.secs > 0) Text("${(episode.secs + 30) / 60} min", style = Type.caption, maxLines = 1)
                if (watched) Text("Watched", style = Type.caption.copy(color = Palette.accent), maxLines = 1)
                if (rating != null) Text(yoursLabel(rating), style = Type.caption.copy(color = Palette.outOf10(rating.toDouble())), maxLines = 1)
                if (focused && episode.titleId.isNotEmpty()) EpisodePlays(episode.titleId)
            }
        }
    }
}

/** A focused episode's copy, in its caption line ("Plays: IPTV · 1080p · Menu for versions"); asked for only once focus rests. */
@Composable
private fun EpisodePlays(title: TitleId) {
    val films = LocalContext.current.graph.films
    val p by produceState<Prepared?>(null, title) {
        delay(WARM_REST_MS)
        films.state(title).collect { value = it }
    }
    val plays = p?.plays
    LaunchedEffect(plays) { if (plays != null) Check.log("plays", "title" to title, "copy" to plays.id, "src" to plays.source) }
    val text = plays?.let { (listOf(sourceName(it.source)) + copyLabels(it).take(2)).joinToString(" · ") } ?: return
    FitText("Plays: $text · Menu for more", style = Type.caption, active = true)
}

private val GENERIC_EPISODE = Regex("""(?i)episode\s*\d+""")

/** A watched film's corner mark: a check on an accent disc. */
@Composable
private fun WatchedMark(modifier: Modifier) {
    Box(
        modifier.size(22.dp).drawBehind {
            drawCircle(Palette.accent)
            val w = size.width
            val check = Path().apply {
                moveTo(w * 0.28f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.68f)
                lineTo(w * 0.73f, w * 0.36f)
            }
            drawPath(check, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        },
    )
}
