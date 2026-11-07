package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.Debrids
import com.mohamed3on.iptv.data.RdCandidate
import com.mohamed3on.iptv.data.RdCheck
import com.mohamed3on.iptv.data.RdStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridsTest {
    private class Fake(override val name: String, val answers: Map<RdCandidate, RdCheck>, state: RdStatus = RdStatus.Ok) : Debrid {
        override val status = MutableStateFlow(state)
        var asked = 0
        override suspend fun inAccount(candidates: List<RdCandidate>) = emptyMap<RdCandidate, RdCheck>()
        override suspend fun probe(c: RdCandidate, wait: Boolean): RdCheck? = null
        override suspend fun check(candidates: List<RdCandidate>) = answers.also { asked++ }
        override suspend fun link(ready: RdCheck.Ready) = "$name:${ready.link}"
    }

    private val best = RdCandidate("AAA", 0, "Best.mkv")
    private val next = RdCandidate("BBB", 0, "Next.mkv")
    private val other = RdCandidate("CCC", 0, "Other.mkv")

    @Test fun `the best copy plays from whichever service holds it, and a copy is not ready only when both say so`() = runTest {
        val tb = Fake("TorBox", mapOf(best to RdCheck.NotCached, next to RdCheck.Ready("1", "9"), other to RdCheck.NotCached))
        val rd = Fake("Real-Debrid", mapOf(best to RdCheck.Ready("T", "https://rd/l")))
        val both = Debrids(tb, rd, backgroundScope)
        val answers = both.check(listOf(best, next, other))
        assertEquals(RdCheck.Ready("T", "https://rd/l"), answers[best])
        assertEquals(RdCheck.Ready("1", "9"), answers[next])
        assertEquals(null, answers[other]) // Real-Debrid didn't say: unknown, not "not ready"
        assertEquals("Real-Debrid:https://rd/l", both.link(answers[best] as RdCheck.Ready))
        assertEquals("TorBox:9", both.link(answers[next] as RdCheck.Ready))
    }

    @Test fun `a lapsed service is not asked, the other one serves alone`() = runTest {
        val tb = Fake("TorBox", emptyMap(), RdStatus.NotPremium)
        val rd = Fake("Real-Debrid", mapOf(best to RdCheck.Ready("T", "https://rd/l")))
        val both = Debrids(tb, rd, backgroundScope)
        assertEquals(RdCheck.Ready("T", "https://rd/l"), both.check(listOf(best))[best])
        assertEquals(0, tb.asked)
        assertEquals("Real-Debrid", both.name)
    }
}
