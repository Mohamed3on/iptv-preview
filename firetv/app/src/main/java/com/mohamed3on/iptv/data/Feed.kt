package com.mohamed3on.iptv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The provider's two fronts and the account, from the feed's `route`. */
@Serializable
data class ProviderRoute(val plain: String, val vpn: String, val user: String, val pass: String)

/** One row of `/api/app/channels`. 🏆 copies share [id], [tvgId], [family] and [rank] with the original. */
@Serializable
data class Channel(
    val id: Int,
    val name: String,
    val logo: String = "",
    val group: String,
    val tvgId: String = "",
    val slot: Boolean = false,
    /** The channel's own non-🏆 group; absent for 🏆-only channels. */
    val home: String? = null,
    val family: String,
    /** Position in the family, 0 = best feed. */
    val rank: Int,
    /** Probed video height, null when never measured. */
    val h: Int? = null,
    /** Name-marker quality tier, 0–5. */
    val q: Int = 0,
    val fixture: Boolean = false,
    val badges: List<String> = emptyList(),
) {
    val identity get() = ChannelIdentity(id, group)
}

@Serializable
data class Feed(val groups: List<String>, val route: ProviderRoute, val channels: List<Channel>) {
    private val byGroup by lazy { channels.groupBy { it.group } }
    private val byIdentity by lazy { channels.associateBy { it.identity } }
    private val byId by lazy { channels.groupBy { it.id } }
    private val byFamily by lazy { channels.groupBy { it.family } }

    /** Channels of [group] in server order (display and zap order). */
    fun channelsIn(group: String): List<Channel> = byGroup[group].orEmpty()

    fun find(identity: ChannelIdentity): Channel? = byIdentity[identity]

    /** Every row carrying [streamId]: the original and its 🏆 copies. */
    fun rowsOf(streamId: Int): List<Channel> = byId[streamId].orEmpty()

    /** The feeds of a family, best first, one row per stream (the home-group row over 🏆 copies). */
    fun familyMembers(family: String): List<Channel> =
        byFamily[family].orEmpty().sortedWith(compareBy({ it.rank }, { it.group != it.home })).distinctBy { it.id }
}

private val json = Json { ignoreUnknownKeys = true }

/** Parses and validates a channel feed; throws on anything that is not a feed with at least one channel. */
fun parseFeed(text: String): Feed {
    val feed = json.decodeFromString(Feed.serializer(), text)
    require(feed.channels.isNotEmpty()) { "feed has no channels" }
    return feed
}
