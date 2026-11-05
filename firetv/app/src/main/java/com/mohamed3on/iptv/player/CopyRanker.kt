package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.KeyValueStore
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One copy of a film or episode, from IPTV or Real-Debrid, with what is known about it (KTD5). Codec
 * names are ffprobe's; an unknown is empty or 0 and gets the benefit of the doubt.
 */
data class Copy(
    /** Unique within the title and stable across plays: marks and pins key on it. */
    val id: String,
    val source: Source,
    /** Nominal lines: 2160, 1080, 720, 480 (a 3840x1600 scope film is 2160); 0 when unknown. */
    val resolution: Int = 0,
    /** "DV", "HDR10+", "HDR10", "HLG"; empty is SDR. A Dolby Vision copy also lists the layer it falls back to, when named. */
    val hdr: List<String> = emptyList(),
    /** The Dolby Vision profile (5, 7, 8) when known. */
    val dvProfile: Int? = null,
    /** "hevc", "h264", "av1", "vp9", "mpeg2video". */
    val codec: String = "",
    /** The main audio track: "eac3", "ac3", "aac", "truehd", "dts", "dtshd", "dtsx", ... */
    val audio: String = "",
    val channels: Int = 0,
    val atmos: Boolean = false,
    /** Audio languages (ISO 639-1, "multi" for MULTi); empty means the original only. */
    val languages: List<String> = emptyList(),
    /** Known to embed English subtitles (the IPTV probe or a release tag). */
    val englishSubs: Boolean = false,
    /** Average bitrate (size over runtime, or measured); 0 when unknown. */
    val mbps: Float = 0f,
)

/** KTD6's sort; the order is the ranking's. */
enum class Support { Works, Unsure, Wont }

/** A judgement on one copy for one setup (KTD7): it played, or it failed for good. Real-Debrid marks lapse. */
@Serializable
data class Mark(val works: Boolean, val at: Long, val expires: Long = Long.MAX_VALUE)

/** The owner's fixes to what the setup reports (R14), kept per setup fingerprint. */
@Serializable
enum class Correction { PassesTrueHd, PassesDts, IgnoreBandwidth }

/** One copy's place in a [Ranking]. */
class Ranked internal constructor(
    val copy: Copy,
    val support: Support,
    val mark: Mark?,
    /** In the title's original language (R2). */
    val original: Boolean,
    /** Within the typical bandwidth of its source (R4). */
    val fits: Boolean,
    val pinned: Boolean,
    internal val why: String,
    internal val typical: Float?,
    internal val dvMissing: Boolean,
    internal val hdr: Int,
    internal val bits: Float,
    internal val sound: Int,
    internal val subs: Boolean,
) {
    /** Why it ranks where it does, for Versions (R12): "Dolby Vision profile unknown", "needs ~75 Mbps, typical 40", "dub". */
    var reason = ""
        internal set
}

/** The copies best first (KTD15), and the few U9 acts on. */
class Ranking internal constructor(
    val copies: List<Ranked>,
    /** What Play uses; null when every copy is ruled out. */
    val pick: Ranked?,
    /** R28: the best untested copy that would beat a working pick, offered once. */
    val offer: Ranked?,
    /** R28: when the pick is untested, the working copy Back returns to. */
    val fallback: Ranked?,
    /** R10: the best working copy that only the bandwidth ruled out. */
    val heavier: Ranked?,
) {
    /** R9: the best-ranked working copy lighter than [current], for a step-down. */
    fun lighter(current: Copy): Ranked? = copies.firstOrNull {
        it.support == Support.Works && it.copy.id != current.id &&
            if (it.copy.mbps > 0f && current.mbps > 0f) it.copy.mbps < current.mbps else it.copy.resolution < current.resolution
    }
}

