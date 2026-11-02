package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedContent
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
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.episodeUrl
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.Movies
import com.mohamed3on.iptv.data.Resume
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.data.groupLabel
import com.mohamed3on.iptv.data.movieUrl
import com.mohamed3on.iptv.data.posterUrl
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.ExoStreamPlayer
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
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
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
private val CARD_SHAPE = RoundedCornerShape(Radius.row)
private val RING = 3.dp
private val EDGE = 48.dp
private val HERO_HEIGHT = 104.dp
/** Type.headline's line: a row's title above its cards. */
private val ROW_TITLE = 27.dp
/** Where a card's top sits inside its row: under the title and the row's lift room. */
private val CARD_TOP = ROW_TITLE + Space.s + Space.m
private val KEYBOARD_WIDTH = 620.dp
/** Rows slide under the hero through a short fade, never a hard edge; the focused row sits just below it. */
private val FADE = Space.l
/** The flat tint over the still-playing video: live shows through faintly, text reads as on ink. */
private val MoviesScrim = Palette.ink.copy(alpha = 0.94f)
/** Focus rests this long before the hero changes, so holding a key never churns it. */
private const val HERO_REST_MS = 90L
private const val FOCUS_FRAMES = 30
private const val CONTINUE = "continue"

/** Which library a screen browses; both share it, a show opening its episodes where a movie plays. */
enum class LibraryKind(val title: String, val noun: String) {
    Movies("Movies", "movies"),
    Shows("Shows", "shows"),
}

/** The library on screen, for the wording of its search, loading and error states. */
private val LocalLibrary = staticCompositionLocalOf { LibraryKind.Movies }

/** A show's episode on screen: the show, its episodes, and which one plays. */
private data class ShowPlay(val show: Movie, val episodes: List<Episode>, val index: Int) {
    val episode get() = episodes[index]
}

/** What plays: the movie (for a show, the episode as one) and where it starts (ms), from [url]. */
private data class Playing(val movie: Movie, val from: Long, val url: String, val show: ShowPlay? = null)

/**
 * Movies and Shows (v2): a library over the still-playing live channel, which stops only once
 * something plays (the account allows one stream), and resumes when the screen is left with Back.
 * A show opens its episodes first; the next episode plays when one ends. [shown] is its entrance
 * (0 → 1); [open] is false while it leaves. [onPlaying] says whether a film or episode is on
 * screen, so the live picture steps aside for it.
 */
@Composable
fun MoviesLayer(
    kind: LibraryKind,
    feed: Feed,
    session: StreamSession,
    tiles: MultiView<ExoStreamPlayer>,
    shown: () -> Float,
    open: Boolean,
    onPlaying: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val sync = if (kind == LibraryKind.Movies) graph.sync.movies else graph.sync.shows
    val store = if (kind == LibraryKind.Movies) graph.resume else graph.showResume
    val watchedStore = if (kind == LibraryKind.Movies) graph.watchedMovies else graph.watchedEpisodes
    val watched by watchedStore.ids.collectAsState()
    val library by sync.items.collectAsState()
    val resume by store.entries.collectAsState()
    val live by rememberUpdatedState(session)
    var playing by remember { mutableStateOf<Playing?>(null) }
    var detail by remember { mutableStateOf<Movie?>(null) }
    var stoppedLive by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<SyncError?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = null
        sync.loadCached()
        val error = sync.sync(12.hours)
        if (sync.items.value == null) failed = error ?: SyncError.Malformed
    }

    // However the layer goes, the live picture comes back.
    DisposableEffect(Unit) { onDispose { onPlaying(false) } }

    fun leave() {
        // The live channel comes back where it was, unless the app restarted it meanwhile (Home and back).
        if (stoppedLive && live.state.value.phase == Phase.Stopped) live.retry()
        onClose()
    }

    fun play(next: Playing) {
        tiles.close()
        live.stop()
        stoppedLive = true
        playing = next
        onPlaying(true)
    }

    fun episode(show: Movie, episodes: List<Episode>, index: Int, from: Long): Playing {
        val e = episodes[index]
        val name = listOf(show.title.title, e.code, e.title).filter(String::isNotEmpty).joinToString(" · ")
        val movie = Movie(id = e.id, name = name, poster = show.poster, group = show.group, ext = e.ext)
        return Playing(movie, from, feed.route.episodeUrl(graph.route.effective.value, e.id, e.ext), ShowPlay(show, episodes, index))
    }

    fun stopped() {
        playing = null
        onPlaying(false)
    }

    CompositionLocalProvider(LocalLibrary provides kind) {
        Box(Modifier.fillMaxSize()) {
            MoviesBrowse(
                library = library,
                resume = resume,
                watched = if (kind == LibraryKind.Movies) watched else emptySet(),
                failed = failed,
                onRetry = { attempt++ },
                active = open && playing == null && detail == null,
                hidden = playing != null || detail != null,
                shown = shown,
                onPlay = { movie ->
                    if (kind == LibraryKind.Shows) detail = movie
                    else play(Playing(movie, store.startAt(movie), feed.route.movieUrl(graph.route.effective.value, movie.id, movie.ext)))
                },
                onBack = ::leave,
            )
            detail?.let { show ->
                key(show) {
                    ShowDetail(
                        show = show,
                        resume = resume.firstOrNull { it.movie.id == show.id },
                        watched = watched,
                        active = open && playing == null,
                        hidden = playing != null,
                        onPlay = { episodes, i -> play(episode(show, episodes, i, store.startAt(show, episodes[i].id))) },
                        onBack = { detail = null },
                    )
                }
            }
            playing?.let { p ->
                key(p) {
                    val s = p.show
                    MoviePlayback(
                        movie = p.movie,
                        from = p.from,
                        url = p.url,
                        onSave = { pos, dur ->
                            if (dur > 0 && pos >= dur * 0.95) watchedStore.mark(p.movie.id)
                            if (s == null) store.save(p.movie, pos, dur) else store.save(s.show, pos, dur, s.episode.id, s.episode.code)
                        },
                        onEnded = {
                            val next = s?.takeIf { it.index < it.episodes.lastIndex }
                            if (next == null) stopped()
                            else {
                                // Continue watching moves on to the next episode even if it is left in its first minute.
                                store.save(next.show, 0, 0, next.episodes[next.index + 1].id, next.episodes[next.index + 1].code)
                                playing = episode(next.show, next.episodes, next.index + 1, 0)
                            }
                        },
                        onClose = ::stopped,
                    )
                }
            }
        }
    }
}

