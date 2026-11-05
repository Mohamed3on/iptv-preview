package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeTest {
    private val movie = Movie(id = 1, name = "EN - Film (2024)", group = "g", titleId = "movie:603")
    private val hour = 3_600_000L

    @Test
    fun resumesBetweenTheFirstMinuteAnd95Percent_andSurvivesARestart() {
        val store = MapStore()
        val resume = ResumeStore(store, "r")
        resume.save(movie, 30_000, hour)
        assertEquals("the first minute starts over", 0L, resume.startAt(movie.titleId))
        resume.save(movie, 42 * 60_000L, hour)
        assertEquals(42 * 60_000L, ResumeStore(store, "r").startAt(movie.titleId))
        resume.save(movie, (hour * 0.96).toLong(), hour)
        assertEquals("finished starts over and leaves Continue watching", 0L, resume.startAt(movie.titleId))
        assertEquals(0, resume.entries.value.size)
    }

    @Test
    fun thePlaceBelongsToTheTitle_whicheverCopyPlays() {
        val resume = ResumeStore(MapStore(), "r")
        resume.save(movie, 40 * 60_000L, 2 * hour)
        val otherCopy = Movie(id = 2, name = "DE - Film (2024)", group = "h", titleId = "movie:603")
        assertEquals(40 * 60_000L, resume.startAt(otherCopy.titleId))
        resume.save(otherCopy, 50 * 60_000L, 2 * hour)
        assertEquals("one entry per title", 1, resume.entries.value.size)
    }
}
