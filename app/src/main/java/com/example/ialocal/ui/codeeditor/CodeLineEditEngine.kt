package com.example.ialocal.ui.codeeditor

import kotlin.math.max
import kotlin.math.min

data class CodeLineRange(
    val startLine: Int,
    val endLine: Int,
) {
    init {
        require(startLine >= 1)
        require(endLine >= startLine)
    }

    val lineCount: Int get() = endLine - startLine + 1

    fun label(): String =
        if (startLine == endLine) "Linha " + startLine else "Linhas " + startLine + "–" + endLine
}

object CodeLineEditEngine {
    fun lineRangeForSelection(
        code: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): CodeLineRange {
        val safeStart = min(selectionStart, selectionEnd).coerceIn(0, code.length)
        val safeEnd = max(selectionStart, selectionEnd).coerceIn(0, code.length)
        val endAnchor = if (safeEnd > safeStart) safeEnd - 1 else safeEnd

        return CodeLineRange(
            startLine = lineForOffset(code, safeStart),
            endLine = lineForOffset(code, endAnchor),
        )
    }

    fun extractLines(code: String, range: CodeLineRange): String? {
        if (range.endLine > lineCount(code)) return null
        val start = lineStartOffset(code, range.startLine) ?: return null
        val end = lineContentEndOffset(code, range.endLine) ?: return null
        return code.substring(start, end)
    }

    fun applyExactLines(
        code: String,
        range: CodeLineRange,
        replacement: String,
    ): String? {
        if (range.endLine > lineCount(code)) return null
        if (replacementLineCount(replacement) != range.lineCount) return null

        val start = lineStartOffset(code, range.startLine) ?: return null
        val end = lineContentEndOffset(code, range.endLine) ?: return null
        return code.replaceRange(start, end, replacement)
    }

    fun replacementLineCount(replacement: String): Int =
        replacement.count { it == '\n' } + 1

    fun numberedContext(
        code: String,
        range: CodeLineRange,
        contextLines: Int = 18,
    ): String {
        val total = lineCount(code)
        val first = max(1, range.startLine - contextLines)
        val last = min(total, range.endLine + contextLines)
        val width = last.toString().length
        val lines = code.split('\n')

        return buildString {
            if (first > 1) appendLine("…")
            for (lineNumber in first..last) {
                val text = lines.getOrElse(lineNumber - 1) { "" }
                append(lineNumber.toString().padStart(width, ' '))
                append(" | ")
                appendLine(text.take(MAX_CONTEXT_LINE_CHARS))
            }
            if (last < total) append("…")
        }.take(MAX_CONTEXT_CHARS)
    }

    private fun lineForOffset(code: String, offset: Int): Int {
        var line = 1
        for (index in 0 until offset.coerceIn(0, code.length)) {
            if (code[index] == '\n') line += 1
        }
        return line
    }

    private fun lineCount(code: String): Int = code.count { it == '\n' } + 1

    private fun lineStartOffset(code: String, lineNumber: Int): Int? {
        if (lineNumber < 1) return null
        if (lineNumber == 1) return 0

        var line = 1
        code.forEachIndexed { index, char ->
            if (char == '\n') {
                line += 1
                if (line == lineNumber) return index + 1
            }
        }
        return null
    }

    private fun lineContentEndOffset(code: String, lineNumber: Int): Int? {
        val total = lineCount(code)
        if (lineNumber !in 1..total) return null
        if (lineNumber == total) return code.length
        val nextLineStart = lineStartOffset(code, lineNumber + 1) ?: return null
        return (nextLineStart - 1).coerceAtLeast(0)
    }

    private const val MAX_CONTEXT_LINE_CHARS = 800
    private const val MAX_CONTEXT_CHARS = 14_000
}
