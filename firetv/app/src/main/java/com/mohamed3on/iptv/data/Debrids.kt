package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** The debrid films play from, for labels: the one that serves, or "Debrid" while both do. [Debrids] keeps it current. */
@Volatile var debridName = "TorBox"
    private set

/**
 * Two debrids, whichever has the copy: a check asks every one that can serve (not without a key, refused, denied
 * or lapsed) at once and takes a ready copy from either, so the best-ranked copy plays whoever holds it and one
 * going down leaves the other playing. The best copy ready on [first] needs no other answer; a slow service gets
 * [ASK_MS]. A copy is not ready only once every service says so. A ready copy is linked by the service that found
 * it. [status] is [first]'s while it serves, else [second]'s: "premium ended" only shows once both are down.
 */
class Debrids(private val first: Debrid, private val second: Debrid, scope: CoroutineScope) : Debrid {
    private val owners = ConcurrentHashMap<RdCheck.Ready, Debrid>()
    private val serving get() = listOf(first, second).filter(::usable).ifEmpty { listOf(first) }

    override val name get() = serving.singleOrNull()?.name ?: "Debrid"
    override val status: StateFlow<RdStatus> = combine(first.status, second.status) { _, _ -> debridName = name; serving.first().status.value }
        .stateIn(scope, SharingStarted.Eagerly, RdStatus.Ok)

    override suspend fun check(candidates: List<RdCandidate>): Map<RdCandidate, RdCheck> = coroutineScope {
        val asked = serving
        val asks = asked.map { d -> d to async { withTimeoutOrNull(ASK_MS) { runCatching { d.check(candidates) }.getOrNull() } } }
        val answers = mutableListOf<Pair<Debrid, Map<RdCandidate, RdCheck>>>()
        for ((d, ask) in asks) {
            val a = ask.await() ?: continue
            answers += d to a
            if (candidates.firstOrNull()?.let { a[it] } is RdCheck.Ready) break
        }
        asks.forEach { it.second.cancel() }
        candidates.mapNotNull { c ->
            answers.firstNotNullOfOrNull { (d, a) -> (a[c] as? RdCheck.Ready)?.also { owners[it] = d } }?.let { return@mapNotNull c to it }
            val said = answers.mapNotNull { it.second[c] }
            if (said.size == asked.size) c to said.first() else null
        }.toMap()
    }

    override suspend fun inAccount(candidates: List<RdCandidate>) = serving.first().let { d -> d.inAccount(candidates).onEach { (_, r) -> if (r is RdCheck.Ready) owners[r] = d } }

    override suspend fun probe(c: RdCandidate, wait: Boolean) = serving.first().let { d -> d.probe(c, wait).also { if (it is RdCheck.Ready) owners[it] = d } }

    override suspend fun link(ready: RdCheck.Ready) = (owners[ready] ?: serving.first()).link(ready)

    private companion object {
        /** Under the film session's own 4 s check budget, so a slow service never costs the other's answer. */
        const val ASK_MS = 3_500L
        val DOWN = setOf(RdStatus.NoToken, RdStatus.TokenInvalid, RdStatus.Denied, RdStatus.NotPremium)
        fun usable(d: Debrid) = d.status.value !in DOWN
    }
}
