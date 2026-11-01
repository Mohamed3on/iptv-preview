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

private val FEED_PREFIX = Regex("""^[^\s:|]{1,6}[:|]\s*""")
private const val SUP_FROM = "ᴬᴮᶜᴰᴱᶠᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿˢᵀᵁⱽᵂᵃᵇᵈᵉᵍʰⁱʲᵏˡᵐⁿᵒᵖʳᵗᵘᵛʷˣʸᶻ⁰¹²³⁴⁵⁶⁷⁸⁹"
private const val SUP_TO = "ABCDEFGHIJKLMNOPRSTUVWabdeghijklmnoprtuvwxyz0123456789"
/** Words that only tell feeds of one channel apart (quality, codec, fps, uplink); the badges say it better. */
private val FEED_WORDS = setOf(
    "8k", "4k", "hd", "sd", "fhd", "uhd", "hdr", "raw", "vip", "hevc", "fps", "50", "60",
    "3840p", "2160p", "1080p", "720p", "(sat)", "(mobil)", "(mobile)", "(bk)", "sat",
)
/** All-caps words of 4+ letters are re-cased ("SPORTS" → "Sports"), except these acronyms. */
private val ACRONYMS = setOf("DAZN", "ESPN", "UEFA", "FIFA", "MUTV", "LFCTV", "NBCSN")
/** Short all-caps words that are words, not acronyms. */
private val SHORT_WORDS = setOf("SKY", "ONE", "TWO", "TEN", "MAX", "MIX", "THE", "AND")

/**
 * A channel name as people say it: the provider's feed prefix and quality markers dropped
 * ("SS: beIN SP⚽RTS 6 4K" → "beIN Sports 6"), shouting words re-cased, acronyms kept.
 * Event slots, whose name is the event, keep their wording.
 */
fun displayName(raw: String): String {
    val unsuper = { w: String -> w.map { c -> SUP_FROM.indexOf(c).let { if (it >= 0) SUP_TO[it] else c } }.joinToString("") }
    val words = raw.replace(FEED_PREFIX, "").split(Regex("\\s+")).mapNotNull { token ->
        // a word spelled in superscript ("ᴬʳᵉⁿᵃ") arrives as "Arena"; markers ("ᴿᴬᵂ", "³⁸⁴⁰ᴾ") are dropped below
        val spelled = token.isNotEmpty() && token.all { it in SUP_FROM }
        val w = unsuper(token).replace(Regex("(?<=[A-Z])⚽|⚽(?=[A-Z])"), "O").replace("⚽", "o")
        val letters = w.filter { it.isLetter() }
        when {
            w.isEmpty() || w.lowercase() in FEED_WORDS || Regex("(?i)\\d+fps").matches(w) -> null
            letters.isNotEmpty() && letters.all { it in 'A'..'Z' } && w !in ACRONYMS && (letters.length >= 4 || w in SHORT_WORDS) ->
                w.lowercase().replaceFirstChar(Char::uppercase)
            spelled -> w.replaceFirstChar(Char::uppercase)
            else -> w
        }
    }
    val shown = words.joinToString(" ").trim()
    return if (shown.isEmpty()) raw.trim() else shown
}
