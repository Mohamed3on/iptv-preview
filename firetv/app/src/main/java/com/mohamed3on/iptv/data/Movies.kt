package com.mohamed3on.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** One row of `/api/app/movies`: a 4K film or a UFC PPV replay. */
@Serializable
data class Movie(val id: Int, val name: String, val poster: String = "", val group: String, val ext: String = "mkv") {
    /** The name as shown, cleaned once: rows ask for it on every focus move. */
    val title by lazy { movieTitle(name) }
}

/** A movie's name as shown: [title] without the provider's [badge] prefix ("EN", "HDR") and the [year] pulled out. */
data class MovieTitle(val title: String, val year: String?, val badge: String?)

/** A movie group's name as shown ("🎬 NETFLIX MOVIES 4K" → "Netflix movies"), its language prefix as a [badge]. */
data class GroupLabel(val name: String, val badge: String?)

@Serializable
data class Movies(val groups: List<String>, val movies: List<Movie>) {
    private val byGroup by lazy { movies.groupBy { it.group } }
    private val byId by lazy { movies.associateBy { it.id } }

    /** The groups with any movies, in server order, each with its movies in server order. */
    val rows: List<Pair<String, List<Movie>>> by lazy { groups.mapNotNull { g -> byGroup[g]?.takeIf { it.isNotEmpty() }?.let { g to it } } }

    fun find(id: Int): Movie? = byId[id]

    /** Each title once (a film listed in two groups), in search form, built on the first search. */
    private val searchIndex by lazy {
        movies.distinctBy { it.title.title.lowercase() to it.title.year }.map { searchNorm(it.title.title) to it }
    }

    /** Titles containing every typed word, in server order, at most [SEARCH_CAP]. */
    fun search(query: String): List<Movie> {
        val words = searchNorm(query).trim().split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return searchIndex.asSequence().filter { (name, _) -> words.all(name::contains) }.map { it.second }.take(SEARCH_CAP).toList()
    }
}

private const val SEARCH_CAP = 120

private val json = Json { ignoreUnknownKeys = true }

/** Parses and validates the movie list; throws on anything that is not one with at least one movie. */
fun parseMovies(text: String): Movies {
    val movies = json.decodeFromString(Movies.serializer(), text)
    require(movies.movies.isNotEmpty()) { "no movies" }
    return movies
}

/** Accents stripped, lowercased ("Pokémon" → "pokemon"). */
private fun searchNorm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

private val SPACES = Regex("\\s+")
private val TITLE_PREFIX = Regex("""^([A-Z][A-Z+]{1,2})\s+-\s+""")
private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")

/**
 * "EN - UFC 328: Chimaev vs Strickland (2026)" → "UFC 328: Chimaev vs Strickland", 2026, badge "EN".
 * Anything after the year ("(Multi Audio)", "(4k)", a second title) goes with it.
 */
fun movieTitle(raw: String): MovieTitle {
    var s = raw.trim()
    val prefix = TITLE_PREFIX.find(s)
    if (prefix != null) s = s.substring(prefix.range.last + 1)
    val year = YEAR.find(s)
    if (year != null && year.range.first > 0) s = s.substring(0, year.range.first)
    val title = s.replace(SPACES, " ").trim().ifEmpty { raw.trim() }
    return MovieTitle(title, year?.groupValues?.get(1), prefix?.groupValues?.get(1))
}

private val GROUP_LANGUAGE = Regex("""^([A-Z]{2})\s+-\s+""")
private val GROUP_NOISE = Regex("""\s*\(VOD\)|\s+4K$""")
/** Words kept as their makers spell them; everything else reads in sentence case. */
private val BRANDS = listOf("UFC", "PPV", "Netflix", "Amazon", "Disney+", "Apple+", "Marvel", "OSN+", "HDR").associateBy { it.uppercase() }
private val groupLabels = java.util.concurrent.ConcurrentHashMap<String, GroupLabel>()

fun groupLabel(raw: String): GroupLabel = groupLabels.getOrPut(raw) {
    var s = raw.removePrefix("🎬").replace(GROUP_NOISE, "").trim()
    val language = GROUP_LANGUAGE.find(s)
    if (language != null) s = s.substring(language.range.last + 1)
    val words = s.split(SPACES).filter(String::isNotEmpty).mapIndexed { i, w ->
        BRANDS[w.uppercase()] ?: w.replace('İ', 'I').lowercase(Locale.ROOT).let { if (i == 0) it.replaceFirstChar(Char::uppercase) else it }
    }
    GroupLabel(words.joinToString(" ").ifEmpty { raw }, language?.groupValues?.get(1))
}

private val TMDB_SIZE = Regex("""^https?://(?:image\.tmdb\.org|www\.themoviedb\.org)/t/p/[^/]+/""")

/** TMDB posters at the size a card needs (w342) rather than 600×900 or the original, a tenth of the bytes. */
fun posterUrl(raw: String): String = raw.replace(TMDB_SIZE, "https://image.tmdb.org/t/p/w342/")

/** A movie's place: [pos] and [dur] in ms ([dur] 0 when unknown), saved [at] (epoch ms). Carries the movie, in case the list drops it. */
@Serializable
data class Resume(val movie: Movie, val pos: Long, val dur: Long, val at: Long)

private const val MIN_RESUME_MS = 60_000L
private const val FINISHED = 0.95

/** Where OK starts a movie with this saved place: there, if past the first minute and short of 95%; else the beginning. */
fun resumeAt(pos: Long, dur: Long): Long = if (pos > MIN_RESUME_MS && (dur <= 0 || pos < dur * FINISHED)) pos else 0L

/**
 * Saved places, newest first, at most [CAP], persisted as one JSON value. Only resumable ones are
 * kept: a movie saved before its first minute or past 95% (finished) drops out of Continue watching.
 */
class ResumeStore(private val store: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    private val serializer = ListSerializer(Resume.serializer())
    private val _entries = MutableStateFlow(
        store.get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    )
    val entries: StateFlow<List<Resume>> = _entries

    fun startAt(movie: Movie): Long = _entries.value.firstOrNull { it.movie.id == movie.id }?.let { resumeAt(it.pos, it.dur) } ?: 0L

    fun save(movie: Movie, pos: Long, dur: Long) {
        val rest = _entries.value.filter { it.movie.id != movie.id }
        val next = if (resumeAt(pos, dur) > 0) (listOf(Resume(movie, pos, dur, now())) + rest).take(CAP) else rest
        if (next == _entries.value) return
        _entries.value = next
        store.put(KEY, json.encodeToString(serializer, next))
    }

    private companion object {
        const val KEY = "movies.resume"
        const val CAP = 50
    }
}
