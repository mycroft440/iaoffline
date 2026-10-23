package com.example.ialocal.models

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Lightweight GGUF metadata reader used before the native runtime is loaded. */
class GgufInspector {
    data class Metadata(
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val name: String?,
        val architecture: String?,
        val sizeLabel: String?,
        val contextLength: Int?,
        val hasChatTemplate: Boolean,
        /**
         * Bytes of tensors the native runtime reads from the file on demand instead of keeping in
         * RAM (per-layer embeddings, about half of a Gemma 4 E2B/E4B file).
         */
        val onDemandBytes: Long = 0L,
        /**
         * Bytes of mixture-of-experts expert weights (0 for a dense model). Each token uses only a
         * few experts, so a model larger than the RAM can keep these in the file and read them as
         * the router picks them; the rest is read for every token.
         */
        val expertBytes: Long = 0L,
        /** Largest layer's expert weights: space needed while a prompt batch uses many experts. */
        val largestExpertLayerBytes: Long = 0L,
        val largestSharedLayerBytes: Long = 0L,
        val blockCount: Int = 0,
    )

    fun inspect(file: File): Metadata =
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { inspect(it, file.length()) }

    /** [totalBytes] is the file size when known; it sizes an on-demand tensor stored last. */
    fun inspect(raw: InputStream, totalBytes: Long? = null): Metadata {
        val input = CountingInputStream(if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 256 * 1024))
        val magic = ByteArray(4)
        readFully(input, magic)
        if (!magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
            throw IOException("O arquivo selecionado não é um GGUF válido.")
        }

        val version = readU32(input).toInt()
        if (version !in 2..3) throw IOException("Versão GGUF $version ainda não é suportada.")
        val tensorCount = readU64(input)
        val kvCount = readU64(input)
        if (kvCount < 0 || kvCount > 10_000_000L) throw IOException("Cabeçalho GGUF inválido.")

        var name: String? = null
        var architecture: String? = null
        var sizeLabel: String? = null
        var contextLength: Int? = null
        val blockCounts = HashMap<String, Int>()
        var hasChatTemplate = false
        var alignment = DEFAULT_ALIGNMENT

        var i = 0L
        while (i < kvCount) {
            val key = readString(input, 2 * 1024 * 1024)
            val type = readU32(input).toInt()
            val wanted = key == "general.name" ||
                key == "general.architecture" ||
                key == "general.size_label" ||
                key == "tokenizer.chat_template" ||
                key == "general.alignment" ||
                key.endsWith(".context_length") || key.endsWith(".block_count")
            val value = readValue(input, type, wanted)

            when (key) {
                "general.name" -> name = value as? String
                "general.architecture" -> architecture = value as? String
                "general.size_label" -> sizeLabel = value?.toString()
                "tokenizer.chat_template" -> hasChatTemplate = (value as? String)?.isNotBlank() == true
                "general.alignment" -> alignment = (value as? Number)?.toLong()?.takeIf { it > 0 } ?: alignment
                else -> if (key.endsWith(".block_count")) {
                    blockCounts[key] = (value as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
                } else if (key.endsWith(".context_length")) {
                    contextLength = when (value) {
                        is Number -> value.toInt()
                        else -> null
                    } ?: contextLength
                }
            }
            i++
        }

        // Only sizes memory use: a header read without its tensor table still describes the model.
        val tensorBytes = runCatching { readTensorBytes(input, tensorCount, alignment, totalBytes) }.getOrDefault(TensorBytes())

        return Metadata(
            version = version,
            tensorCount = tensorCount,
            kvCount = kvCount,
            name = name,
            architecture = architecture,
            sizeLabel = sizeLabel,
            contextLength = contextLength,
            hasChatTemplate = hasChatTemplate,
            onDemandBytes = tensorBytes.onDemand,
            expertBytes = tensorBytes.experts,
            largestExpertLayerBytes = tensorBytes.largestExpertLayer,
            largestSharedLayerBytes = tensorBytes.largestSharedLayer,
            blockCount = blockCounts["$architecture.block_count"] ?: 0,
        )
    }

    private data class TensorBytes(
        val onDemand: Long = 0L,
        val experts: Long = 0L,
        val largestExpertLayer: Long = 0L,
        val largestSharedLayer: Long = 0L,
    )

