package com.example.ialocal.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A text file as the tools see it: the text with "\n" line breaks, plus how the file wrote them,
 * so an edited copy keeps the original's line breaks.
 */
data class TextContent(
    val text: String,
    /** How the bytes were read: "UTF-8", "UTF-16" or "Windows-1252". */
    val encoding: String = "UTF-8",
    val lineSeparator: String = "\n",
    val byteOrderMark: Boolean = false,
) {
    val lines: List<String> by lazy { TextFiles.lines(text) }
}

/** Part of a file for the model, lines numbered as "12| texto". */
data class TextExcerpt(val text: String, val totalLines: Int, val note: String? = null)

/** An edited text and what each operation changed, for the model to report. */
data class TextEditResult(val text: String, val changes: List<String>)

/** Edits applied by [TextFiles.edit], in order; line numbers refer to the text as it is at that step. */
sealed class TextEdit {
    /** Replaces [find] (exact text) with [replacement]: every occurrence, or only the first. */
    data class Replace(val find: String, val replacement: String, val all: Boolean = true) : TextEdit()
    data class Append(val text: String) : TextEdit()
    data class Prepend(val text: String) : TextEdit()
    /** Inserts lines after line [afterLine] (1-based; 0 inserts at the start). */
    data class InsertLines(val afterLine: Int, val text: String) : TextEdit()
    data class DeleteLines(val lines: String) : TextEdit()
    /** Replaces a continuous range of lines, such as "3-5", with [text] (empty removes them). */
    data class ReplaceLines(val lines: String, val text: String) : TextEdit()
}

/**
 * Reads, creates and edits plain-text files. Files are read as UTF-8 (or UTF-16 with a BOM) and,
 * when they are not valid UTF-8, as Windows-1252, common in older Brazilian files. Everything is
 * written as UTF-8, keeping the source's line breaks and byte order mark.
 */
object TextFiles {
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_CREATED_CHARS = 1_000_000
    private const val MAX_TEXT_CHARS = 12_000_000
    private const val PREVIEW_BYTES = 512 * 1024

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** The "12| " gutter [read] puts before each line. */
    private val LINE_NUMBER = Regex("^\\d+\\| ?")

    fun load(file: File): TextContent {
        require(file.isFile) { "O arquivo não está mais disponível." }
        require(file.length() <= MAX_FILE_BYTES) { "Arquivo grande demais (máximo de ${MAX_FILE_BYTES / (1024 * 1024)} MB)." }
        return decode(file.readBytes())
    }

    /** The start of [file] for showing it, and whether the file goes on after that. */
    fun preview(file: File): Pair<TextContent, Boolean> {
        require(file.isFile) { "O arquivo não está mais disponível." }
        val size = minOf(file.length(), PREVIEW_BYTES.toLong()).toInt()
        val bytes = ByteArray(size)
        var read = 0
        file.inputStream().use { input ->
            while (read < size) {
                val count = input.read(bytes, read, size - read)
                if (count < 0) break
                read += count
            }
        }
        val truncated = file.length() > read
        val start = bytes.copyOf(read)
        return decode(if (truncated) withoutCutCharacter(start) else start) to truncated
    }

    fun decode(bytes: ByteArray): TextContent {
        val bom = when {
            bytes.startsWith(UTF8_BOM) -> Charsets.UTF_8 to UTF8_BOM.size
            bytes.startsWith(UTF16LE_BOM) -> Charsets.UTF_16LE to UTF16LE_BOM.size
            bytes.startsWith(UTF16BE_BOM) -> Charsets.UTF_16BE to UTF16BE_BOM.size
            else -> null
        }
        val raw: String
        val encoding: String
        if (bom != null) {
            raw = String(bytes, bom.second, bytes.size - bom.second, bom.first)
            encoding = if (bom.first == Charsets.UTF_8) "UTF-8" else "UTF-16"
        } else {
            val utf8 = strictUtf8(bytes)
            raw = utf8 ?: String(bytes, WINDOWS_1252)
            encoding = if (utf8 != null) "UTF-8" else "Windows-1252"
        }
        require('\u0000' !in raw) { "O arquivo não parece ser de texto." }
        return TextContent(
            text = normalize(raw),
            encoding = encoding,
            lineSeparator = if ("\r\n" in raw) "\r\n" else "\n",
            byteOrderMark = bom != null,
        )
    }

