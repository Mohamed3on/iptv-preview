package com.mohamed3on.iptv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A film, show or episode whatever its copy or source (KTD10): `movie:<tmdb>`, `tv:<tmdb>`,
 * `tv:<tmdb>:s1e3`; `iptv:<vodId>`, `iptv:tv:<seriesId>` (and its episodes) without a TMDB id.
 */
typealias TitleId = String

/**
 * A title's place: [pos] and [dur] (its runtime) in ms ([dur] 0 when unknown), saved [at] (epoch ms),
 * with [name], [poster] and [year] so it shows with no catalogue (R21). For a show, [title] is the show,
 * [episode] the episode's title id and [label] its "S1 E3". [iptv] is the IPTV film (or show) last
 * played, which plays it until titles have copies of their own; its titleId is [title].
 */
@Serializable
data class Resume(
    val title: TitleId, val name: String, val poster: String = "", val year: String? = null,
    val pos: Long, val dur: Long, val at: Long, val episode: TitleId? = null, val label: String? = null, val iptv: Movie? = null,
)

/** A place in [movie] (an IPTV film, or for an episode its show), [at] 0 until a store stamps it. */
fun Movie.resume(pos: Long, dur: Long, episode: TitleId? = null, label: String? = null, at: Long = 0) =
    Resume(titleId, title.title, poster, title.year, pos, dur, at, episode, label, this)

/** `POST /api/app/title-ids`'s body: the provider ids marks were saved under. */
@Serializable
data class TitleIdRequest(val movies: List<Int>, val shows: List<Int>, val episodes: List<EpisodeRef>)

@Serializable
data class EpisodeRef(val series: Int, val id: Int)

/** Its answer: title ids by provider id (as a string). An episode is missing when its show could not be read. */
@Serializable
data class TitleIdMap(val movies: Map<String, TitleId> = emptyMap(), val shows: Map<String, TitleId> = emptyMap(), val episodes: Map<String, TitleId> = emptyMap())

/** A place as saved before title ids: by provider film or show id, the episode by its provider id. */
@Serializable
private class LegacyResume(val movie: Movie, val pos: Long, val dur: Long, val at: Long, val episode: Int? = null, val label: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Carries the marks saved under provider ids onto title ids, once. Films and shows in progress and
 * watched films go through the server ([run]); watched episodes were saved as bare ids with no show to
 * ask about, so each moves over when its show's episodes load ([adoptEpisodes]). The old keys are
 * never written, so an older APK still reads them; a failed lookup writes nothing and the next [run]
 * tries again. Writes merge by title (the newer place wins), so a retry never undoes a newer save.
 */
class TitleMigration(
    private val store: KeyValueStore,
    private val films: ResumeStore,
    private val shows: ResumeStore,
    private val watchedFilms: WatchedStore,
    private val watchedEpisodes: WatchedStore,
) {
    private val lock = Mutex()
    /** Old watched episode ids, read on the first show that loads (off the main thread). */
    private val legacyEpisodes by lazy { ids(store.get("shows.watched")) }

    /** True once everything is carried over (or there was nothing to carry); false to try again later. */
    suspend fun run(lookup: suspend (TitleIdRequest) -> TitleIdMap): Boolean = lock.withLock {
        if (store.get(DONE) == "1") return true
        val filmPlaces = legacy("movies.resume")
        val showPlaces = legacy("shows.resume")
        val filmWatched = ids(store.get("movies.watched"))
        val request = TitleIdRequest(
            movies = (filmPlaces.map { it.movie.id } + filmWatched).distinct(),
            shows = showPlaces.map { it.movie.id }.distinct(),
            episodes = showPlaces.mapNotNull { r -> r.episode?.let { EpisodeRef(r.movie.id, it) } }.distinct(),
        )
        if (request.movies.isEmpty() && request.shows.isEmpty()) return done()
        val map = try {
            lookup(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        // Every film and show maps (to iptv:… without TMDB); one that does not means a bad answer.
        if (request.movies.any { "$it" !in map.movies } || request.shows.any { "$it" !in map.shows }) return false
        fun moved(r: LegacyResume, title: TitleId, episode: TitleId?) =
            r.movie.copy(titleId = title).resume(r.pos, r.dur, episode, r.label, r.at)
        films.merge(filmPlaces.map { moved(it, map.movies.getValue("${it.movie.id}"), null) })
        // An episode whose show could not be read is retried; after a few tries its show keeps its place without it.
        val tries = (store.get(TRIES)?.toIntOrNull() ?: 0) + 1
        val unmapped = showPlaces.any { it.episode != null && "${it.episode}" !in map.episodes }
        shows.merge(showPlaces.mapNotNull { r ->
            val episode = r.episode?.let { map.episodes["$it"] }
            if (r.episode != null && episode == null && tries < MAX_TRIES) null
            else moved(r, map.shows.getValue("${r.movie.id}"), episode)
        })
        watchedFilms.markAll(filmWatched.map { map.movies.getValue("$it") })
        if (unmapped && tries < MAX_TRIES) {
            store.put(TRIES, "$tries")
            return false
        }
        return done()
    }

    /** Watched marks saved under these episodes' provider ids, now under their title ids. */
    fun adoptEpisodes(episodes: List<Episode>) {
        val seen = episodes.filter { it.id in legacyEpisodes && it.titleId.isNotEmpty() }
        if (seen.isNotEmpty()) watchedEpisodes.markAll(seen.map { it.titleId })
    }

    private fun done(): Boolean {
        store.put(DONE, "1")
        store.put(TRIES, null)
        return true
    }

    private fun legacy(key: String): List<LegacyResume> =
        store.get(key)?.let { runCatching { json.decodeFromString(ListSerializer(LegacyResume.serializer()), it) }.getOrNull() }.orEmpty()

    private fun ids(raw: String?): Set<Int> = raw?.split(',')?.mapNotNull(String::toIntOrNull)?.toHashSet().orEmpty()

    private companion object {
        const val DONE = "titles.migrated"
        const val TRIES = "titles.migrate.tries"
        const val MAX_TRIES = 3
    }
}