/** A row of the screen: Continue watching, then each group. [progress] is every saved place, by movie id. */
private class MovieRow(val key: String, val title: String, val badge: String?, val movies: List<Movie>, val progress: Map<Int, Float>)

private fun rowsOf(library: Movies?, resume: List<Resume>): List<MovieRow> {
    library ?: return emptyList()
    val progress = resume.filter { it.dur > 0 }.associate { it.movie.id to it.pos.toFloat() / it.dur }
    return buildList {
        if (resume.isNotEmpty()) add(MovieRow(CONTINUE, "Continue watching", null, resume.map { library.find(it.movie.id) ?: it.movie }, progress))
        for ((group, movies) in library.rows) {
            val label = groupLabel(group)
            add(MovieRow("g|$group", label.name, label.badge, movies, progress))
        }
    }
}

/**
 * The browsing screen: a hero for the focused title over rows of posters, or search. While a movie
 * plays it stays composed but undrawn ([hidden]), so Back lands on the same card. [active] is when it
 * should hold focus.
 */
@Composable
private fun MoviesBrowse(
    library: Movies?,
    resume: List<Resume>,
    watched: Set<Int>,
    failed: SyncError?,
    onRetry: () -> Unit,
    active: Boolean,
    hidden: Boolean,
    shown: () -> Float,
    onPlay: (Movie) -> Unit,
    onBack: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val rows = remember(library, resume) { rowsOf(library, resume) }
    val focused = remember { mutableStateOf<Movie?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    val content = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    val keys = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    // The rows are built a frame after the rest, so neither frame of the opening is long enough to drop.
    var built by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        built = true
    }
    val kind = when {
        searching && library != null -> 3
        rows.isNotEmpty() -> if (built) 2 else -1
        failed != null -> 1
        else -> 0
    }

    // Leaving search goes back to its button; otherwise focus goes in once there is something to
    // hold it, and back to the same card after a movie.
    val leftSearch = remember { booleanArrayOf(false) }
    LaunchedEffect(active, kind) {
        if (!active) return@LaunchedEffect
        val target = when {
            kind == 3 -> results
            leftSearch[0] -> searchButton
            else -> content
        }
        leftSearch[0] = false
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { target.requestFocus() }
        }
    }
    fun closeSearch() {
        leftSearch[0] = true
        searching = false
        focused.value = null
    }

    Box(
        Modifier
            .fillMaxSize()
            // Undrawn under a playing movie: an alpha-0 layer costs the GPU nothing.
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onFocusChanged { hasFocus = it.hasFocus }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) if (searching) closeSearch() else onBack()
                true // the key-up too, so Back never reaches the player or leaves the app
            }
            .focusGroup(),
    ) {
        // The tint fades in place while the content rises into it.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(MoviesScrim, alpha = shown().coerceIn(0f, 1f)) })
        Column(Modifier.fillMaxSize().slideFade(shown, Edge.Bottom, reduced).padding(top = 27.dp)) {
            Row(Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(horizontal = EDGE), verticalAlignment = Alignment.Top) {
                Hero(focused, resume, placeholder = if (searching) "Search ${LocalLibrary.current.noun}" else LocalLibrary.current.title, modifier = Modifier.weight(1f))
                if (!searching && library != null) ActionButton("Search", onClick = {
                    focused.value = null
                    searching = true
                }, Modifier.focusRequester(searchButton))
            }
            when (kind) {
                3 -> SearchGrid(library!!, query, { query = it }, results, keys, onFocused = { focused.value = it }, onPlay = onPlay)
                2 -> Rows(rows, watched, content, active, onFocused = { focused.value = it }, onPlay = onPlay)
                1 -> Unavailable(content, onRetry)
                0 -> LoadingMovies(content)
                else -> {}
            }
        }
    }
}

