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
        /**
         * Weights of the types llama.cpp repacks for the ARM kernels (Q4_0, Q4_K, Q5_K, Q6_K, Q8_0,
         * IQ4_NL, MXFP4, Q1_0) outside [onDemandBytes]: what an optimized copy in RAM holds. Other
         * types (Q3_K, Q2_K, IQ3, IQ4_XS, Q2_0) stay read from the file and gain nothing from it.
         */
        val repackableBytes: Long = 0L,
        /** The part of [repackableBytes] outside the experts: the weights read for every token. */
        val fixedRepackableBytes: Long = 0L,
        /** Q6_K and Q8_0 weights, which the KleidiAI kernels turn into int8 at about 1.22 times their size. */
        val requantizedBytes: Long = 0L,
        /** Values of the largest Q6_K or Q8_0 tensor, which the repacking stages as int8 while loading. */
        val largestRequantizedValues: Long = 0L,
        /** Recurrent or hybrid model (Mamba, Gated DeltaNet, short convolutions): its state cannot be cut back. */
        val recurrent: Boolean = false,
        /** Experts of each mixture-of-experts layer ("<arch>.expert_count"); null for a dense model. */
        val expertCount: Int? = null,
        /**
         * Experts each token goes through ("<arch>.expert_used_count"), when the GGUF gives one number
         * for every layer: the value llama.cpp's metadata override can replace ("Modo rápido de MoE").
         */
        val expertUsedCount: Int? = null,
        /** The key of [expertUsedCount] as the file names it, for that override. */
        val expertUsedCountKey: String? = null,
        /** Layers with expert weights ("blk.N.ffn_*_exps"), in order; empty for a dense model. */
        val expertLayers: List<Int> = emptyList(),
        /** The part of [expertBytes] of the types the ARM kernels repack. */
        val expertRepackableBytes: Long = 0L,
        /** The part of [expertRepackableBytes] in Q6_K or Q8_0, which KleidiAI stores as int8. */
        val expertRequantizedBytes: Long = 0L,
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
        var hasChatTemplate = false
        var recurrent = false
        var alignment = DEFAULT_ALIGNMENT
        // Read before general.architecture may be known; matched to it after the loop.
        val expertCounts = HashMap<String, Int>()

        var i = 0L
        while (i < kvCount) {
            val key = readString(input, 2 * 1024 * 1024)
            val type = readU32(input).toInt()
            val wanted = key == "general.name" ||
                key == "general.architecture" ||
                key == "general.size_label" ||
                key == "tokenizer.chat_template" ||
                key == "general.alignment" ||
                key.endsWith(".context_length") ||
                key.endsWith(EXPERT_COUNT_SUFFIX) ||
                key.endsWith(EXPERT_USED_COUNT_SUFFIX)
            val value = readValue(input, type, wanted)
            if (RECURRENT_KEYS.any { key.endsWith(it) }) recurrent = true

            when (key) {
                "general.name" -> name = value as? String
                "general.architecture" -> architecture = value as? String
                "general.size_label" -> sizeLabel = value?.toString()
                "tokenizer.chat_template" -> hasChatTemplate = (value as? String)?.isNotBlank() == true
                "general.alignment" -> alignment = (value as? Number)?.toLong()?.takeIf { it > 0 } ?: alignment
                else -> if (key.endsWith(".context_length")) {
                    contextLength = when (value) {
                        is Number -> value.toInt()
                        else -> null
                    } ?: contextLength
                } else if (key.endsWith(EXPERT_COUNT_SUFFIX) || key.endsWith(EXPERT_USED_COUNT_SUFFIX)) {
                    // A per-layer array (a list here) is not one number to override.
                    (value as? Number)?.toLong()?.takeIf { it in 1..MAX_EXPERTS }?.let { expertCounts[key] = it.toInt() }
                }
            }
            i++
        }

        val expertUsedKey = architecture?.let { it + EXPERT_USED_COUNT_SUFFIX }?.takeIf { it in expertCounts }

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
            repackableBytes = tensorBytes.repackable,
            fixedRepackableBytes = tensorBytes.fixedRepackable,
            requantizedBytes = tensorBytes.requantized,
            largestRequantizedValues = tensorBytes.largestRequantizedValues,
            recurrent = recurrent,
            expertCount = architecture?.let { expertCounts[it + EXPERT_COUNT_SUFFIX] },
            expertUsedCount = expertUsedKey?.let { expertCounts[it] },
            expertUsedCountKey = expertUsedKey,
            expertLayers = tensorBytes.expertLayers,
            expertRepackableBytes = tensorBytes.expertRepackable,
            expertRequantizedBytes = tensorBytes.expertRequantized,
        )
    }

    private data class TensorBytes(
        val onDemand: Long = 0L,
        val experts: Long = 0L,
        val repackable: Long = 0L,
        val fixedRepackable: Long = 0L,
        val requantized: Long = 0L,
        val largestRequantizedValues: Long = 0L,
        val expertLayers: List<Int> = emptyList(),
        val expertRepackable: Long = 0L,
        val expertRequantized: Long = 0L,
    )

    /**
     * Reads the tensor table that follows the metadata. A tensor's size is the distance to the next
     * tensor's data (the last one ends at the file end), which needs no table of block sizes.
     */
    private fun readTensorBytes(input: CountingInputStream, tensorCount: Long, alignment: Long, totalBytes: Long?): TensorBytes {
        if (tensorCount < 0 || tensorCount > MAX_TENSORS) throw IOException("Cabeçalho GGUF inválido.")
        val count = tensorCount.toInt()
        val offsets = LongArray(count)
        val types = IntArray(count)
        val values = LongArray(count)
        val onDemand = BooleanArray(count)
        val expert = BooleanArray(count)
        val expertLayers = sortedSetOf<Int>()
        for (index in 0 until count) {
            val name = readString(input, 64 * 1024)
            val dimensions = readU32(input)
            if (dimensions > MAX_DIMENSIONS) throw IOException("Tensor GGUF inválido.")
            var elements = 1L
            repeat(dimensions.toInt()) { elements *= readU64(input).coerceAtLeast(1L) }
            types[index] = readU32(input).toInt()
            values[index] = elements
            offsets[index] = readU64(input)
            onDemand[index] = name in ON_DEMAND_TENSORS
            expert[index] = EXPERT_TENSOR.containsMatchIn(name)
            if (expert[index]) LAYER_PREFIX.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { expertLayers += it }
        }
        val dataStart = (input.count + alignment - 1) / alignment * alignment
        val dataEnd = totalBytes?.minus(dataStart)
        val sorted = offsets.sortedArray()
        fun sizeOf(index: Int): Long {
            val start = offsets[index]
            var next = sorted.binarySearch(start) + 1
            while (next < sorted.size && sorted[next] <= start) next++
            val end = if (next < sorted.size) sorted[next] else dataEnd ?: start
            return (end - start).coerceAtLeast(0L)
        }
        var onDemandBytes = 0L
        var expertBytes = 0L
        var repackable = 0L
        var fixedRepackable = 0L
        var requantized = 0L
        var largestRequantized = 0L
        var expertRepackable = 0L
        var expertRequantized = 0L
        for (index in 0 until count) {
            val size = sizeOf(index)
            if (onDemand[index]) {
                onDemandBytes += size
                continue
            }
            if (expert[index]) expertBytes += size
            if (types[index] !in REPACKABLE_TYPES) continue
            repackable += size
            if (!expert[index]) fixedRepackable += size else expertRepackable += size
            if (types[index] in REQUANTIZED_TYPES) {
                requantized += size
                if (expert[index]) expertRequantized += size
                largestRequantized = maxOf(largestRequantized, values[index])
            }
        }
        return TensorBytes(
            onDemandBytes, expertBytes, repackable, fixedRepackable, requantized, largestRequantized,
            expertLayers.toList(), expertRepackable, expertRequantized,
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
        const val EXPERT_COUNT_SUFFIX = ".expert_count"
        const val EXPERT_USED_COUNT_SUFFIX = ".expert_used_count"
        /** llama.cpp's LLAMA_MAX_EXPERTS in the pinned version. */
        const val MAX_EXPERTS = 1024L
        /** Tensors llama.cpp reads row by row from the file when lazy mode is on, as the app sets it. */
        val ON_DEMAND_TENSORS = setOf("per_layer_token_embd.weight")
        /** Expert weights, named as llama.cpp's --cpu-moe matches them ("blk.3.ffn_down_exps.weight"). */
        val EXPERT_TENSOR = Regex("\\.ffn_(up|down|gate|gate_up)_(ch|)exps")
        /** The layer of a tensor named "blk.N....". */
        val LAYER_PREFIX = Regex("^blk\\.(\\d+)\\.")
        /**
         * ggml types with repacked ARM kernels in the pinned llama.cpp (ggml-cpu repack and KleidiAI):
         * Q4_0, Q8_0, Q4_K, Q5_K, Q6_K, IQ4_NL, MXFP4 and Q1_0.
         */
        val REPACKABLE_TYPES = setOf(2, 8, 12, 13, 14, 20, 39, 41)
        /** Q8_0 and Q6_K, which KleidiAI stores as int8 per channel. */
        val REQUANTIZED_TYPES = setOf(8, 14)
        /** Metadata of recurrent layers: Mamba, Gated DeltaNet (Qwen3.5/3.6) and short convolutions (LFM2). */
        val RECURRENT_KEYS = listOf(".ssm.conv_kernel", ".ssm.state_size", ".shortconv.l_cache", ".full_attention_interval")
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Fim inesperado do arquivo GGUF.")
                remaining -= read
            }
        }
    }
}
