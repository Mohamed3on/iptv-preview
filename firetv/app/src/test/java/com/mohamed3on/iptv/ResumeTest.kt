package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeTest {
    private val movie = Movie(id = 1, name = "EN - Film (2024)", group = "g")
    private val hour = 3_600_000L

    @Test
    fun resumesBetweenTheFirstMinuteAnd95Percent_andSurvivesARestart() {
        val store = MapStore()
        val resume = ResumeStore(store)
        resume.save(movie, 30_000, hour)
        assertEquals("the first minute starts over", 0L, resume.startAt(movie))
        resume.save(movie, 42 * 60_000L, hour)
        assertEquals(42 * 60_000L, ResumeStore(store).startAt(movie))
        resume.save(movie, (hour * 0.96).toLong(), hour)
        assertEquals("finished starts over and leaves Continue watching", 0L, resume.startAt(movie))
        assertEquals(0, resume.entries.value.size)
    }
}
