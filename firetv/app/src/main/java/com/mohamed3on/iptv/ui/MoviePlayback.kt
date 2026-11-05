package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.mohamed3on.iptv.player.SubtitleOption
import com.mohamed3on.iptv.player.SubtitleState
import com.mohamed3on.iptv.player.SubtitleTracks
import com.mohamed3on.iptv.player.qualityLine
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.mohamed3on.iptv.player.filmReading
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.graph
import android.util.Log
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.player.BandwidthMeter
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.FilmLoad
import com.mohamed3on.iptv.player.AudioTracks
import com.mohamed3on.iptv.player.FrameRate
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.SessionState
import com.mohamed3on.iptv.player.moviePlayer
import com.mohamed3on.iptv.ui.theme.ActionButton
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Scrim
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.bottomScrim
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.reveal
import com.mohamed3on.iptv.ui.theme.slideFade
import com.mohamed3on.iptv.KeyCap
import com.mohamed3on.iptv.data.TitleId
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.ExoFilmPlayer
import com.mohamed3on.iptv.player.FilmPhase
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.FilmState
import com.mohamed3on.iptv.player.Notice
import com.mohamed3on.iptv.player.OfferKind
import com.mohamed3on.iptv.player.Place
import com.mohamed3on.iptv.ui.theme.Radius
import androidx.compose.foundation.layout.ColumnScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent

private const val CONTROLS_MS = 4_000L
private const val SAVE_EVERY_MS = 10_000L
/** A seek waits this long for the next step, so a run of presses costs one reconnect, not one each. */
private const val SEEK_COMMIT_MS = 450L
private const val STEP_MS = 10_000L
private const val HELD_STEP_MS = 30_000L
private const val JUMP_MS = 30_000L
/** Holding Left/Right that long switches to [HELD_STEP_MS] steps. */
private const val HOLD_MS = 1_000L
/** Key repeats closer together than this are skipped: about ten steps a second. */
private const val REPEAT_MS = 100L
private const val BUFFERING_SHOWN_MS = 400L
private const val NOTE_MS = 3_000L
private val BAR_HEIGHT = 5.dp
private val THUMB = 14.dp
private val PANEL_WIDTH = 420.dp
private val CARD_WIDTH = 560.dp

/** The player's own view of ExoPlayer, kept in Compose state on the main thread. */
private class MovieState {
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var error by mutableStateOf(false)
    /** Whether it ever played: only then is its place worth saving. */
    var started = false
}

/**
 * A movie full screen (v2): [title] from [from] (ms), on the best copy a [FilmSession] finds (the
 * prepared copies, else [fallback], the library's IPTV copy, after 4 s), keeping its place every 10s
 * and on the way out. Any key shows the controls (title, the copy, progress, elapsed and remaining),
 * which hide 4s after the last key while it plays. OK pauses and plays; Left/Right step 10s (30s once
 * held for a second), ⏪/⏩ 30s, and a run of steps seeks once it stops; Up (▲) takes the offer in the
 * corner; Menu has the version, audio, subtitles and Play from beginning; Back drops a pending seek,
 * else undoes a trial or dismisses the notice, else hides the controls, else returns.
 * [onSave] keeps the place (ms); [onEnded] follows the end, after the place is saved as finished.
 */
