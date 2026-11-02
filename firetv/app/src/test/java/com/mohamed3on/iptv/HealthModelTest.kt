package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Health
import com.mohamed3on.iptv.player.HealthModel
import com.mohamed3on.iptv.player.HealthSample
import com.mohamed3on.iptv.player.StreamPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthModelTest {
    /** An 8 Mbps stream played second by second, [mediaPerSecond] ms of picture arriving each second. */
    private class Stream(val model: HealthModel) {
        var at = 0L
        var position = 0L
        var buffered = 3_000L
        var bytes = 0L

        fun play(seconds: Int, mediaPerSecond: Long = 1_000, buffering: Boolean = false, plain: Boolean = true): Health? {
            var last: Health? = null
            repeat(seconds) {
                at += 1_000
                bytes += mediaPerSecond * 1_000 // 8 Mbps = 1,000 bytes per ms of picture
                val played = if (buffering) 0L else minOf(1_000L, buffered + mediaPerSecond)
                buffered = (buffered + mediaPerSecond - played).coerceAtLeast(0)
                position += played
                last = model.next(HealthSample(at, StreamPhase.Playing, 1, buffering, position, buffered, bytes, spare = false, plainRoute = plain))
            }
            return last
        }
    }

    @Test
    fun `a stream that keeps up is good, and one whose line falls behind says by how much`() {
        val stream = Stream(HealthModel())
        assertEquals(Health.Level.Good, stream.play(12)?.level)

        val behind = stream.play(10, mediaPerSecond = 500)!!
        assertEquals(Health.Level.Warn, behind.level)
        assertEquals("Network behind the stream · network 4.0 of 8.0 Mbps · VPN may help", behind.reason)
    }

    @Test
    fun `buffering with nothing arriving blames the provider, and is remembered for a while`() {
        val rebuffers = mutableListOf<Float?>()
        val stream = Stream(HealthModel(onRebuffer = { _, s -> rebuffers += s }))
        stream.play(12)

        val stalled = stream.play(4, mediaPerSecond = 0, buffering = true)!!
        assertEquals(Health.Level.Bad, stalled.level)
        assertEquals("Buffering · no data from the provider", stalled.reason)
        assertEquals(listOf(8f), rebuffers)

        val after = stream.play(15)!!
        assertEquals(Health.Level.Warn, after.level)
        assertEquals("Buffered just now", after.reason)
    }
}
