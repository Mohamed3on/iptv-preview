package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.osHash
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFetchTest {
    /** A wrong hash never matches, silently: size plus both 64 KB ends as little-endian words. */
    @Test fun theHashIsTheSizePlusBothEndsAsLittleEndianWords() {
        val head = ByteArray(65_536).also { it[0] = 1 }
        val tail = ByteArray(65_536).also { it[8] = 2; it[65_535] = 0x80.toByte() }
        assertEquals("8000000000001003", osHash(head, tail, 0x1000))
    }
}
