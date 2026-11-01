package com.mohamed3on.iptv.data

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** One guide entry; times are epoch seconds. [synthetic] marks name-as-title filler. */
data class Programme(val start: Long, val stop: Long, val title: String, val descr: String, val synthetic: Boolean, val match: Boolean)

data class NowNext(val now: Programme?, val next: Programme?)

/** The cached guide (the server's SQLite file), opened read-only. */
class GuideDb private constructor(private val db: SQLiteDatabase) : Closeable {
    /** What airs on [tvgId] at [nowSec] (null in a gap) and what follows it. */
    fun nowNext(tvgId: String, nowSec: Long): NowNext {
        val rows = programmes(tvgId, nowSec, limit = 2)
        val now = rows.firstOrNull()?.takeIf { it.start <= nowSec }
        return NowNext(now, if (now != null) rows.getOrNull(1) else rows.firstOrNull())
    }

    /** Programmes on [tvgId] still running or starting after [fromSec], in start order. */
    fun schedule(tvgId: String, fromSec: Long): List<Programme> = programmes(tvgId, fromSec, limit = -1)

    private fun programmes(tvgId: String, after: Long, limit: Int): List<Programme> =
        db.rawQuery(
            "SELECT start, stop, title, descr, synthetic, \"match\" FROM programmes WHERE tvg_id = ? AND stop > ? ORDER BY start LIMIT $limit",
            arrayOf(tvgId, after.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4) != 0, c.getInt(5) != 0))
                }
            }
        }

    override fun close() = db.close()

    companion object {
        /** Opens [file] read-only; throws unless it is SQLite with a non-empty programmes table. */
        fun open(file: File): GuideDb {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT 1 FROM programmes LIMIT 1", null).use { check(it.moveToFirst()) { "guide is empty" } }
            } catch (e: Exception) {
                db.close()
                throw e
            }
            return GuideDb(db)
        }
    }
}