@Composable
internal fun MoviePlayback(
    movie: Movie, title: TitleId, from: Long, fallback: Copy?, iptvUrl: (Copy) -> String,
    onSave: (pos: Long, dur: Long) -> Unit, onEnded: () -> Unit, onClose: () -> Unit, onNext: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val graph = context.graph
    val window = LocalActivity.current?.window
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val ended by rememberUpdatedState(onEnded)
    val keep by rememberUpdatedState(onSave)
    // The file's size, for its bitrate in the film's quality reading.
    val fileBytes = remember { java.util.concurrent.atomic.AtomicLong(0) }
    // Its loading bursts teach the typical bandwidth (KTD8); every film is an IPTV copy until Real-Debrid plays.
    val load = remember { FilmLoad() }
    val meter = remember { BandwidthMeter(graph.capacity) }
    val exo = remember { moviePlayer(context, graph.http, load) { fileBytes.compareAndSet(0, it) } }
    // A film's first play measures what it really is (an episode's is known already), for its quality and tier.
    val films = LocalLibrary.current == LibraryKind.Movies
    val ui = remember { MovieState() }
    val audio = remember { AudioTracks(exo) }
    val subtitles = remember { SubtitleTracks(exo) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    // The picture keeps its own shape (a 2.39:1 film letterboxed), never stretched to the screen; 0 until known.
    var aspect by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    // One session runs the film over its copies: failover, step-down, offers and trials (U9).
    val film = remember { ExoFilmPlayer(exo) }
    val setup = remember { graph.setup }
    val session = remember {
        FilmSession(title, null, film, graph.copyChoices, { setup.profile.value }, meter, graph.debrid, iptvUrl, scope, System::currentTimeMillis, graph.telemetry::film)
    }

    fun save() {
        if (ui.started) keep(exo.currentPosition, exo.duration.coerceAtLeast(0))
    }

    DisposableEffect(Unit) {
        // FrameRate follows a session's phase; a movie is "playing" from its first frame to the end.
        val phase = MutableStateFlow(SessionState())
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.playing = isPlaying
                if (isPlaying && !ui.started) {
                    ui.started = true
                    phase.value = SessionState(phase = Phase.Playing)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                ui.paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) {
                    keep(exo.duration, exo.duration) // finished: out of Continue watching
                    ui.started = false
                    ended()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        exo.addListener(listener)
        val frameRate = window?.let { FrameRate(it, exo, phase, scope) }
        session.play(Place(from), load = { graph.films.load(title) }, fallback = fallback)
        onDispose {
            save()
            session.stop()
            // Marks, pins and trials made while it played: the title page's Plays line follows them.
            graph.films.rerank(title)
            frameRate?.detach()
            audio.release()
            subtitles.release()
            film.release()
            exo.removeListener(listener)
            exo.release()
            meter.finish().forEach { (bucket, mbps) -> Log.i("IptvCapacity", "${bucket.first} ${bucket.second}: $mbps Mbps") }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            meter.next(System.currentTimeMillis(), load.bytes.get(), load.filling(exo), session.state.value.copy?.source ?: Capacity.Source.Iptv)
            delay(1_000)
        }
    }
    // Every copy failed: the film isn't available.
    LaunchedEffect(Unit) {
        session.state.collect {
            if (it.phase == FilmPhase.Unavailable && !ui.error) {
                save()
                ui.error = true
            }
        }
    }
    // A new network or VPN is a new IP: a Real-Debrid link is tied to the old one (KTD2).
    LaunchedEffect(Unit) { graph.route.effective.drop(1).collect { session.ipChanged() } }
    // Home: keep the place and let go of the account's one connection; back in the app, it's the movies.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        save()
        session.stop()
        close()
    }
    LaunchedEffect(ui.started) {
        if (!ui.started || !films) return@LaunchedEffect
        delay(FILM_READING_MS) // past the first frames, so the formats have settled
        // Only an IPTV copy is the provider's film the box keeps readings for.
        val id = session.state.value.copy?.takeIf { it.source == Capacity.Source.Iptv }?.id?.removePrefix("iptv:")?.toIntOrNull() ?: return@LaunchedEffect
        filmReading(exo, fileBytes.get())?.let { graph.sync.reportFilmQuality(id, it) }
    }
    LaunchedEffect(ui.playing) {
        while (ui.playing) {
            delay(SAVE_EVERY_MS)
            save()
        }
    }

    var panel by remember { mutableStateOf(false) }
    LaunchedEffect(panel) { session.panel(panel) }
    var kick by remember { mutableStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<Long?>(null) }
    val position = remember { mutableLongStateOf(from) }
    val duration = remember { mutableLongStateOf(0L) }
    val buffered = remember { mutableLongStateOf(0L) }
    var quality by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(if (from > 0) "Resuming from ${playTime(from)}" else null) }
    LaunchedEffect(kick, ui.playing, target, panel) {
        controls = true
        if (ui.playing && target == null && !panel) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(ui.playing) {
        if (!ui.playing) return@LaunchedEffect
        delay(NOTE_MS)
        note = null
    }
    // While the controls show, the bar follows the player; elapsed and remaining recompose once a second.
    LaunchedEffect(controls) {
        while (controls) {
            position.longValue = exo.currentPosition
            duration.longValue = exo.duration.coerceAtLeast(0)
            buffered.longValue = exo.bufferedPosition
            // The copy playing, by its source, then what the decoders really show (R29).
            quality = listOfNotNull(session.state.value.copy?.let { sourceName(it.source) }, qualityLine(exo.videoFormat, exo.audioFormat).ifEmpty { null }).joinToString(" · ")
            delay(250)
        }
    }

    val commit = remember { arrayOfNulls<Job>(1) }
    val lastStep = remember { longArrayOf(0) }
    fun seekNow() {
        commit[0]?.cancel()
        val to = target ?: return
        exo.seekTo(to)
        position.longValue = to
        target = null
    }
    fun seekBy(delta: Long) {
        val dur = exo.duration
        val base = target ?: exo.currentPosition
        target = (base + delta).coerceIn(0, if (dur > 0) (dur - 1_000).coerceAtLeast(0) else Long.MAX_VALUE)
        commit[0]?.cancel()
        commit[0] = scope.launch {
            delay(SEEK_COMMIT_MS)
            seekNow()
        }
    }

    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(panel, ui.error) {
        if (panel || ui.error) return@LaunchedEffect
        repeat(30) {
            withFrameNanos {}
            if (focused) return@LaunchedEffect
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { e ->
                val native = e.nativeKeyEvent
                val code = native.keyCode
                val down = e.type == KeyEventType.KeyDown
                if (code == AndroidKeyEvent.KEYCODE_BACK && ui.error) {
                    if (down) close()
                    return@onKeyEvent true
                }
                if (panel) {
                    // Back and Menu close it (its own Audio page handles Back first); arrows move inside it.
                    if (code != AndroidKeyEvent.KEYCODE_BACK && code != AndroidKeyEvent.KEYCODE_MENU) return@onKeyEvent false
                    if (down) panel = false
                    return@onKeyEvent true
                }
                if (ui.error) return@onKeyEvent false
                when (code) {
                    // As in other streaming apps, Back peels a layer: a seek not yet made, then the controls, then the film.
                    AndroidKeyEvent.KEYCODE_BACK -> {
                        if (down) when {
                            target != null -> {
                                commit[0]?.cancel()
                                target = null
                            }
                            // A trial's first 30 s go back to the last copy; else a notice is dismissed (KTD11).
                            session.back() -> {}
                            controls -> controls = false
                            else -> {
                                save()
                                close()
                            }
                        }
                        return@onKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MENU -> if (down) panel = true
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (down && native.repeatCount == 0) {
                            if (target != null) seekNow()
                            else if (code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY) session.resume()
                            else if (code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE || exo.playWhenReady) {
                                session.pause()
                                save()
                            } else session.resume()
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                        val repeat = native.repeatCount > 0
                        if (!repeat || native.eventTime - lastStep[0] >= REPEAT_MS) {
                            lastStep[0] = native.eventTime
                            val step = if (native.eventTime - native.downTime >= HOLD_MS) HELD_STEP_MS else STEP_MS
                            seekBy(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                        }
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seekBy(-JUMP_MS)
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seekBy(JUMP_MS)
                    // Up takes the offer showing (▲), unused otherwise.
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (down && native.repeatCount == 0) session.accept()
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {}
                    else -> return@onKeyEvent false
                }
                if (down) kick++
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focus)
            .focusable(),
    ) {
        AndroidView(
            factory = { SurfaceView(it).apply { keepScreenOn = true }.also(exo::setVideoSurfaceView) },
            modifier = Modifier.align(Alignment.Center).then(if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()),
        )
        Subtitles(cues)

        var spinner by remember { mutableStateOf(false) }
        LaunchedEffect(ui.buffering, ui.error) {
            spinner = false
            if (ui.buffering && !ui.error) {
                delay(BUFFERING_SHOWN_MS)
                spinner = true
            }
        }
        val spin by animateFloatAsState(if (spinner) 1f else 0f, Motion.panel(), label = "buffering")
        if (spin > 0f) ActivityLine(Modifier.align(Alignment.Center).graphicsLayer { alpha = spin })

        AnimatedVisibility(
            visible = controls && !ui.error,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shown = reveal()
            Box(Modifier.fillMaxWidth().bottomScrim { shown.value }.padding(top = Space.xxxl * 2)) {
                Controls(
                    movie, ui.paused, note, quality,
                    position = { target ?: position.longValue },
                    duration = { duration.longValue },
                    buffered = { buffered.longValue },
                    seeking = target != null,
                    modifier = Modifier.slideFade({ shown.value }, Edge.Bottom, reduced).padding(Space.safe),
                )
            }
        }

        AnimatedVisibility(
            visible = panel,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
        ) {
            val shown = reveal()
            val audioState by audio.state.collectAsState()
            val subtitleState by subtitles.state.collectAsState()
            val filmState by session.state.collectAsState()
            val prepared by remember(title) { graph.films.state(title) }.collectAsState()
            MoviePanel(
                audioState,
                subtitleState,
                version = filmState.copy?.let { copyLine(it, prepared.answer) },
                onSubtitle = { subtitles.select(it) },
                open = panel,
                onAudio = { audio.select(it) },
                onFromStart = {
                    panel = false
                    target = null
                    exo.seekTo(0)
                    session.resume()
                    position.longValue = 0
                },
                // Its place is kept here; leaving then saves nothing, so Continue watching names the next episode.
                onNext = onNext?.let { next -> { save(); ui.started = false; next() } },
                modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
            ) { first ->
                Versions(
                    title, VersionsData(filmState.ranking, filmState.rd, prepared.answer, filmState.copy?.id), first,
                    onChoose = {
                        panel = false
                        session.choose(it.id)
                    },
                    onChanged = {
                        session.rerank()
                        graph.films.rerank(title)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // The session's notices, one at a time in the corner; never over the controls' text or a panel.
        if (!panel && !ui.error) NoticeCorner(session.state, Modifier.align(Alignment.TopEnd).padding(Space.safe))

        if (ui.error) Unavailable(onBack = { close() })
    }
}

/** Title (with its badge), a passing [note], then elapsed, the bar and what's left; "Paused" while paused. */
@Composable
private fun Controls(
    movie: Movie,
    paused: Boolean,
    note: String?,
    quality: String,
    position: () -> Long,
    duration: () -> Long,
    buffered: () -> Long,
    seeking: Boolean,
    modifier: Modifier,
) {
    val elapsed by remember { derivedStateOf { playTime(position()) } }
    val left by remember { derivedStateOf { duration().takeIf { it > 0 }?.let { "−" + playTime((it - position()).coerceAtLeast(0)) } ?: "" } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            movie.title.badge?.let { Badges(remember(it) { listOf(it) }) }
            Text(movie.title.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            movie.title.year?.let { Text(it, style = Type.body) }
            Box(Modifier.weight(1f))
            AnimatedContent(
                targetState = paused,
                transitionSpec = { fadeIn(Motion.focus()) togetherWith fadeOut(Motion.focus()) },
                label = "paused",
            ) { p ->
                if (p) Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    PauseMark()
                    Text("Paused", style = Type.headline)
                }
            }
        }
        if (quality.isNotEmpty()) Text(quality, style = Type.body, maxLines = 1)
        if (note != null) Text(note, style = Type.body)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, style = Type.body.copy(color = Palette.textPrimary))
            SeekBar(position, duration, buffered, seeking, Modifier.weight(1f))
            Text(left, style = Type.body)
        }
    }
}

/** Played (accent), buffered ahead (faint) and the rest, with a thumb at the place; all read while drawing. */
@Composable
private fun SeekBar(position: () -> Long, duration: () -> Long, buffered: () -> Long, seeking: Boolean, modifier: Modifier) {
    val grow by animateFloatAsState(if (seeking) 1f else 0f, Motion.focus(), label = "thumb")
    Box(
        modifier.height(THUMB).drawBehind {
            val dur = duration().toFloat()
            val f = if (dur > 0) (position() / dur).coerceIn(0f, 1f) else 0f
            val b = if (dur > 0) (buffered() / dur).coerceIn(f, 1f) else 0f
            val h = BAR_HEIGHT.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Palette.track, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = r)
            drawRoundRect(Palette.track, topLeft = Offset(0f, top), size = Size(size.width * b, h), cornerRadius = r)
            drawRoundRect(Palette.accent, topLeft = Offset(0f, top), size = Size(size.width * f, h), cornerRadius = r)
            val thumb = (THUMB.toPx() * (0.6f + 0.4f * grow)) / 2
            drawCircle(Palette.textPrimary, radius = thumb, center = Offset(size.width * f, size.height / 2))
        },
    )
}

@Composable
private fun PauseMark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) { Box(Modifier.size(5.dp, 18.dp).background(Palette.textPrimary, androidx.compose.foundation.shape.RoundedCornerShape(1.dp))) }
    }
}

private enum class PanelPage { Main, Version, Audio, Subtitles }

/**
 * Menu while a movie plays: the version playing ([versions], R11), audio track (when there is a choice),
 * subtitles (when there are any), a show's Next episode and Play from beginning.
 */
@Composable
private fun MoviePanel(
    audio: AudioState, subtitles: SubtitleState, version: String?, onSubtitle: (SubtitleOption?) -> Unit, open: Boolean,
    onAudio: (com.mohamed3on.iptv.player.AudioOption) -> Unit, onFromStart: () -> Unit, onNext: (() -> Unit)?, modifier: Modifier,
    versions: @Composable ColumnScope.(first: FocusRequester) -> Unit,
) {
    val stay by rememberUpdatedState(open)
    var page by remember { mutableStateOf(PanelPage.Main) }
    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (page == PanelPage.Main || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) page = PanelPage.Main
                true
            }
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            when (page) {
                PanelPage.Main -> "Options"
                PanelPage.Version -> "Versions"
                else -> page.name
            },
            style = Type.title, modifier = Modifier.padding(start = Space.l, bottom = Space.l),
        )
        val first = remember(page) { FocusRequester() }
        LaunchedEffect(page) {
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
        when (page) {
            PanelPage.Main -> {
                // Focus starts on the first row there is.
                var firstTaken = false
                fun take() = (if (firstTaken) null else first).also { firstTaken = true }
                PanelRow("Version", version ?: "Finding…", focus = take()) { page = PanelPage.Version }
                if (audio.options.size > 1) PanelRow("Audio", audio.selected?.let(::languageName), focus = take()) { page = PanelPage.Audio }
                if (subtitles.options.isNotEmpty()) PanelRow("Subtitles", subtitles.selected?.let(::subtitleName) ?: "Off", focus = take()) { page = PanelPage.Subtitles }
                if (onNext != null) PanelRow("Next episode", focus = take(), onClick = onNext)
                PanelRow("Play from beginning", focus = take(), onClick = onFromStart)
            }
            PanelPage.Version -> versions(first)
            PanelPage.Audio -> audio.options.forEachIndexed { i, option ->
                val selected = option == audio.selected || (audio.selected == null && i == 0)
                PanelRow(audioLabel(option), checked = option == audio.selected, focus = if (selected) first else null) { onAudio(option) }
            }
            PanelPage.Subtitles -> {
                PanelRow("Off", checked = subtitles.selected == null, focus = if (subtitles.selected == null) first else null) { onSubtitle(null) }
                subtitles.options.forEach { option ->
                    PanelRow(subtitleName(option), checked = option == subtitles.selected, focus = if (option == subtitles.selected) first else null) { onSubtitle(option) }
                }
            }
        }
    }
}

