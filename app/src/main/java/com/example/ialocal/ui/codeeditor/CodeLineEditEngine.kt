package com.example.ialocal.ui.codeeditor

import kotlin.math.max
import kotlin.math.min

data class CodeEditTarget(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
    val original: String,
    val explicitSelection: Boolean,
) {
    init {
        require(startOffset >= 0)
        require(endOffset >= startOffset)
        require(startLine >= 1)
        require(endLine >= startLine)
    }

    fun label(): String = when {
        explicitSelection && startLine == endLine -> "Trecho da linha $startLine"
        explicitSelection -> "Trecho das linhas $startLine–$endLine"
        else -> "Linha $startLine"
    }
}

object CodeLineEditEngine {
    fun targetForSelection(code: String, selectionStart: Int, selectionEnd: Int): CodeEditTarget {
        val safeStart = min(selectionStart, selectionEnd).coerceIn(0, code.length)
        val safeEnd = max(selectionStart, selectionEnd).coerceIn(0, code.length)
        val explicitSelection = safeEnd > safeStart
        val targetStart = if (explicitSelection) safeStart else lineStartOffset(code, safeStart)
        val targetEnd = if (explicitSelection) safeEnd else lineEndOffset(code, safeStart)
        val endAnchor = if (targetEnd > targetStart) targetEnd - 1 else targetEnd
        return CodeEditTarget(
            targetStart,
            targetEnd,
            lineForOffset(code, targetStart),
            lineForOffset(code, endAnchor),
            code.substring(targetStart, targetEnd),
            explicitSelection,
        )
    }

    fun numberedContext(code: String, target: CodeEditTarget, contextLines: Int = 18): String {
        val total = lineCount(code)
        val first = max(1, target.startLine - contextLines)
        val last = min(total, target.endLine + contextLines)
        val width = last.toString().length
        val lines = code.split('\n')
        return buildString {
            if (first > 1) appendLine("…")
            for (lineNumber in first..last) {
                append(lineNumber.toString().padStart(width, ' '))
                append(" | ")
                appendLine(lines.getOrElse(lineNumber - 1) { "" }.take(MAX_CONTEXT_LINE_CHARS))
            }
            if (last < total) append("…")
        }.take(MAX_CONTEXT_CHARS)
    }

    private fun lineForOffset(code: String, offset: Int): Int {
        var line = 1
        for (index in 0 until offset.coerceIn(0, code.length)) if (code[index] == '\n') line += 1
        return line
    }

    private fun lineStartOffset(code: String, offset: Int): Int {
        if (code.isEmpty()) return 0
        val safeOffset = offset.coerceIn(0, code.length)
        if (safeOffset == 0) return 0
        return code.lastIndexOf('\n', safeOffset - 1).let { if (it < 0) 0 else it + 1 }
    }

    private fun lineEndOffset(code: String, offset: Int): Int {
        if (code.isEmpty()) return 0
        val nextBreak = code.indexOf('\n', offset.coerceIn(0, code.length))
        return if (nextBreak < 0) code.length else nextBreak
    }

    private fun lineCount(code: String): Int = code.count { it == '\n' } + 1
    private const val MAX_CONTEXT_LINE_CHARS = 800
    private const val MAX_CONTEXT_CHARS = 14_000
}
