package com.example.ialocal.knowledge

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Wraps a cluster's compressed bytes in a decompressing stream. */
fun interface ZimDecompressor {
    /** [compression] is the ZIM cluster type: 4 = xz, 5 = zstd (what current Kiwix files use). */
    fun open(compression: Int, compressed: InputStream): InputStream
}

data class ZimEntry(
    val index: Int,
    val namespace: Char,
    val path: String,
    val title: String,
    /** Null for redirects. */
    val mimeType: String?,
    val redirectTo: Int?,
    val cluster: Int,
    val blob: Int,
) {
    val isRedirect: Boolean get() = redirectTo != null
    val isHtml: Boolean get() = mimeType?.startsWith("text/html") == true
}

/**
 * Minimal reader for ZIM files, the format of Kiwix's offline Wikipedia (openzim.org). It looks
 * entries up by path or title with binary searches over the archive's own indexes and reads an
 * entry by decompressing its cluster only up to that entry. All reads are positioned, so one
 * archive can be shared between threads, and almost nothing is kept in memory even for archives
 * of many gigabytes.
 */
class ZimArchive private constructor(
    private val channel: FileChannel,
    private val decompressor: ZimDecompressor,
) : Closeable {
    private val fileSize = channel.size()
    val majorVersion: Int
    val minorVersion: Int
    val entryCount: Int
    private val clusterCount: Int
    private val pathPointerPos: Long
    private val titlePointerPos: Long
    private val clusterPointerPos: Long
    private val mimeListPos: Long
    private val mimeTypes: List<String>

    /** Namespace that holds the articles: 'C' in the current scheme (6.1 and later), 'A' before. */
    val articleNamespace: Char
    private val titles: TitleIndex

    init {
        require(fileSize >= HEADER_SIZE) { "O arquivo não é um ZIM válido." }
        val header = read(0, HEADER_SIZE)
        require(header.int == MAGIC) { "O arquivo não é um ZIM válido." }
        majorVersion = header.short.toInt() and 0xFFFF
        minorVersion = header.short.toInt() and 0xFFFF
        header.position(24)
        entryCount = header.int
        clusterCount = header.int
        pathPointerPos = header.long
        titlePointerPos = header.long
        clusterPointerPos = header.long
        mimeListPos = header.long
        require(entryCount >= 0 && clusterCount >= 0) { "Cabeçalho ZIM corrompido." }
        mimeTypes = readMimeTypes()
        articleNamespace = if (majorVersion > 6 || (majorVersion == 6 && minorVersion >= 1)) 'C' else 'A'
        titles = openTitleIndex()
    }

    /** Number of entries reachable by title, or 0 when the archive has no title index. */
    val titleCount: Int get() = titles.size

    fun entry(index: Int): ZimEntry {
        require(index in 0 until entryCount) { "Entrada ZIM inexistente: $index" }
        return parseEntry(index, readLong(pathPointerPos + 8L * index))
    }

    /** The entry a chain of redirects ends at, or null if it loops or leaves the archive. */
    fun resolve(entry: ZimEntry): ZimEntry? {
        var current = entry
        repeat(MAX_REDIRECTS) {
            val target = current.redirectTo ?: return current
            if (target !in 0 until entryCount) return null
            current = entry(target)
        }
        return null
    }

    fun findByPath(path: String, namespace: Char = articleNamespace): ZimEntry? {
        val key = path.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = entryCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = entry(mid)
            val cmp = compareKey(entry.namespace, entry.path, namespace, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return entry
            }
        }
        return null
    }

    /** The entry whose title is exactly [title], in the article namespace. */
    fun findByTitle(title: String): ZimEntry? {
        val key = title.toByteArray(Charsets.UTF_8)
        val position = titleLowerBound(key)
        if (position >= titles.size) return null
        val entry = entry(titles.entryAt(position))
        return entry.takeIf { it.namespace == articleNamespace && it.title == title }
    }

    /** Entries whose titles start with [prefix] (case-sensitive), in title order. */
    fun titlesWithPrefix(prefix: String, limit: Int): List<ZimEntry> {
        if (limit <= 0 || titles.size == 0) return emptyList()
        val key = prefix.toByteArray(Charsets.UTF_8)
        val found = mutableListOf<ZimEntry>()
        var position = titleLowerBound(key)
        while (position < titles.size && found.size < limit) {
            val entry = entry(titles.entryAt(position))
            if (entry.namespace != articleNamespace || !entry.title.startsWith(prefix)) break
            found += entry
            position++
        }
        return found
    }

    /** Metadata such as "Title", "Language" or "Date" from the M namespace. */
    fun metadata(name: String): String? = runCatching {
        findByPath(name, 'M')?.takeIf { !it.isRedirect }?.let { String(read(it, MAX_METADATA_BYTES), Charsets.UTF_8) }
    }.getOrNull()

    /** The content of [entry] (after redirects), at most [maxBytes] bytes. */
    fun read(entry: ZimEntry, maxBytes: Int = DEFAULT_MAX_CONTENT_BYTES): ByteArray {
        val target = resolve(entry) ?: throw IOException("Redirecionamento inválido no arquivo ZIM.")
        require(target.cluster in 0 until clusterCount) { "Entrada sem conteúdo no arquivo ZIM." }
        val clusterPos = readLong(clusterPointerPos + 8L * target.cluster)
        val info = read(clusterPos, 1).get().toInt() and 0xFF
        val compression = info and 0x0F
        val offsetSize = if (info and EXTENDED_CLUSTER != 0) 8 else 4
        return if (compression == COMPRESSION_NONE || compression == COMPRESSION_NONE_LEGACY) {
            val (start, length) = uncompressedBlob(clusterPos, offsetSize, target.blob)
            val bytes = read(start, minOf(length, maxBytes.toLong()).toInt())
            ByteArray(bytes.remaining()).also(bytes::get)
        } else {
            val clusterEnd = clusterEnd(target.cluster, clusterPos)
            decompressor.open(compression, ChannelInputStream(channel, clusterPos + 1, clusterEnd)).use { stream ->
                compressedBlob(stream, offsetSize, target.blob, maxBytes)
            }
        }
    }

    override fun close() {
        channel.close()
    }

    private fun compressedBlob(stream: InputStream, offsetSize: Int, blob: Int, maxBytes: Int): ByteArray {
        val first = readOffset(stream, offsetSize)
        val offsets = first / offsetSize
        if (blob + 1 >= offsets) throw IOException("Entrada fora do bloco no arquivo ZIM.")
        var consumed = offsetSize.toLong()
        var start = first
        var end = first
        for (i in 1..blob + 1) {
            val value = readOffset(stream, offsetSize)
            consumed += offsetSize
            if (i == blob) start = value
            if (i == blob + 1) end = value
        }
        if (start < consumed || end < start) throw IOException("Bloco corrompido no arquivo ZIM.")
        skipFully(stream, start - consumed)
        val length = minOf(end - start, maxBytes.toLong()).toInt()
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = stream.read(bytes, read, length - read)
            if (count < 0) throw EOFException("Bloco incompleto no arquivo ZIM.")
            read += count
        }
        return bytes
    }

    /** Absolute position and length of a blob in an uncompressed cluster. */
    private fun uncompressedBlob(clusterPos: Long, offsetSize: Int, blob: Int): Pair<Long, Long> {
        val base = clusterPos + 1
        val offsets = offsetAt(base, offsetSize) / offsetSize
        if (blob + 1 >= offsets) throw IOException("Entrada fora do bloco no arquivo ZIM.")
        val start = offsetAt(base + offsetSize.toLong() * blob, offsetSize)
        val end = offsetAt(base + offsetSize.toLong() * (blob + 1), offsetSize)
        if (end < start || base + end > fileSize) throw IOException("Bloco corrompido no arquivo ZIM.")
        return (base + start) to (end - start)
    }

    private fun clusterEnd(cluster: Int, clusterPos: Long): Long {
        if (cluster + 1 < clusterCount) {
            val next = readLong(clusterPointerPos + 8L * (cluster + 1))
            if (next > clusterPos) return next
        }
        return fileSize
    }

    private fun titleLowerBound(key: ByteArray): Int {
        var low = 0
        var high = titles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val entry = entry(titles.entryAt(mid))
            if (compareKey(entry.namespace, entry.title, articleNamespace, key) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun openTitleIndex(): TitleIndex {
        // Current files list the articles in title order in X/listing/titleOrdered/v1; older ones
        // point at every entry, all namespaces included, from the header.
        if (articleNamespace == 'C') {
            val listing = runCatching { findByPath(TITLE_LISTING_PATH, 'X') }.getOrNull()
            if (listing != null && !listing.isRedirect && listing.cluster in 0 until clusterCount) {
                runCatching { listingIndex(listing) }.getOrNull()?.let { return it }
            }
        }
        if (titlePointerPos != NO_TITLE_INDEX && titlePointerPos + 4L * entryCount <= fileSize) {
            return PositionedTitleIndex(titlePointerPos, entryCount)
        }
        return InMemoryTitleIndex(IntArray(0))
    }

    private fun listingIndex(listing: ZimEntry): TitleIndex {
        val clusterPos = readLong(clusterPointerPos + 8L * listing.cluster)
        val info = read(clusterPos, 1).get().toInt() and 0xFF
        val compression = info and 0x0F
        val offsetSize = if (info and EXTENDED_CLUSTER != 0) 8 else 4
        if (compression == COMPRESSION_NONE || compression == COMPRESSION_NONE_LEGACY) {
            val (start, length) = uncompressedBlob(clusterPos, offsetSize, listing.blob)
            return PositionedTitleIndex(start, (length / 4).toInt())
        }
        // A compressed listing has to be held in memory: 4 bytes per article.
        val bytes = read(listing, MAX_IN_MEMORY_LISTING_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return InMemoryTitleIndex(IntArray(bytes.size / 4) { buffer.int })
    }

    private interface TitleIndex {
        val size: Int
        fun entryAt(position: Int): Int
    }

    private inner class PositionedTitleIndex(private val start: Long, override val size: Int) : TitleIndex {
        override fun entryAt(position: Int): Int = read(start + 4L * position, 4).int
    }

    private class InMemoryTitleIndex(private val indexes: IntArray) : TitleIndex {
        override val size: Int get() = indexes.size
        override fun entryAt(position: Int): Int = indexes[position]
    }

    private fun parseEntry(index: Int, pos: Long): ZimEntry =
        tryParseEntry(index, read(pos, DIRENT_PROBE_BYTES, allowShort = true))
            ?: tryParseEntry(index, read(pos, DIRENT_MAX_BYTES, allowShort = true))
            ?: throw IOException("Entrada corrompida no arquivo ZIM.")

    private fun tryParseEntry(index: Int, buffer: ByteBuffer): ZimEntry? {
        if (buffer.limit() < 8) return null
        val mime = buffer.getShort(0).toInt() and 0xFFFF
        val namespace = (buffer.get(3).toInt() and 0xFF).toChar()
        var redirect: Int? = null
        var cluster = -1
        var blob = -1
        val stringsAt = when (mime) {
            MIME_REDIRECT -> {
                if (buffer.limit() < 12) return null
                redirect = buffer.getInt(8)
                12
            }
            MIME_LINK_TARGET, MIME_DELETED -> 8
            else -> {
                if (buffer.limit() < 16) return null
                cluster = buffer.getInt(8)
                blob = buffer.getInt(12)
                16
            }
        }
        val pathEnd = zeroAt(buffer, stringsAt) ?: return null
        val titleEnd = zeroAt(buffer, pathEnd + 1) ?: return null
        val path = utf8(buffer, stringsAt, pathEnd)
        val title = utf8(buffer, pathEnd + 1, titleEnd).ifEmpty { path }
        return ZimEntry(
            index = index,
            namespace = namespace,
            path = path,
            title = title,
            mimeType = if (mime < mimeTypes.size) mimeTypes[mime] else null,
            redirectTo = redirect,
            cluster = cluster,
            blob = blob,
        )
    }

    private fun readMimeTypes(): List<String> {
        val buffer = read(mimeListPos, minOf(MAX_MIME_LIST_BYTES.toLong(), fileSize - mimeListPos).toInt())
        val types = mutableListOf<String>()
        var start = 0
        while (start < buffer.limit()) {
            val end = zeroAt(buffer, start) ?: break
            if (end == start) break
            types += utf8(buffer, start, end)
            start = end + 1
        }
        return types
    }

    private fun readLong(pos: Long): Long = read(pos, 8).long

    private fun offsetAt(pos: Long, size: Int): Long =
        if (size == 8) read(pos, 8).long else read(pos, 4).int.toLong() and 0xFFFFFFFFL

    private fun read(pos: Long, length: Int, allowShort: Boolean = false): ByteBuffer {
        val available = (fileSize - pos).coerceAtLeast(0)
        val wanted = if (allowShort) minOf(length.toLong(), available).toInt() else length
        if (!allowShort && pos + length > fileSize) throw EOFException("Leitura além do fim do arquivo ZIM.")
        val buffer = ByteBuffer.allocate(wanted).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, pos + buffer.position())
            if (count < 0) break
        }
        if (!allowShort && buffer.hasRemaining()) throw EOFException("Leitura além do fim do arquivo ZIM.")
        buffer.flip()
        return buffer
    }

    companion object {
        private const val MAGIC = 72173914
        private const val HEADER_SIZE = 80
        private const val MIME_REDIRECT = 0xFFFF
        private const val MIME_LINK_TARGET = 0xFFFE
        private const val MIME_DELETED = 0xFFFD
        private const val EXTENDED_CLUSTER = 0x10
        private const val COMPRESSION_NONE_LEGACY = 0
        private const val COMPRESSION_NONE = 1
        private const val NO_TITLE_INDEX = -1L
        private const val TITLE_LISTING_PATH = "listing/titleOrdered/v1"
        private const val MAX_REDIRECTS = 10
        private const val DIRENT_PROBE_BYTES = 512
        private const val DIRENT_MAX_BYTES = 64 * 1024
        private const val MAX_MIME_LIST_BYTES = 64 * 1024
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val MAX_IN_MEMORY_LISTING_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_CONTENT_BYTES = 2 * 1024 * 1024

        fun open(file: File, decompressor: ZimDecompressor): ZimArchive {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            return try {
                ZimArchive(channel, decompressor)
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }

        private fun compareKey(namespace: Char, value: String, keyNamespace: Char, key: ByteArray): Int {
            if (namespace != keyNamespace) return namespace.compareTo(keyNamespace)
            val bytes = value.toByteArray(Charsets.UTF_8)
            val common = minOf(bytes.size, key.size)
            for (i in 0 until common) {
                val diff = (bytes[i].toInt() and 0xFF) - (key[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return bytes.size - key.size
        }

        private fun zeroAt(buffer: ByteBuffer, from: Int): Int? {
            for (i in from until buffer.limit()) if (buffer.get(i) == 0.toByte()) return i
            return null
        }

        private fun utf8(buffer: ByteBuffer, start: Int, end: Int): String {
            val bytes = ByteArray(end - start)
            for (i in bytes.indices) bytes[i] = buffer.get(start + i)
            return String(bytes, Charsets.UTF_8)
        }

        private fun readOffset(stream: InputStream, size: Int): Long {
            var value = 0L
            for (i in 0 until size) {
                val byte = stream.read()
                if (byte < 0) throw EOFException("Bloco incompleto no arquivo ZIM.")
                value = value or (byte.toLong() shl (8 * i))
            }
            return value
        }

        private fun skipFully(stream: InputStream, count: Long) {
            var remaining = count
            val scratch = ByteArray(8 * 1024)
            while (remaining > 0) {
                val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Bloco incompleto no arquivo ZIM.")
                remaining -= read
            }
        }
    }
}

/** Reads [start, end) of a file channel with positioned reads, so it never moves a shared position. */
private class ChannelInputStream(
    private val channel: FileChannel,
    private var position: Long,
    private val end: Long,
) : InputStream() {
    private val buffer = ByteBuffer.allocate(64 * 1024).also { it.limit(0) }

    override fun read(): Int {
        if (!fill()) return -1
        return buffer.get().toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!fill()) return -1
        val count = minOf(length, buffer.remaining())
        buffer.get(target, offset, count)
        return count
    }

    private fun fill(): Boolean {
        if (buffer.hasRemaining()) return true
        if (position >= end) return false
        buffer.clear()
        buffer.limit(minOf(buffer.capacity().toLong(), end - position).toInt())
        val count = channel.read(buffer, position)
        buffer.flip()
        if (count <= 0) return false
        position += count
        return true
    }
}
