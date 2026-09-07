package com.example.ialocal.models

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
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
    )

    fun inspect(file: File): Metadata = BufferedInputStream(FileInputStream(file), 256 * 1024).use(::inspect)

    fun inspect(raw: InputStream): Metadata {
        val input = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 256 * 1024)
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

        var i = 0L
        while (i < kvCount) {
            val key = readString(input, 2 * 1024 * 1024)
            val type = readU32(input).toInt()
            val wanted = key == "general.name" ||
                key == "general.architecture" ||
                key == "general.size_label" ||
                key == "tokenizer.chat_template" ||
                key.endsWith(".context_length")
            val value = readValue(input, type, wanted)

            when (key) {
                "general.name" -> name = value as? String
                "general.architecture" -> architecture = value as? String
                "general.size_label" -> sizeLabel = value?.toString()
                "tokenizer.chat_template" -> hasChatTemplate = (value as? String)?.isNotBlank() == true
                else -> if (key.endsWith(".context_length")) {
                    contextLength = when (value) {
                        is Number -> value.toInt()
                        else -> null
                    } ?: contextLength
                }
            }
            i++
        }

        return Metadata(
            version = version,
            tensorCount = tensorCount,
            kvCount = kvCount,
            name = name,
            architecture = architecture,
            sizeLabel = sizeLabel,
            contextLength = contextLength,
            hasChatTemplate = hasChatTemplate,
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