/**
 * Ranks a title's [copies] for [setup] (U6): language, then support, then bandwidth, picture, sound,
 * English subtitles for a foreign film, and Real-Debrid on a tie (KTD15); a [pin] beats all of it.
 * [marks] are this setup's, by copy id; [typicalMbps] is per source, null when unknown (optimistic).
 */
fun rankCopies(
    copies: List<Copy>,
    originalLanguage: String?,
    setup: SetupProfile,
    typicalMbps: (Source) -> Float?,
    marks: Map<String, Mark> = emptyMap(),
    corrections: Set<Correction> = emptySet(),
    pin: String? = null,
): Ranking {
    val p = setup.corrected(corrections)
    val ignore = Correction.IgnoreBandwidth in corrections
    val iptv = if (ignore) null else typicalMbps(Source.Iptv)
    val rd = if (ignore) null else typicalMbps(Source.RealDebrid)
    val ranked = copies.map { judge(it, p, marks[it.id], originalLanguage, if (it.source == Source.Iptv) iptv else rd, it.id == pin) }.sortedWith(ORDER)
    val pick = ranked.firstOrNull()?.takeIf { it.pinned || it.support != Support.Wont }
    for (r in ranked) r.reason = reason(r, pick)
    val working = pick?.takeIf { it.support == Support.Works && !it.pinned }
    return Ranking(
        ranked, pick,
        offer = working?.let { w -> ranked.firstOrNull { it.support == Support.Unsure && it.original == w.original && versus(it, w) < 0 } },
        fallback = pick?.takeIf { it.support == Support.Unsure }?.let { ranked.firstOrNull { it.support == Support.Works } },
        heavier = working?.takeIf { it.fits }?.let { w ->
            ranked.firstOrNull { it.support == Support.Works && it.original == w.original && !it.fits && versus(it, w, fit = false) < 0 }
        },
    )
}

/** What the setup handles once the owner's [corrections] are applied. */
fun SetupProfile.corrected(corrections: Set<Correction>): SetupProfile {
    var extra = emptySet<Passthrough>()
    if (Correction.PassesTrueHd in corrections) extra = extra + Passthrough.TrueHd
    if (Correction.PassesDts in corrections) extra = extra + setOf(Passthrough.Dts, Passthrough.DtsHd, Passthrough.DtsX)
    return if (extra.isEmpty()) this else copy(passthrough = passthrough + extra)
}

private fun judge(c: Copy, p: SetupProfile, mark: Mark?, language: String?, typical: Float?, pinned: Boolean): Ranked {
    val dv = "DV" in c.hdr
    val hdr10 = "HDR10" in c.hdr || "HDR10+" in c.hdr
    val shown = when {
        dv && Hdr.DolbyVision in p.hdr -> 5
        "HDR10+" in c.hdr && Hdr.Hdr10Plus in p.hdr -> 4
        hdr10 && Hdr.Hdr10 in p.hdr -> 3
        "HLG" in c.hdr && Hdr.Hlg in p.hdr -> 2
        dv -> 1 // no named fallback layer: the stick converts it (a profile 5 file plays on today's HDR10 TV)
        else -> 0
    }
    val mime = MIMES[c.codec]
    val needs = PASSTHROUGH[c.audio]
    var support = Support.Works
    val why = when {
        mark != null -> if (mark.works) "works here" else "failed here on ${DAY.format(Instant.ofEpochMilli(mark.at))}".also { support = Support.Wont }
        mime != null && mime !in p.videoCodecs -> "${c.codec.uppercase()} not decoded here".also { support = Support.Wont }
        dv && c.dvProfile != null && c.dvProfile !in p.dolbyVisionProfiles -> "Dolby Vision profile ${c.dvProfile} not supported".also { support = Support.Wont }
        dv && c.dvProfile == null -> "Dolby Vision profile unknown".also { support = Support.Unsure }
        !dv && hdr10 && shown == 0 -> "TV shows no HDR10".also { support = Support.Unsure }
        needs != null && needs !in p.passthrough -> "${AUDIO_NAMES[c.audio]} may not pass through".also { support = Support.Unsure }
        else -> ""
    }
    val channels = if (p.maxChannels > 0) min(c.channels, p.maxChannels) else c.channels
    // Atmos over DD+ needs E-AC3-JOC passthrough; a TrueHD copy that plays at all is passed through.
    val atmos = c.atmos && (c.audio != "eac3" || Passthrough.EAc3Joc in p.passthrough)
    return Ranked(
        c, support, mark,
        original = language == null || c.languages.isEmpty() || language in c.languages || "multi" in c.languages,
        fits = typical == null || c.mbps <= 0f || c.mbps <= typical,
        pinned = pinned, why = why, typical = typical,
        dvMissing = dv && Hdr.DolbyVision !in p.hdr,
        hdr = shown,
        bits = c.mbps * (GAIN[c.codec] ?: if (c.resolution >= 2160) 2f else 1f),
        sound = if (atmos) 3 else if (channels >= 6) 2 else 1,
        subs = language != null && language != "en" && c.englishSubs,
    )
}