/**
 * The focused title, its badge, year and group, and how much is left of a started one. It cross-fades
 * once focus rests, in a fixed box, so the rows below never move.
 */
@Composable
private fun Hero(focused: State<Movie?>, resume: List<Resume>, placeholder: String, modifier: Modifier) {
    var movie by remember { mutableStateOf(focused.value) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest {
            delay(HERO_REST_MS)
            movie = it
        }
    }
    AnimatedContent(
        targetState = movie,
        contentKey = { it?.id },
        transitionSpec = { fadeIn(Motion.spring(0.3f)) togetherWith fadeOut(Motion.spring(0.2f)) using SizeTransform(clip = false) },
        modifier = modifier,
        label = "hero",
    ) { m ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (m == null) {
                Text(placeholder, style = Type.display, maxLines = 1)
                return@Column
            }
            val title = m.title
            Text(title.title, style = Type.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A show also says which episode it is on: "S1 E3 · 40 min left".
            val left = remember(m, resume) {
                resume.firstOrNull { it.movie.id == m.id }?.let { r ->
                    listOfNotNull(r.label, r.takeIf { it.dur > 0 && it.pos > 0 }?.let { leftLabel(it.dur - it.pos) }).joinToString(" · ").ifEmpty { null }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                title.badge?.let { Badges(remember(it) { listOf(it) }) }
                Text(listOfNotNull(title.year, groupLabel(m.group).name, left).joinToString(" · "), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "1 h 12 min left", "42 min left". */
private fun leftLabel(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min left" else "$min min left"
}

/**
 * Rows of posters. The focused row rises to the top of the list; within a row the focused card keeps a
 * card's width from either edge. Each row comes back to the card last focused in it, and the screen to
 * the card focused last ([focus] restores it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Rows(rows: List<MovieRow>, watched: Set<Int>, focus: FocusRequester, active: Boolean, onFocused: (Movie) -> Unit, onPlay: (Movie) -> Unit) {
    val density = LocalDensity.current
    val first = remember { FocusRequester() }
    // The card each row was left on, for a row built again after scrolling far away.
    val columns = remember { HashMap<String, Int>() }
    val pivot = remember(density) {
        val top = with(density) { (CARD_TOP + FADE).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - top
        }
    }
    val keepClear = remember(density) {
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
    // The card a movie was played from (row key, movie id). Back from the movie lands on it explicitly:
    // Continue watching is added above meanwhile, which throws the focus restorers off.
    val list = rememberLazyListState()
    val back = remember { FocusRequester() }
    var played by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val lastFocused = remember { arrayOfNulls<Pair<String, Int>>(1) }
    LaunchedEffect(active) {
        val target = played?.takeIf { active } ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == target.first }
        if (index >= 0) {
            list.scrollToItem(index)
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (lastFocused[0] == target) return@repeat
                runCatching { back.requestFocus() }
            }
        }
        played = null
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(
            Modifier.fillMaxSize().edgeFade(MoviesScrim, top = FADE).focusRequester(focus).focusRestorer(first).focusGroup(),
            state = list,
            contentPadding = PaddingValues(top = FADE, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "row" }) { i, row ->
                CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                    PosterRow(
                        row,
                        watched = watched,
                        entry = if (i == 0) first else null,
                        startAt = columns[row.key] ?: 0,
                        back = played?.takeIf { it.first == row.key }?.let { it.second to back },
                        onFocused = { col, movie ->
                            columns[row.key] = col
                            lastFocused[0] = row.key to movie.id
                            onFocused(movie)
                        },
                        onPlay = { movie ->
                            played = row.key to movie.id
                            onPlay(movie)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(row: MovieRow, watched: Set<Int>, entry: FocusRequester?, startAt: Int, back: Pair<Int, FocusRequester>?, onFocused: (Int, Movie) -> Unit, onPlay: (Movie) -> Unit) {
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
                    val left = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                    val edge = (left && focusedCol[0] == 0) || (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && focusedCol[0] == row.movies.lastIndex)
                    if (edge && !reduced && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) scope.launch {
                        nudge.animateTo(if (left) -1f else 1f, Motion.spring(0.1f))
                        nudge.animateTo(0f, Motion.spring(0.3f))
                    }
                    edge
                }
                .focusRestorer(restore)
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            itemsIndexed(row.movies, key = { _, m -> m.id }, contentType = { _, _ -> "card" }) { col, movie ->
                PosterCard(
                    movie, row.progress[movie.id], watched = movie.id in watched,
                    focus = if (col == at) restore else null,
                    back = back?.takeIf { it.first == movie.id }?.second,
                    onFocused = {
                        focusedCol[0] = col
                        onFocused(col, movie)
                    },
                    onClick = { onPlay(movie) },
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
private fun PosterCard(movie: Movie, progress: Float?, watched: Boolean = false, focus: FocusRequester?, back: FocusRequester? = null, onFocused: () -> Unit, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    var failed by remember(movie.poster) { mutableStateOf(false) }
    val request = remember(movie.poster) {
        movie.poster.takeIf { it.isNotEmpty() }?.let {
            with(density) { ImageRequest.Builder(context).data(posterUrl(it)).size(CARD.width.roundToPx(), CARD.height.roundToPx()).build() }
        }
    }
    Box(
        Modifier
            .size(CARD)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .then(if (back != null) Modifier.focusRequester(back) else Modifier)
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
    }
}

private fun monogram(title: String): String =
    title.split(' ').mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("") { it.uppercase() }

/**
 * Search: the app's keyboard over a grid of matching posters (titles with every typed word). Back
 * leaves it. [focus] restores the result last focused, else the keyboard ([keys]).
 */
@Composable
private fun SearchGrid(
    library: Movies,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    keys: FocusRequester,
    onFocused: (Movie?) -> Unit,
    onPlay: (Movie) -> Unit,
) {
    var found by remember { mutableStateOf<List<Movie>?>(null) }
    // Runs once typing pauses; the last results stay meanwhile.
    LaunchedEffect(query, library) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        delay(150)
        found = withContext(Dispatchers.Default) { library.search(query) }
    }
    val first = remember { FocusRequester() }
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
                )
            }
        }
        val list = found
        when {
            query.isBlank() -> item(key = "hint", span = { GridItemSpan(maxLineSpan) }) { HintLine("Type part of a title") }
            list == null -> {}
            list.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) { HintLine("No ${LocalLibrary.current.noun} match “${query.trim()}”") }
            else -> itemsIndexed(list, key = { _, m -> m.id }) { i, movie ->
                PosterCard(movie, null, focus = if (i == 0) first else null, onFocused = { onFocused(movie) }, onClick = { onPlay(movie) })
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

private val SHOW_POSTER = DpSize(198.dp, 297.dp)
private val EPISODE_NUMBER = 44.dp

/** A row of a show's episode list: a season's heading, or an episode (its index in the show's list). */
private sealed interface EpisodeLine {
    data class Season(val season: Int) : EpisodeLine
    data class Item(val index: Int, val episode: Episode) : EpisodeLine
}

/**
 * A show's episodes: its poster and name, then each season's episodes in order. Focus starts on the
 * episode Continue watching points at (else the one last played, else the first); OK plays it from
 * its place. Back returns to the rows. [hidden] while an episode plays over it.
 */
@Composable
private fun ShowDetail(show: Movie, resume: Resume?, watched: Set<Int>, active: Boolean, hidden: Boolean, onPlay: (List<Episode>, Int) -> Unit, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        graph.sync.episodes(show.id).onSuccess { episodes = it }.onFailure { failed = true }
    }
    val lines = remember(episodes) {
        buildList {
            episodes?.forEachIndexed { i, e ->
                if (i == 0 || episodes!![i - 1].season != e.season) add(EpisodeLine.Season(e.season))
                add(EpisodeLine.Item(i, e))
            }
        }
    }
    var lastPlayed by remember { mutableStateOf<Int?>(null) }
    // Up next: the episode in progress, else the one last played here, else the first not yet watched.
    val target = episodes?.let { eps ->
        eps.indexOfFirst { it.id == resume?.episode }.takeIf { it >= 0 } ?: lastPlayed ?: eps.indexOfFirst { it.id !in watched }.takeIf { it >= 0 } ?: 0
    }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active, target, failed) {
        if (!active) return@LaunchedEffect
        if (target != null) list.scrollToItem((lines.indexOfFirst { it is EpisodeLine.Item && it.index == target } - 1).coerceAtLeast(0))
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .drawBehind { drawRect(MoviesScrim) }
            .onFocusChanged { hasFocus = it.hasFocus }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onBack()
                true // the key-up too, so Back never reaches the rows behind
            }
            .focusGroup(),
    ) {
        Row(Modifier.fillMaxSize().padding(start = EDGE, end = EDGE, top = 27.dp), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
            Column(Modifier.width(SHOW_POSTER.width), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                val context = LocalPlatformContext.current
                val density = LocalDensity.current
                val request = remember(show.poster) {
                    with(density) { ImageRequest.Builder(context).data(posterUrl(show.poster)).size(SHOW_POSTER.width.roundToPx(), SHOW_POSTER.height.roundToPx()).build() }
                }
                Box(Modifier.size(SHOW_POSTER).graphicsLayer { shape = CARD_SHAPE; clip = true }.drawBehind { drawRect(Palette.raised) }) {
                    if (show.poster.isNotEmpty()) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(show.title.title, style = Type.title, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
                val seasons = episodes?.map { it.season }?.distinct()?.size
                val meta = listOfNotNull(
                    show.title.year,
                    seasons?.let { if (it == 1) "1 season" else "$it seasons" },
                    episodes?.size?.let { if (it == 1) "1 episode" else "$it episodes" },
                    episodes?.count { it.id in watched }?.takeIf { it > 0 }?.let { "$it watched" },
                )
                Text(meta.joinToString(" · "), style = Type.body, maxLines = 3)
                Text(groupLabel(show.group).name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val eps = episodes
            when {
                eps != null && eps.isNotEmpty() -> LazyColumn(
                    Modifier.weight(1f).fillMaxHeight().edgeFade(MoviesScrim, top = Space.l).focusGroup(),
                    state = list,
                    contentPadding = PaddingValues(top = Space.l, bottom = Space.xxxl),
                ) {
                    items(lines, key = { if (it is EpisodeLine.Item) it.episode.id else "s${(it as EpisodeLine.Season).season}" }) { line ->
                        when (line) {
                            is EpisodeLine.Season -> Text("Season ${line.season}", style = Type.headline, modifier = Modifier.padding(start = Space.l, top = Space.l, bottom = Space.s))
                            is EpisodeLine.Item -> EpisodeRow(
                                line.episode,
                                watched = line.episode.id in watched,
                                progress = resume?.takeIf { it.episode == line.episode.id && it.dur > 0 && it.pos > 0 }?.let { it.pos.toFloat() / it.dur },
                                focus = if (line.index == target) focus else null,
                                onClick = {
                                    lastPlayed = line.index
                                    onPlay(eps, line.index)
                                },
                            )
                        }
                    }
                }
                // Something holds focus meanwhile, so Back still answers.
                failed -> Column(Modifier.padding(top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Episodes didn’t load", style = Type.title)
                    Text("Check that the Fire TV is online, then try again.", style = Type.body)
                    ActionButton("Try again", { attempt++ }, Modifier.padding(top = Space.m).focusRequester(focus))
                }
                else -> Text(
                    if (eps == null) "Loading episodes…" else "No episodes yet",
                    style = Type.body,
                    modifier = Modifier.padding(top = Space.xl).focusRequester(focus).focusable(),
                )
            }
        }
    }
}

/** "3   Good News About Hell   57 min", with how far it was watched. */
@Composable
private fun EpisodeRow(episode: Episode, watched: Boolean, progress: Float?, focus: FocusRequester?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .pressable(onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("${episode.episode}", style = Type.body, modifier = Modifier.width(EPISODE_NUMBER))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(episode.title.ifEmpty { "Episode ${episode.episode}" }, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) ProgressLine({ progress }, Modifier.fillMaxWidth())
        }
        if (episode.secs > 0) Text("${(episode.secs + 30) / 60} min", style = Type.body, maxLines = 1)
        if (watched) Text("✓", style = Type.headline.copy(color = Palette.accent))
    }
}

/** A watched film's corner mark: a check on an accent disc. */
@Composable
private fun WatchedMark(modifier: Modifier) {
    Box(modifier.size(22.dp).drawBehind { drawCircle(Palette.accent) }, contentAlignment = Alignment.Center) {
        Text("✓", style = Type.body.copy(color = Color.White), maxLines = 1)
    }
}
