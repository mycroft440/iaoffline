package com.example.ialocal.knowledge

import com.github.luben.zstd.Zstd
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes small ZIM files (current namespace scheme, version 6.1) for tests: one uncompressed
 * cluster with metadata and the title listing, and one zstd cluster with the articles.
 */
class TestZimBuilder {
    private data class Item(
        val namespace: Char,
        val path: String,
        val title: String,
        val mime: String?,
        val content: ByteArray? = null,
        val redirectPath: String? = null,
    )

    private val items = mutableListOf<Item>()

    fun article(path: String, title: String, html: String) = apply {
        items += Item('C', path, title, "text/html", html.toByteArray(Charsets.UTF_8))
    }

    fun redirect(path: String, title: String, targetPath: String) = apply {
        items += Item('C', path, title, null, redirectPath = targetPath)
    }

    fun metadata(name: String, value: String) = apply {
        items += Item('M', name, name, "text/plain", value.toByteArray(Charsets.UTF_8))
    }

    fun write(file: File) {
        val listingItem = Item('X', "listing/titleOrdered/v1", "listing/titleOrdered/v1", "application/octet-stream+zimlisting")
        val all = (items + listingItem).sortedWith(compareBy<Item> { it.namespace }.thenComparator { a, b -> compareBytes(a.path, b.path) })
        val indexOf = all.withIndex().associate { (index, item) -> (item.namespace to item.path) to index }

        val mimeTypes = all.mapNotNull { it.mime }.distinct()
        // Front articles and redirects in title order, like libzim writes them.
        val titleOrder = all.withIndex()
            .filter { (_, item) -> item.namespace == 'C' }
            .sortedWith { a, b -> compareBytes(a.value.title, b.value.title) }
            .map { it.index }
        val listingBytes = ByteBuffer.allocate(4 * titleOrder.size).order(ByteOrder.LITTLE_ENDIAN)
            .apply { titleOrder.forEach { putInt(it) } }.array()

        // Cluster 0: uncompressed metadata + listing. Cluster 1: zstd articles.
        val plainBlobs = mutableListOf<ByteArray>()
        val articleBlobs = mutableListOf<ByteArray>()
        val location = mutableMapOf<Int, Pair<Int, Int>>()
        all.forEachIndexed { index, item ->
            when {
                item.redirectPath != null -> Unit
                item === listingItem -> { location[index] = 0 to plainBlobs.size; plainBlobs += listingBytes }
                item.namespace == 'C' -> { location[index] = 1 to articleBlobs.size; articleBlobs += item.content!! }
                else -> { location[index] = 0 to plainBlobs.size; plainBlobs += item.content!! }
            }
        }
        val cluster0 = byteArrayOf(1) + blobTable(plainBlobs)
        val cluster1 = byteArrayOf(5) + Zstd.compress(blobTable(articleBlobs))

        val mimeList = ByteArrayOutputStream().apply {
            mimeTypes.forEach { write(it.toByteArray(Charsets.UTF_8)); write(0) }
            write(0)
        }.toByteArray()

        val dirents = all.mapIndexed { index, item ->
            val out = ByteArrayOutputStream()
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            if (item.redirectPath != null) {
                header.putShort(0xFFFF.toShort()).put(0).put(item.namespace.code.toByte()).putInt(0)
                header.putInt(indexOf.getValue('C' to item.redirectPath))
                out.write(header.array(), 0, 12)
            } else {
                val (cluster, blob) = location.getValue(index)
                header.putShort(mimeTypes.indexOf(item.mime).toShort()).put(0).put(item.namespace.code.toByte()).putInt(0)
                header.putInt(cluster).putInt(blob)
                out.write(header.array(), 0, 16)
            }
            out.write(item.path.toByteArray(Charsets.UTF_8)); out.write(0)
            out.write((if (item.title == item.path) "" else item.title).toByteArray(Charsets.UTF_8)); out.write(0)
            out.toByteArray()
        }

        val mimePos = 80L
        val urlPtrPos = mimePos + mimeList.size
        val clusterPtrPos = urlPtrPos + 8L * all.size
        var cursor = clusterPtrPos + 8L * 2
        val direntPositions = dirents.map { bytes -> cursor.also { cursor += bytes.size } }
        val cluster0Pos = cursor
        val cluster1Pos = cluster0Pos + cluster0.size
        val checksumPos = cluster1Pos + cluster1.size

        val header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(72173914).putShort(6).putShort(1)
            .put(ByteArray(16))
            .putInt(all.size).putInt(2)
            .putLong(urlPtrPos).putLong(-1L).putLong(clusterPtrPos).putLong(mimePos)
            .putInt(-1).putInt(-1).putLong(checksumPos)

        file.outputStream().use { out ->
            out.write(header.array())
            out.write(mimeList)
            val pointers = ByteBuffer.allocate(8 * (all.size + 2)).order(ByteOrder.LITTLE_ENDIAN)
            direntPositions.forEach { pointers.putLong(it) }
            pointers.putLong(cluster0Pos).putLong(cluster1Pos)
            out.write(pointers.array())
            dirents.forEach(out::write)
            out.write(cluster0)
            out.write(cluster1)
            out.write(ByteArray(16))
        }
    }

    private fun blobTable(blobs: List<ByteArray>): ByteArray {
        val offsets = ByteBuffer.allocate(4 * (blobs.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 4 * (blobs.size + 1)
        offsets.putInt(offset)
        blobs.forEach { offset += it.size; offsets.putInt(offset) }
        return ByteArrayOutputStream().apply {
            write(offsets.array())
            blobs.forEach(::write)
        }.toByteArray()
    }

    private fun compareBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val diff = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return x.size - y.size
    }
}