private const val FILM_READING_MS = 5_000L

@Composable
private fun Unavailable(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = CARD_WIDTH).material().padding(Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val shows = LocalLibrary.current == LibraryKind.Shows
            Text(if (shows) "This episode isn’t available right now" else "This movie isn’t available right now", style = Type.title)
            Text("No copy of it would play. Try another, or this one again later.", style = Type.body)
            val button = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos {}
                runCatching { button.requestFocus() }
            }
            ActionButton(if (shows) "Back to shows" else "Back to movies", onBack, Modifier.padding(top = Space.m).focusRequester(button))
        }
    }
}

/** "1:02:13", "42:10". */
internal fun playTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "English", "English · SDH"; the file's own label when it has no language. */
private fun subtitleName(option: SubtitleOption): String {
    val language = option.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { it } }
    return listOfNotNull(language, option.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }).joinToString(" · ").ifEmpty { "Unknown language" }
}

/** The film's subtitles over the picture: text cues low and centred, picture cues (PGS) where the file puts them. */
@Composable
private fun Subtitles(cues: List<Cue>) {
    if (cues.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val text = cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
        for (cue in cues) {
            val bitmap = cue.bitmap ?: continue
            val w = maxWidth * (if (cue.size != Cue.DIMEN_UNSET) cue.size else 0.5f)
            val h = if (cue.bitmapHeight != Cue.DIMEN_UNSET) maxHeight * cue.bitmapHeight else w * bitmap.height / bitmap.width
            val x = maxWidth * (if (cue.position != Cue.DIMEN_UNSET) cue.position else 0.5f) - when (cue.positionAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> w / 2
                Cue.ANCHOR_TYPE_END -> w
                else -> 0.dp
            }
            val y = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) maxHeight * cue.line - when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> h / 2
                Cue.ANCHOR_TYPE_END -> h
                else -> 0.dp
            } else maxHeight - h - SUBTITLE_BOTTOM
            androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.offset(x, y).size(w, h))
        }
        if (text.isNotEmpty()) Text(
            text.joinToString("\n"),
            style = Type.title.copy(color = Color.White, textAlign = TextAlign.Center, shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = Space.xxxl, end = Space.xxxl, bottom = SUBTITLE_BOTTOM),
        )
    }
}

