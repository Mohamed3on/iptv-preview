package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.at
import com.mohamed3on.iptv.player.osHash
import com.mohamed3on.iptv.player.parseSrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFetchTest {
    private val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\n\r\n" +
        "2\r\n00:00:03,000 --> 00:00:04,000\r\nTwo\r\nlines\r\n\r\n"

    @Test fun movingTheTimingByOneAndAHalfSecondsShiftsEveryLineAndKeepsThemInOrder() {
        val cues = parseSrt(srt)
        assertEquals(listOf(1_000L, 3_000L), cues.map { it.start })
        assertEquals("Hello", cues.at(1_000))
        assertNull(cues.at(1_000, offset = 1_500))
        assertEquals("Hello", cues.at(2_500, offset = 1_500))
        assertEquals("Two\nlines", cues.at(4_500, offset = 1_500))
        assertEquals("Two\nlines", cues.at(2_000, offset = -1_000))
    }

    /** A wrong hash never matches, silently: size plus both 64 KB ends as little-endian words. */
    @Test fun theHashIsTheSizePlusBothEndsAsLittleEndianWords() {
        val head = ByteArray(65_536).also { it[0] = 1 }
        val tail = ByteArray(65_536).also { it[8] = 2; it[65_535] = 0x80.toByte() }
        assertEquals("8000000000001003", osHash(head, tail, 0x1000))
    }
}