/** Where [a] first differs from [b] in KTD15's quality keys: -k when [a] is better at key k, +k when worse, 0 on a tie. */
private fun versus(a: Ranked, b: Ranked, fit: Boolean = true): Int = when {
    fit && a.fits != b.fits -> if (a.fits) -1 else 1
    a.copy.resolution != b.copy.resolution -> if (a.copy.resolution > b.copy.resolution) -2 else 2
    a.hdr != b.hdr -> if (a.hdr > b.hdr) -3 else 3
    a.bits != b.bits -> if (a.bits > b.bits) -4 else 4
    a.sound != b.sound -> if (a.sound > b.sound) -5 else 5
    a.subs != b.subs -> if (a.subs) -6 else 6
    a.copy.source != b.copy.source -> if (a.copy.source == Source.RealDebrid) -7 else 7
    else -> 0
}

private val ORDER = Comparator<Ranked> { a, b ->
    when {
        a.pinned != b.pinned -> if (a.pinned) -1 else 1
        (a.support == Support.Wont) != (b.support == Support.Wont) -> if (a.support == Support.Wont) 1 else -1
        a.original != b.original -> if (a.original) -1 else 1
        a.support != b.support -> a.support.compareTo(b.support)
        else -> versus(a, b)
    }
}

private fun reason(r: Ranked, pick: Ranked?): String = when {
    r === pick -> if (r.pinned) "pinned" else r.why.ifEmpty { "best for this setup" }
    pick == null || r.support == Support.Wont -> r.why
    !r.original && pick.original -> "dub"
    r.support == Support.Unsure -> r.why
    else -> when (versus(r, pick)) {
        1 -> "needs ~${r.copy.mbps.roundToInt()} Mbps, typical ${r.typical?.roundToInt()}"
        2 -> if (r.copy.resolution > 0) "only ${r.copy.resolution}p" else "resolution unknown"
        3 -> if (r.dvMissing) "TV has no Dolby Vision" else if (r.hdr == 0) "SDR" else "lesser HDR"
        4 -> "lower bitrate"
        5 -> if (pick.sound == 3) "no Atmos" else "fewer channels"
        6 -> "no English subtitles"
        7 -> "same on IPTV"
        0 -> "same as the pick"
        else -> if (pick.pinned) "another copy is pinned" else ""
    }
}

private val MIMES = mapOf(
    "hevc" to "video/hevc", "h264" to "video/avc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9", "mpeg2video" to "video/mpeg2", "vc1" to "video/wvc1",
)
/** H.264-equivalent bitrate, as `pictureTier` on the server scales it. */
private val GAIN = mapOf("h264" to 1f, "hevc" to 2f, "vp9" to 1.8f, "av1" to 2.4f, "mpeg2video" to 0.6f)
/** Audio the stick can't decode: it plays only if passed through. */
private val PASSTHROUGH = mapOf("truehd" to Passthrough.TrueHd, "dts" to Passthrough.Dts, "dtshd" to Passthrough.DtsHd, "dtsx" to Passthrough.DtsX)
private val AUDIO_NAMES = mapOf("truehd" to "TrueHD", "dts" to "DTS", "dtshd" to "DTS-HD", "dtsx" to "DTS:X")
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault())