private val SUBTITLE_BOTTOM = 56.dp

/**
 * The film session's notice in the top corner, where the health dot sits for live TV: an offer with
 * ▲ to take it, or a short word on what changed; during a trial, that Back goes back. Fades in and
 * out; never pauses or covers the picture's middle.
 */
@Composable
private fun NoticeCorner(state: StateFlow<FilmState>, modifier: Modifier) {
    val film by state.collectAsState()
    val now = film.notice?.let(::noticeText) ?: if (film.trial) NoticeText("Trying this version · Back returns to the last one") else null
    val shown = animateFloatAsState(if (now != null) 1f else 0f, Motion.panel(), label = "notice")
    val kept = remember { arrayOfNulls<NoticeText>(1) }
    if (now != null) kept[0] = now
    val n = kept[0] ?: return
    if (now == null && shown.value == 0f) return
    Row(
        modifier
            .widthIn(max = NOTICE_WIDTH)
            .graphicsLayer { alpha = shown.value }
            .material(Radius.panel)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(n.text, style = Type.caption.copy(color = Palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        n.action?.let {
            KeyCap("▲")
            Text(it, style = Type.caption.copy(color = Palette.textPrimary), maxLines = 1)
        }
    }
}

private class NoticeText(val text: String, val action: String? = null)

/** "Real-Debrid · 4K · HDR10": enough to tell copies apart in a line. */
private fun short(c: Copy) = (listOf(sourceName(c.source)) + copyLabels(c).take(2)).joinToString(" · ")

private fun noticeText(n: Notice) = when (n) {
    is Notice.Offer -> when (n.kind) {
        OfferKind.Better -> NoticeText("Better version ready: ${short(n.copy)}", "Switch")
        OfferKind.Untested -> NoticeText("Untested here: ${short(n.copy)}", "Try it")
        OfferKind.Lighter -> NoticeText("Stalling. Lighter version: ${short(n.copy)}", "Switch")
    }
    is Notice.SteppedDown -> NoticeText("Stalls: now on ${short(n.copy)} · Versions can reverse it")
    is Notice.PinCleared -> NoticeText("Your pinned version won’t play here, so it’s unpinned")
    is Notice.TrialResult -> NoticeText(if (n.works) "${short(n.copy)} works here" else "${short(n.copy)} didn’t play here · back to the last version")
}

private val NOTICE_WIDTH = 420.dp
