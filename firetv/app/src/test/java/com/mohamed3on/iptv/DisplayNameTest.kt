package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.displayName
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {
    private fun check(raw: String, shown: String) = assertEquals(shown, displayName(raw))

    @Test fun dropsFeedPrefixAndQualityMarkers() {
        check("SS: beIN SP⚽RTS 6 4K", "beIN Sports 6")
        check("◉: beIN Sp⚽rts 2 4K", "beIN Sports 2")
        check("UK: SKY SPORTS 1 ᵁᴴᴰ ³⁸⁴⁰ᴾ", "Sky Sports 1")
        check("VIP: TNT SPORTS 1 ᴿᴬᵂ ⁵⁰ FPS", "TNT Sports 1")
        check("NOW: TNT SPORT 1 ᴴᴰ", "TNT Sport 1")
        check("UK: SKY SPORTS F1  50FPS ᴿᴬᵂ", "Sky Sports F1")
        check("DE: SKY SPORT BUNDESLIGA 1 HD (SAT)", "Sky Sport Bundesliga 1")
        check("UK: SKY SPORTS MAIN EVENT HEVC 4K", "Sky Sports Main Event")
    }

    @Test fun keepsWordsSpelledInSuperscript() = check("UK: SKY SPORTS ᴬʳᵉⁿᵃ HD", "Sky Sports Arena")

    @Test fun keepsAcronymsAndMixedCase() {
        check("ES: DAZN LALIGA FHD", "DAZN Laliga")
        check("UK: TNT SPORTS 2 HD", "TNT Sports 2")
    }

    @Test fun leavesEventSlotsAndNonLatinAlone() {
        check("UK PPV 1: Arsenal v Chelsea", "UK PPV 1: Arsenal v Chelsea")
        check("AR: بي إن سبورت 1 HD", "بي إن سبورت 1")
    }

    @Test fun neverReturnsEmpty() = check("UK: 4K", "UK: 4K")
}
