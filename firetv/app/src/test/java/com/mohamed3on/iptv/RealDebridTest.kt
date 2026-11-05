package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.RdCandidate
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.RdError
import com.mohamed3on.iptv.data.RdStatus
import com.mohamed3on.iptv.data.RealDebrid
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RealDebridTest {
    private val server = MockWebServer()
    // fileIdx 0 would be the sample (id 1); the file name picks the film (id 2).
    private val film = RdCandidate("ABC123", 0, "Film.mkv")

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun rd() = RealDebrid(OkHttpClient(), { "tok" }, server.url("/rest").toString())
    private fun response(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    private fun reply(code: Int, body: String = "") = server.enqueue(response(code, body))
    private fun info(status: String) {
        val done = status == "downloaded"
        reply(200, """{"status":"$status","files":[{"id":1,"path":"/Sample.mkv","bytes":1,"selected":0},
            {"id":2,"path":"/Film.mkv","bytes":9,"selected":${if (done) 1 else 0}}],"links":${if (done) """["https://real-debrid.com/d/L1"]""" else "[]"}}""")
    }
    private fun requests() = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
    private fun paths() = requests().map { "${it.method} ${it.url.encodedPath}" }

    @Test fun `a torrent already in the account skips addMagnet and links fresh`() = runTest {
        val rd = rd()
        reply(200, """[{"id":"T1","hash":"abc123","status":"downloaded"}]""")
        info("downloaded")
        val ready = rd.check(listOf(film))[film] as RdCheck.Ready
        reply(200, """{"download":"https://dl.example/film.mkv"}""")
        assertEquals("https://dl.example/film.mkv", rd.link(ready))
        assertEquals(listOf("GET /rest/torrents", "GET /rest/torrents/info/T1", "POST /rest/unrestrict/link"), paths())
    }

    @Test fun `a cached magnet is kept with its named file selected, an uncached one is deleted`() = runTest {
        reply(204)
        reply(201, """{"id":"N1"}""")
        info("waiting_files_selection")
        reply(204)
        info("downloaded")
        assertEquals(RdCheck.Ready("N1", "https://real-debrid.com/d/L1"), rd().check(listOf(film))[film])
        val sent = requests()
        assertEquals("files=2", sent[3].body?.utf8())
        assertEquals(5, sent.size) // no delete

        reply(204)
        reply(201, """{"id":"N2"}""")
        info("waiting_files_selection")
        reply(204)
        info("downloading")
        reply(204)
        assertEquals(RdCheck.NotCached, rd().check(listOf(film))[film])
        assertEquals("DELETE /rest/torrents/delete/N2", paths().last())
    }

    @Test fun `errors map to permanent or transient and set the source status`() = runTest {
        reply(204)
        reply(451, """{"error":"infringing_file","error_code":35}""")
        assertTrue((rd().check(listOf(film))[film] as RdCheck.Failed).error.permanent)

        var rd = rd()
        reply(204)
        reply(503, """{"error":"too_many_active_downloads","error_code":21}""")
        assertEquals(false, (rd.check(listOf(film))[film] as RdCheck.Failed).error.permanent)
        assertEquals(RdStatus.TooManyDownloads, rd.status.value)

        rd = rd()
        reply(401, """{"error":"bad_token","error_code":8}""")
        assertEquals(emptyMap<RdCandidate, RdCheck>(), rd.check(listOf(film)))
        assertEquals(RdStatus.TokenInvalid, rd.status.value)

        reply(503, """{"error":"unavailable_file","error_code":24}""")
        val e = runCatching { rd.link(RdCheck.Ready("T1", "https://real-debrid.com/d/L1")) }.exceptionOrNull() as RdError
        assertEquals(false, e.permanent)
    }

    @Test fun `a rate-limited addMagnet stops adding instead of hammering`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.url.encodedPath.endsWith("addMagnet")) response(429, """{"error":"too_many_requests","error_code":34}""") else response(204)
        }
        val rd = rd()
        val six = List(6) { RdCandidate("h$it", 0) }
        val first = rd.check(six)
        assertEquals(3, first.size)
        assertTrue(first.values.all { it is RdCheck.Failed && !it.error.permanent })
        assertEquals(RdStatus.RateLimited, rd.status.value)
        rd.check(six) // still within the pause: no adds at all
        assertEquals(3, paths().count { it.endsWith("addMagnet") })
    }
}
