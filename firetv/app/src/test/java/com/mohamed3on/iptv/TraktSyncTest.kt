package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.TraktSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TraktSyncTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MapStore()

    @Before fun setUp() = server.start()
    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun trakt() = TraktSync(OkHttpClient(), server.url("").toString().trimEnd('/'), "tok", store, scope) { 1_762_372_800_000 }
    private fun reply(code: Int) = server.enqueue(MockResponse.Builder().code(code).build())
    private fun sent() = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.map { it.body?.utf8() }.toList()

    @Test fun `passing 90 percent sends one finished play, and passing it again in the same play sends nothing`() = runBlocking {
        val trakt = trakt()
        reply(204)
        val play = trakt.play("movie:603")
        play(50_000, 100_000)
        play(91_000, 100_000)
        play(40_000, 100_000) // seeked back
        play(95_000, 100_000)
        trakt.flush()
        assertEquals(listOf("""{"title":"movie:603","at":1762372800000}"""), sent())
        assertNull(store.get("trakt.finished"))
    }

    @Test fun `plays finished offline are kept and sent later`() = runBlocking {
        reply(503) // Trakt not signed in on the box, or the box out of reach
        trakt().finished("tv:1396:s1e2")
        assertEquals("/api/app/trakt-history?t=tok", server.takeRequest(5, TimeUnit.SECONDS)?.target)
        reply(204)
        trakt().flush() // a later run, from what was kept
        assertEquals(listOf("""{"title":"tv:1396:s1e2","at":1762372800000}"""), sent())
        assertNull(store.get("trakt.finished"))
    }
}