    /** [text] ("\n" line breaks) as UTF-8 bytes, with [lineSeparator] and an optional BOM. */
    fun encode(text: String, lineSeparator: String = "\n", byteOrderMark: Boolean = false): ByteArray {
        val body = (if (lineSeparator == "\n") text else text.replace("\n", lineSeparator)).toByteArray(Charsets.UTF_8)
        return if (byteOrderMark) UTF8_BOM + body else body
    }

    /** The text of a new file: [content] with "\n" line breaks, ending in one. */
    fun created(content: String): String {
        require(content.length <= MAX_CREATED_CHARS) { "Texto longo demais (máximo de $MAX_CREATED_CHARS caracteres)." }
        val text = normalize(content)
        return if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
    }

    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    /**
     * Numbered lines of [content] for the model: [lines] (all when null), only those containing
     * [query] when one is given, up to [maxChars].
     */
    fun read(content: TextContent, lines: String?, query: String?, maxChars: Int): TextExcerpt {
        val all = content.lines
        if (all.isEmpty()) return TextExcerpt("", 0, "O arquivo está vazio.")
        var indexes = LineRanges.parse(lines, all.size)
        if (query != null) {
            indexes = indexes.filter { all[it].contains(query, ignoreCase = true) }
            if (indexes.isEmpty()) return TextExcerpt("", all.size, "Nenhuma linha contém \"$query\".")
        }
        val text = StringBuilder()
        var stoppedAt: Int? = null
        for (index in indexes) {
            val line = all[index]
            val shown = if (line.length > maxChars) line.take(maxChars) + " [… linha cortada]" else line
            val entry = "${index + 1}| $shown\n"
            if (text.isNotEmpty() && text.length + entry.length > maxChars) {
                stoppedAt = index + 1
                break
            }
            text.append(entry)
        }
        val note = stoppedAt?.let {
            if (query != null) "Há mais linhas com \"$query\" a partir da linha $it; use lines para continuar."
            else "Texto cortado; para continuar, leia com lines \"$it-\"."
        }
        return TextExcerpt(text.toString().trimEnd('\n'), all.size, note)
    }

    fun edit(text: String, operations: List<TextEdit>): TextEditResult {
        require(operations.isNotEmpty()) { "Informe 'operations' com ao menos uma operação." }
        var current = text
        val changes = operations.map { operation ->
            val (next, change) = apply(current, operation)
            current = next
            change
        }
        return TextEditResult(current, changes)
    }