    /**
     * Reads the tensor table that follows the metadata. A tensor's size is the distance to the next
     * tensor's data (the last one ends at the file end), which needs no table of quantization types.
     */
    private fun readTensorBytes(input: CountingInputStream, tensorCount: Long, alignment: Long, totalBytes: Long?): TensorBytes {
        if (tensorCount < 0 || tensorCount > MAX_TENSORS) throw IOException("Cabeçalho GGUF inválido.")
        val offsets = LongArray(tensorCount.toInt())
        val onDemand = ArrayList<Int>()
        val experts = ArrayList<Int>()
        val expertLayers = HashMap<Int, Int>()
        val sharedLayers = HashMap<Int, Int>()
        for (index in 0 until tensorCount.toInt()) {
            val name = readString(input, 64 * 1024)
            val dimensions = readU32(input)
            if (dimensions > MAX_DIMENSIONS) throw IOException("Tensor GGUF inválido.")
            skipFully(input, dimensions * 8)
            readU32(input) // quantization type
            offsets[index] = readU64(input)
            if (name in ON_DEMAND_TENSORS) onDemand += index
            if (EXPERT_TENSOR.containsMatchIn(name)) {
                experts += index
                // Unknown layer names share a conservative bucket instead of disappearing.
                expertLayers[index] = EXPERT_LAYER.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } else if (name !in ON_DEMAND_TENSORS) {
                EXPERT_LAYER.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { sharedLayers[index] = it }
            }
        }
        if (onDemand.isEmpty() && experts.isEmpty() && sharedLayers.isEmpty()) return TensorBytes()
        val dataStart = (input.count + alignment - 1) / alignment * alignment
        val dataEnd = totalBytes?.minus(dataStart)
        val sorted = offsets.sortedArray()
        fun sizeOf(index: Int): Long {
            val start = offsets[index]
            // Upper-bound search avoids scanning the tensor table for every MoE tensor.
            // Repeated offsets are skipped, as in the original strictly-greater lookup.
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= start) lo = mid + 1 else hi = mid
            }
            val next = sorted.getOrNull(lo) ?: dataEnd ?: start
            return (next - start).coerceAtLeast(0L)
        }
        val bytesByLayer = HashMap<Int, Long>()
        for (index in experts) {
            val layer = expertLayers.getValue(index)
            bytesByLayer[layer] = bytesByLayer.getOrDefault(layer, 0L) + sizeOf(index)
        }
        val sharedBytesByLayer = HashMap<Int, Long>()
        for ((index, layer) in sharedLayers) {
            sharedBytesByLayer[layer] = sharedBytesByLayer.getOrDefault(layer, 0L) + sizeOf(index)
        }
        return TensorBytes(
            onDemand = onDemand.sumOf { sizeOf(it) },
            experts = bytesByLayer.values.sum(),
            largestExpertLayer = bytesByLayer.values.maxOrNull() ?: 0L,
            largestSharedLayer = sharedBytesByLayer.values.maxOrNull() ?: 0L,
        )
    }

    private fun readValue(input: InputStream, type: Int, materialize: Boolean): Any? = when (type) {
        0 -> readByte(input).toUByte().toInt()
        1 -> readByte(input).toInt()
        2 -> readU16(input)
        3 -> readI16(input)
        4 -> readU32(input)
        5 -> readI32(input)
        6 -> Float.fromBits(readI32(input))
        7 -> readByte(input).toInt() != 0
        8 -> if (materialize) readString(input, 32 * 1024 * 1024) else {
            val length = readU64(input)
            skipFully(input, length)
            null
        }
        9 -> {
            val elementType = readU32(input).toInt()
            val count = readU64(input)
            if (count < 0 || count > 100_000_000L) throw IOException("Array GGUF inválido.")
            if (materialize && count <= 64) {
                val result = ArrayList<Any?>(count.toInt())
                repeat(count.toInt()) { result += readValue(input, elementType, true) }
                result
            } else {
                var n = 0L
                while (n < count) {
                    readValue(input, elementType, false)
                    n++
                }
                null
            }
        }
        10 -> readU64(input)
        11 -> readI64(input)
        12 -> Double.fromBits(readI64(input))
        else -> throw IOException("Tipo de metadado GGUF desconhecido: $type")
    }

    private fun readString(input: InputStream, maxBytes: Int): String {
        val length = readU64(input)
        if (length < 0 || length > maxBytes.toLong()) throw IOException("String GGUF grande demais.")
        val bytes = ByteArray(length.toInt())
        readFully(input, bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readByte(input: InputStream): Byte {
        val v = input.read()
        if (v < 0) throw IOException("Fim inesperado do arquivo GGUF.")
        return v.toByte()
    }

    private fun readU16(input: InputStream): Int {
        val b0 = readByte(input).toInt() and 0xff
        val b1 = readByte(input).toInt() and 0xff
        return b0 or (b1 shl 8)
    }

    private fun readI16(input: InputStream): Short = readU16(input).toShort()

    private fun readU32(input: InputStream): Long = readI32(input).toLong() and 0xffff_ffffL

    private fun readI32(input: InputStream): Int {
        var result = 0
        repeat(4) { index -> result = result or ((readByte(input).toInt() and 0xff) shl (8 * index)) }
        return result
    }

    private fun readU64(input: InputStream): Long = readI64(input)

    private fun readI64(input: InputStream): Long {
        var result = 0L
        repeat(8) { index -> result = result or ((readByte(input).toLong() and 0xffL) shl (8 * index)) }
        return result
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IOException("Fim inesperado do arquivo GGUF.")
            offset += read
        }
    }

    /** Counts the bytes consumed, to find where the tensor data starts. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }

    private companion object {
        const val DEFAULT_ALIGNMENT = 32L
        const val MAX_TENSORS = 1_000_000L
        const val MAX_DIMENSIONS = 8L
        /** Tensors llama.cpp reads row by row from the file when lazy mode is on, as the app sets it. */
        val ON_DEMAND_TENSORS = setOf("per_layer_token_embd.weight")
        /** Expert weights, named as llama.cpp's --cpu-moe matches them ("blk.3.ffn_down_exps.weight"). */
        val EXPERT_TENSOR = Regex("\\.ffn_(up|down|gate|gate_up)_(ch|)exps")
        val EXPERT_LAYER = Regex("^blk\\.(\\d+)\\.")
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        // File streams normally skip directly. A tokenizer can contain hundreds of thousands
        // of strings; allocating a buffer for every skipped string creates gigabytes of garbage.
        var scratch: ByteArray? = null
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val buffer = scratch ?: ByteArray(64 * 1024).also { scratch = it }
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Fim inesperado do arquivo GGUF.")
                remaining -= read
            }
        }
    }
}