/**
 * The judgements the ranker reads, kept per setup or title in [store] and in memory after the first
 * read: marks (KTD7) per fingerprint and copy, corrections (R14) per fingerprint, pins (KTD12) per title.
 */
class CopyChoices(private val store: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    private val marks: MutableMap<String, MutableMap<String, Mark>> by lazy {
        read<Map<String, Map<String, Mark>>>(MARKS).orEmpty()
            .mapValuesTo(mutableMapOf()) { (_, m) -> m.filterValues { it.expires > now() }.toMutableMap() }
            .apply { values.removeAll { it.isEmpty() } }
    }
    private val corrections: MutableMap<String, Set<Correction>> by lazy { read<Map<String, Set<Correction>>>(CORRECTIONS).orEmpty().toMutableMap() }
    private val pins: MutableMap<String, String> by lazy { read<Map<String, String>>(PINS).orEmpty().toMutableMap() }

    /** Ranks [copies] of [titleId] for [setup] with this setup's marks and corrections and the title's pin. */
    fun rank(titleId: String, copies: List<Copy>, originalLanguage: String?, setup: SetupProfile, typicalMbps: (Source) -> Float?) =
        rankCopies(copies, originalLanguage, setup, typicalMbps, marks(setup), corrections(setup), pin(titleId))

    @Synchronized fun marks(setup: SetupProfile): Map<String, Mark> =
        marks[setup.fingerprint]?.filterValues { it.expires > now() }.orEmpty()

    /** Records that [copy] played ([works]) or failed for good on [setup]; nothing for a blank reading. */
    @Synchronized fun mark(setup: SetupProfile, copy: Copy, works: Boolean) {
        if (setup.isBlank) return
        val at = now()
        marks.getOrPut(setup.fingerprint) { mutableMapOf() }[copy.id] =
            Mark(works, at, if (copy.source == Source.RealDebrid) at + RD_MARK_MS else Long.MAX_VALUE)
        write<Map<String, Map<String, Mark>>>(MARKS, marks)
    }

    @Synchronized fun clearMark(setup: SetupProfile, copyId: String) {
        if (marks[setup.fingerprint]?.remove(copyId) != null) write<Map<String, Map<String, Mark>>>(MARKS, marks)
    }

    @Synchronized fun corrections(setup: SetupProfile): Set<Correction> = corrections[setup.fingerprint].orEmpty()

    @Synchronized fun correct(setup: SetupProfile, correction: Correction, on: Boolean) {
        if (setup.isBlank) return
        val next = corrections(setup).let { if (on) it + correction else it - correction }
        if (next.isEmpty()) corrections.remove(setup.fingerprint) else corrections[setup.fingerprint] = next
        write<Map<String, Set<Correction>>>(CORRECTIONS, corrections)
    }

    @Synchronized fun pin(titleId: String): String? = pins[titleId]

    /** Pins [copyId] for [titleId], or clears the pin when null. */
    @Synchronized fun pin(titleId: String, copyId: String?) {
        if (copyId == null) pins.remove(titleId) else pins[titleId] = copyId
        write<Map<String, String>>(PINS, pins)
    }

    private inline fun <reified T> read(key: String): T? = store.get(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    private inline fun <reified T> write(key: String, value: T) = store.put(key, json.encodeToString(value))

    private companion object {
        const val MARKS = "copies.marks"
        const val CORRECTIONS = "copies.corrections"
        const val PINS = "copies.pins"
        const val RD_MARK_MS = 30L * 24 * 3_600_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
