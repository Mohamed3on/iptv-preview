package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.EpisodeRef
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.TitleIdMap
import com.mohamed3on.iptv.data.TitleIdRequest
import com.mohamed3on.iptv.data.TitleMigration
import com.mohamed3on.iptv.data.WatchedStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TitleMigrationTest {
    // As the previous build saved them, under provider ids.
    private val oldFilms = """[{"movie":{"id":2219248,"name":"EN - Film (2025)","poster":"p","group":"g"},"pos":2400000,"dur":7200000,"at":111}]"""
    private val oldShows = """[{"movie":{"id":77,"name":"Show","group":"s"},"pos":600000,"dur":3000000,"at":222,"episode":9001,"label":"S2 E4"}]"""
    private val old = mapOf("movies.resume" to oldFilms, "shows.resume" to oldShows, "movies.watched" to "55", "shows.watched" to "9001,9002")
    private val answer = TitleIdMap(
        movies = mapOf("2219248" to "movie:1378537", "55" to "movie:38"),
        shows = mapOf("77" to "tv:95396"),
        episodes = mapOf("9001" to "tv:95396:s2e4"),
    )

    private class Graph(val prefs: MapStore) {
        val films = ResumeStore(prefs, "titles.movies.resume", now = { 999 })
        val shows = ResumeStore(prefs, "titles.shows.resume", now = { 999 })
        val watchedFilms = WatchedStore(prefs, "titles.movies.watched")
        val watchedEpisodes = WatchedStore(prefs, "titles.shows.watched")
        val migration = TitleMigration(prefs, films, shows, watchedFilms, watchedEpisodes)
    }

    @Test
    fun carriesPlacesAndWatchedMarksOverToTitleIds_once() = runTest {
        val g = Graph(MapStore(old.toMutableMap()))
        var asked: TitleIdRequest? = null
        assertTrue(g.migration.run { asked = it; answer })
        assertEquals(listOf(2219248, 55), asked!!.movies)
        assertEquals(listOf(EpisodeRef(77, 9001)), asked!!.episodes)

        val film = g.films.find("movie:1378537")!!
        assertEquals(2_400_000L to 111L, film.pos to film.at)
        assertEquals("plays from its place on any copy", 2_400_000L, g.films.startAt("movie:1378537"))
        assertEquals("movie:1378537", film.iptv!!.titleId)
        assertEquals(2219248, film.iptv!!.id)
        assertEquals("tv:95396:s2e4", g.shows.find("tv:95396")!!.episode)
        assertEquals(setOf("movie:38"), g.watchedFilms.ids.value)
        old.forEach { (k, v) -> assertEquals("old keys stay for an older APK", v, g.prefs.map[k]) }

        // Episodes watched under bare ids move over as their show loads.
        g.migration.adoptEpisodes(listOf(Episode(id = 9001, titleId = "tv:95396:s2e4", season = 2, episode = 4)))
        assertEquals(setOf("tv:95396:s2e4"), g.watchedEpisodes.ids.value)

        assertTrue(g.migration.run { error("asked again") })
    }

    @Test
    fun aFailedLookupLeavesEverythingAsItWas_andTheNextLaunchRetries() = runTest {
        val prefs = MapStore(old.toMutableMap())
        val g = Graph(prefs)
        assertFalse(g.migration.run { throw IOException("offline") })
        assertTrue(g.films.entries.value.isEmpty())
        assertNull(prefs.map["titles.movies.resume"])
        old.forEach { (k, v) -> assertEquals(v, prefs.map[k]) }

        // Meanwhile the film plays on and its newer place must survive the retry.
        g.films.save(Movie(id = 1, name = "EN - Film (2025)", group = "g", titleId = "movie:1378537"), 3_000_000, 7_200_000)
        val next = Graph(prefs)
        assertTrue(next.migration.run { answer })
        assertEquals(3_000_000L, next.films.startAt("movie:1378537"))
        assertEquals(1, next.shows.entries.value.size)
    }

    @Test
    fun anEpisodeWhoseShowCouldNotBeReadIsRetried() = runTest {
        val g = Graph(MapStore(old.toMutableMap()))
        val noEpisodes = TitleIdMap(answer.movies, answer.shows)
        assertFalse(g.migration.run { noEpisodes })
        assertTrue("the show waits for its episode", g.shows.entries.value.isEmpty())
        assertTrue("films are carried over already", g.films.find("movie:1378537") != null)
        assertTrue(g.migration.run { answer })
        assertEquals("tv:95396:s2e4", g.shows.find("tv:95396")!!.episode)
    }
}