    private fun apply(text: String, operation: TextEdit): Pair<String, String> {
        val endsWithBreak = text.isEmpty() || text.endsWith('\n')
        return when (operation) {
            is TextEdit.Replace -> {
                val find = normalize(withoutLineNumbers(operation.find))
                require(find.isNotEmpty()) { "Em replace, 'find' não pode ser vazio." }
                val replacement = normalize(withoutLineNumbers(operation.replacement))
                val count = occurrences(text, find)
                require(count > 0) {
                    val hint = if (text.contains(find, ignoreCase = true)) " Ele existe com maiúsculas e minúsculas diferentes." else ""
                    "Texto não encontrado: \"${find.take(80)}\".$hint Leia o arquivo com txt_read e copie o trecho exatamente como está, sem os números das linhas."
                }
                if (operation.all || count == 1) {
                    require(text.length.toLong() + count.toLong() * (replacement.length - find.length) <= MAX_TEXT_CHARS) {
                        "O texto ficaria grande demais."
                    }
                    text.replace(find, replacement) to "trocado em $count ${if (count == 1) "lugar" else "lugares"}"
                } else {
                    text.replaceFirst(find, replacement) to "trocada a primeira de $count ocorrências"
                }
            }
            is TextEdit.Append -> {
                val added = block(operation.text, "append")
                // Models often start the text with a line break of their own, as if gluing it to the
                // last line; then it goes right after that line, not after an extra blank one.
                val last = if (endsWithBreak) text.removeSuffix("\n") else text
                val separator = if (last.isEmpty() || added.startsWith("\n")) "" else "\n"
                last + separator + added + (if (endsWithBreak) "\n" else "") to "texto acrescentado no fim"
            }
            is TextEdit.Prepend -> {
                val added = block(operation.text, "prepend")
                added + "\n" + text to "texto acrescentado no início"
            }
            is TextEdit.InsertLines -> {
                val lines = lines(text)
                require(operation.afterLine in 0..lines.size) {
                    "Em insert_lines, after_line vai de 0 (antes da primeira linha) a ${lines.size} (depois da última)."
                }
                val added = block(operation.text, "insert_lines").split('\n')
                join(lines.take(operation.afterLine) + added + lines.drop(operation.afterLine), endsWithBreak) to
                    "${added.size} ${if (added.size == 1) "linha inserida" else "linhas inseridas"} depois da linha ${operation.afterLine}"
            }
            is TextEdit.DeleteLines -> {
                val lines = lines(text)
                val removed = LineRanges.parse(operation.lines, lines.size).toSet()
                join(lines.filterIndexed { index, _ -> index !in removed }, endsWithBreak) to
                    "${removed.size} ${if (removed.size == 1) "linha apagada" else "linhas apagadas"}"
            }
            is TextEdit.ReplaceLines -> {
                val lines = lines(text)
                val range = LineRanges.parse(operation.lines, lines.size).distinct().sorted()
                require(range.last() - range.first() + 1 == range.size) {
                    "Em replace_lines, 'lines' deve ser um intervalo contínuo, como 3-5."
                }
                val added = if (operation.text.isEmpty()) emptyList() else normalize(withoutLineNumbers(operation.text)).removeSuffix("\n").split('\n')
                join(lines.take(range.first()) + added + lines.drop(range.last() + 1), endsWithBreak) to
                    "${if (range.size == 1) "linha ${range.first() + 1} trocada" else "linhas ${range.first() + 1}-${range.last() + 1} trocadas"} por ${added.size} ${if (added.size == 1) "linha" else "linhas"}"
            }
        }
    }

    /** Text the model gives to add, without a final line break (the edit adds its own). */
    private fun block(text: String, operation: String): String {
        require(text.isNotEmpty()) { "Em $operation, 'text' não pode ser vazio." }
        return normalize(withoutLineNumbers(text)).removeSuffix("\n")
    }

    /** Small models copy lines from txt_read with the "12| " numbers; those are not part of the text. */
    fun withoutLineNumbers(text: String): String {
        val lines = text.split('\n')
        val filled = lines.filter { it.isNotBlank() }
        if (filled.isEmpty() || !filled.all { LINE_NUMBER.containsMatchIn(it) }) return text
        return lines.joinToString("\n") { it.replaceFirst(LINE_NUMBER, "") }
    }

    private fun join(lines: List<String>, endsWithBreak: Boolean): String =
        if (lines.isEmpty()) "" else lines.joinToString("\n") + if (endsWithBreak) "\n" else ""

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun occurrences(text: String, find: String): Int {
        var count = 0
        var index = text.indexOf(find)
        while (index >= 0) {
            count++
            index = text.indexOf(find, index + find.length)
        }
        return count
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** [bytes] without a UTF-8 character that the end of a preview cut in half. */
    private fun withoutCutCharacter(bytes: ByteArray): ByteArray {
        var start = bytes.size - 1
        while (start > 0 && bytes.size - start < 4 && (bytes[start].toInt() and 0xC0) == 0x80) start--
        if (start < 0) return bytes
        val lead = bytes[start].toInt() and 0xFF
        val length = when {
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> 1
        }
        return if (start + length > bytes.size) bytes.copyOf(start) else bytes
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
