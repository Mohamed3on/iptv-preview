package com.mohamed3on.iptv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A channel as the user sees it: 🏆 copies share a stream id, so the group is part of who it is (KTD5). */
data class ChannelIdentity(val streamId: Int, val group: String)

/** A watched channel as persisted: its identity plus its family, kept for when the stream id disappears. */
@Serializable
data class Watched(val streamId: Int, val group: String, val family: String) {
    val identity get() = ChannelIdentity(streamId, group)

    companion object {
        fun of(channel: Channel) = Watched(channel.id, channel.group, channel.family)
    }
}

/** What launch does: play a channel, or open the channel list at a group with nothing playing. */
sealed interface StartAction {
    data class Play(val channel: Channel) : StartAction
    data class OpenList(val group: String) : StartAction
}

/**
 * Finds [watched] in a (possibly newer) feed, per KTD5: the same (streamId, group); else the same
 * stream in its home group; else the best feed of its family; else null.
 */
fun Feed.resolve(watched: Watched): Channel? =
    find(watched.identity)
        ?: rowsOf(watched.streamId).firstOrNull { it.group == it.home }
        ?: familyMembers(watched.family).firstOrNull()

/** Launch plays the last channel when it still resolves, else opens the list at the first group (R9). */
fun Feed.startAction(last: Watched?): StartAction =
    last?.let(::resolve)?.let(StartAction::Play) ?: StartAction.OpenList(channels.first().group)

/** Last and previous channel, and the few watched before them, persisted (KTD5). */
class WatchHistory(private val store: KeyValueStore) {
    var last: Watched? = read(LAST)
        private set
    var previous: Watched? = read(PREVIOUS)
        private set
    /** The last [RECENT_CAP] distinct channels watched (by identity), newest first. */
    var recent: List<Watched> = store.get(RECENT)?.let { runCatching { json.decodeFromString(recentSerializer, it) }.getOrNull() }
        ?: listOfNotNull(last, previous)
        private set

    /** Records that [channel] started playing; the channel it replaced becomes the previous one. */
    fun watched(channel: Channel) {
        val now = Watched.of(channel)
        if (last?.identity != now.identity) {
            previous = last
            store.put(PREVIOUS, previous?.let { json.encodeToString(Watched.serializer(), it) })
        }
        last = now
        store.put(LAST, json.encodeToString(Watched.serializer(), now))
        if (recent.firstOrNull() != now) {
            recent = (listOf(now) + recent.filter { it.identity != now.identity }).take(RECENT_CAP)
            store.put(RECENT, json.encodeToString(recentSerializer, recent))
        }
    }

    private fun read(key: String) =
        store.get(key)?.let { runCatching { json.decodeFromString(Watched.serializer(), it) }.getOrNull() }

    private companion object {
        const val LAST = "watch.last"
        const val PREVIOUS = "watch.previous"
        const val RECENT = "watch.recent"
        const val RECENT_CAP = 10
        val json = Json
        val recentSerializer = ListSerializer(Watched.serializer())
    }
}
