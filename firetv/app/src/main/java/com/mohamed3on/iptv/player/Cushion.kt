package com.mohamed3on.iptv.player

/**
 * How much picture to buffer before playing again after a stall: each stall on the same feed waits
 * for a bigger cushion ([STEPS_MS]), so a shaky line does not stall again within seconds; every
 * [QUIET_MS] without a stall steps back down one level. Every second of cushion is a second further
 * behind live, hence the cap. Called from the main thread and ExoPlayer's playback thread; times
 * are elapsedRealtime ms.
 */
class Cushion {
    private var stalls = 0
    /** When the quiet clock last started: the last stall, or the last step down. */
    private var quietSince = 0L

    /** A new feed (a zap or a failover) starts at the usual cushion. */
    @Synchronized fun onTune() {
        stalls = 0
    }

    /** Counts a stall; returns the cushion it waits for. */
    @Synchronized fun onRebuffer(now: Long): Long {
        stepDown(now)
        stalls = minOf(stalls + 1, STEPS_MS.size)
        quietSince = now
        return resumeMs(now)
    }

    @Synchronized fun resumeMs(now: Long): Long {
        stepDown(now)
        return STEPS_MS[maxOf(stalls - 1, 0)]
    }

    private fun stepDown(now: Long) {
        while (stalls > 0 && now - quietSince >= QUIET_MS) {
            stalls--
            quietSince += QUIET_MS
        }
    }

    companion object {
        /** Today's resume threshold; the start-of-tune one is separate and stays small. */
        const val BASE_MS = 1_500L
        const val MAX_MS = 10_000L
        private val STEPS_MS = longArrayOf(BASE_MS, 3_000, 6_000, MAX_MS)
        private const val QUIET_MS = 10 * 60_000L
    }
}
