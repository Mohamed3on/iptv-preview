@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The bytes an mkv's start needs, read before Play (a Real-Debrid link fetched ahead, [FilmCopies]): its head
 * and, when its index (Cues) sits at the end, the index. Opening a film reads both before the first frame;
 * from a Real-Debrid host the index alone took 2 s (each read a new connection, the file's tail served
 * slowly). [HeadSource] serves them from memory and reads the rest from the network.
 */
class FilmHeads(private val http: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) {
    private class Span(val start: Long, val bytes: ByteArray)
    private class Head(val at: Long, val size: Long, val spans: List<Span>)

    private val heads = ConcurrentHashMap<String, Head>()
    /** Reads under way: a film opening meanwhile waits for them rather than read the same bytes again. */
    private val reading = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /** Reads [url]'s head and index; never throws. */
    suspend fun fetch(url: String) = withContext(Dispatchers.IO) {
        if (heads[url]?.let { now() - it.at <= KEEP_MS } == true) return@withContext
        val done = CompletableFuture<Unit>()
        if (reading.putIfAbsent(url, done) != null) return@withContext
        try {
            read(url)
        } finally {
            done.complete(Unit)
            reading.remove(url)
        }
    }

    private fun read(url: String) {
        runCatching {
            heads.entries.removeAll { now() - it.value.at > KEEP_MS }
            val (head, size) = get(url, 0, HEAD_BYTES - 1) ?: return@runCatching
            val spans = mutableListOf(Span(0, head))
            cuesAt(head)?.takeIf { it >= head.size && size > 0 && size - it <= TAIL_BYTES }?.let { at ->
                get(url, at, size - 1)?.let { spans += Span(at, it.first) }
            }
            heads[url] = Head(now(), size, spans)
        }
    }

    /** The bytes kept for [url] from [position] on, and where they start in them; null when none are. Blocks while they are read. */
    internal fun at(url: String, position: Long): Pair<ByteArray, Int>? {
        runCatching { reading[url]?.get(WAIT_MS, TimeUnit.MILLISECONDS) }
        val h = heads[url]?.takeIf { now() - it.at <= KEEP_MS } ?: return null
        val s = h.spans.firstOrNull { position >= it.start && position < it.start + it.bytes.size } ?: return null
        return s.bytes to (position - s.start).toInt()
    }

    internal fun size(url: String): Long = heads[url]?.size ?: C.LENGTH_UNSET.toLong()

    /** Bytes [from]..[to] of [url], with the file's size from Content-Range (0 unknown). */
    private fun get(url: String, from: Long, to: Long): Pair<ByteArray, Long>? =
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$from-$to").build()).execute().use { r ->
            if (r.code != 206) return null
            r.body.bytes() to (r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: 0)
        }

    companion object {
        private const val HEAD_BYTES = 512 * 1024L
        private const val TAIL_BYTES = 16 * 1024 * 1024L
        private const val KEEP_MS = 5 * 60_000L
        private const val WAIT_MS = 3_000L

        /** The file offset of an mkv's Cues, from the Segment's SeekHead in [head]; null if not found. */
        internal fun cuesAt(head: ByteArray): Long? {
            var p = 0
            fun vint(id: Boolean): Long? {
                if (p >= head.size) return null
                val first = head[p].toInt() and 0xFF
                val len = Integer.numberOfLeadingZeros(first) - 23
                if (len !in 1..8 || p + len > head.size) return null
                var v = (if (id) first else first and (0xFF shr len)).toLong()
                for (i in 1 until len) v = (v shl 8) or (head[p + i].toLong() and 0xFF)
                p += len
                return v
            }
            if (vint(true) != EBML) return null
            val header = vint(false) ?: return null
            p += header.toInt()
            if (vint(true) != SEGMENT) return null
            vint(false) ?: return null
            val segment = p.toLong()
            // The Segment's first children: the SeekHead is one of them, before any Cluster.
            while (p < head.size) {
                val id = vint(true) ?: return null
                val size = vint(false) ?: return null
                val end = p + size
                if (id == CLUSTER) return null
                if (id != SEEK_HEAD) { p = end.toInt(); continue }
                while (p < end && p < head.size) {
                    val seekId = vint(true) ?: return null
                    val seekSize = vint(false) ?: return null
                    val seekEnd = p + seekSize
                    if (seekId != SEEK) { p = seekEnd.toInt(); continue }
                    var target = 0L
                    var position: Long? = null
                    while (p < seekEnd) {
                        val e = vint(true) ?: return null
                        val n = (vint(false) ?: return null).toInt()
                        if (p + n > head.size) return null
                        var v = 0L
                        for (i in 0 until n) v = (v shl 8) or (head[p + i].toLong() and 0xFF)
                        if (e == SEEK_ID) target = v else if (e == SEEK_POSITION) position = v
                        p += n
                    }
                    if (target == CUES && position != null) return segment + position
                }
                return null
            }
            return null
        }

        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val SEEK_HEAD = 0x114D9B74L
        private const val SEEK = 0x4DBBL
        private const val SEEK_ID = 0x53ABL
        private const val SEEK_POSITION = 0x53ACL
        private const val CUES = 0x1C53BB6BL
        private const val CLUSTER = 0x1F43B675L
    }
}

/** Serves what [heads] keeps of a film from memory, then reads on from [upstream]. */
class HeadSource(private val upstream: DataSource, private val heads: FilmHeads) : DataSource {
    private var spec: DataSpec? = null
    private var bytes: ByteArray? = null
    private var offset = 0
    /** Bytes left to serve, memory and network; [C.LENGTH_UNSET] for "to the end". */
    private var left = 0L
    private var served = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        served = 0
        val kept = heads.at(dataSpec.uri.toString(), dataSpec.position)
        if (kept == null) {
            bytes = null
            left = C.LENGTH_UNSET.toLong()
            opened = true
            return upstream.open(dataSpec)
        }
        bytes = kept.first
        offset = kept.second
        val size = heads.size(dataSpec.uri.toString())
        left = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size > 0 -> size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val b = bytes
        val n = if (b != null && this.offset < b.size) {
            val n = minOf(length.toLong(), (b.size - this.offset).toLong(), if (left > 0) left else Long.MAX_VALUE).toInt()
            System.arraycopy(b, this.offset, buffer, offset, n)
            this.offset += n
            n
        } else {
            if (!opened) {
                opened = true
                upstream.open(spec!!.subrange(served))
            }
            upstream.read(buffer, offset, length).also { if (it == C.RESULT_END_OF_INPUT) return it }
        }
        served += n
        if (left > 0) left -= n
        return n
    }

    override fun getUri(): Uri? = spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (opened) upstream.responseHeaders else emptyMap()

    override fun close() {
        bytes = null
        spec = null
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val heads: FilmHeads) : DataSource.Factory {
        override fun createDataSource(): DataSource = HeadSource(upstream.createDataSource(), heads)
    }
}
