package com.mohamed3on.iptv

import android.database.sqlite.SQLiteDatabase
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.ui.rowLines
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** One guide row as the server writes it (table `programmes`, times in epoch seconds). */
data class Row(
    val tvgId: String, val start: Long, val stop: Long, val title: String,
    val norm: String = title.lowercase(), val synthetic: Boolean = false, val match: Boolean = false,
    /** The 🏆 group of the competition a live match names; test matches default to one. */
    val comp: String? = if (match && !synthetic) "🏆 Premier League" else null,
)

/** Writes [rows] to a guide file the way the server builds it, and opens it. */
fun guideOf(dir: File, vararg rows: Row): GuideDb {
    val file = File(dir, "guide-${System.nanoTime()}.db")
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        db.execSQL("CREATE TABLE programmes(tvg_id TEXT, start INTEGER, stop INTEGER, title TEXT, norm TEXT, descr TEXT, synthetic INTEGER, match INTEGER, comp TEXT)")
        for (r in rows) {
            db.execSQL(
                "INSERT INTO programmes VALUES (?, ?, ?, ?, ?, '', ?, ?, ?)",
                arrayOf<Any?>(r.tvgId, r.start, r.stop, r.title, r.norm, if (r.synthetic) 1 else 0, if (r.match) 1 else 0, r.comp),
            )
        }
        db.execSQL("CREATE INDEX programmes_channel ON programmes (tvg_id, start)")
    }
    return GuideDb.open(file)
}

@RunWith(RobolectricTestRunner::class)
class NowNextTest {
    @get:Rule val tmp = TemporaryFolder()
    private var guide: GuideDb? = null

    @After fun close() { guide?.close() }

    private val h20 = 1_793_563_200L // a 20:00 boundary in epoch seconds
    private val h21 = h20 + 3600
    private val h22 = h21 + 3600
    private val g = "🏴 Sky Sports & UK Sports"
    private fun ch(name: String, tvgId: String, slot: Boolean = false) =
        Channel(id = tvgId.hashCode(), name = name, group = g, tvgId = tvgId, slot = slot, home = g, family = "$g|$name", rank = 0)

    private fun open(vararg rows: Row) = guideOf(tmp.root, *rows).also { guide = it }

    @Test fun oneSecondBeforeNineTheEightOClockProgrammeIsOnThenTheNineOClockOne() {
        val db = open(Row("a.uk", h20, h21, "Snooker International Championship"), Row("a.uk", h21, h22, "Live Serie A: Juventus v Napoli"))
        val sky = ch("Sky Sports Main Event", "a.uk")

        val before = rowLines(sky, db.nowNextFor(listOf("a.uk"), h21 - 1).getValue("a.uk"))
        assertEquals("Snooker International Championship", before.now?.title)
        assertEquals("Live Serie A: Juventus v Napoli", before.next?.title)

        val at = rowLines(sky, db.nowNextFor(listOf("a.uk"), h21).getValue("a.uk"))
        assertEquals("Live Serie A: Juventus v Napoli", at.now?.title)
        assertNull(at.next)
    }
}
