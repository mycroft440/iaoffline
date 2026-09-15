package com.example.ialocal.ui.codeeditor

import androidx.compose.ui.text.TextRange

enum class CodeIssueSeverity {
    ERROR,
    WARNING,
    INFO,
}

enum class CodeIssueCategory {
    SYNTAX,
    TYPE,
    REFERENCE,
    LOGIC,
    SECURITY,
    COMPATIBILITY,
}

enum class CodeIssueSource {
    LOCAL,
    AI,
}

data class CodeIssue(
    val id: String,
    val title: String,
    val explanation: String,
    val startLine: Int,
    val endLine: Int,
    val original: String,
    val replacement: String,
    val severity: CodeIssueSeverity,
    val category: CodeIssueCategory = CodeIssueCategory.SYNTAX,
    val column: Int? = null,
    val canAutoFix: Boolean = original.isNotEmpty(),
    val source: CodeIssueSource = CodeIssueSource.AI,
)

object CodePatchEngine {
    fun selectionRange(code: String, issue: CodeIssue): TextRange? {
        findExactRange(code, issue)?.let { return it }

        if (code.isEmpty()) return null
        val start = offsetForLine(code, issue.startLine.coerceAtLeast(1))
        val end = offsetForLine(code, issue.endLine.coerceAtLeast(issue.startLine) + 1)
            .coerceAtMost(code.length)

        if (start > code.length || end <= start) return null
        return TextRange(start, end)
    }

    fun applyIssue(code: String, issue: CodeIssue): String? {
        if (!issue.canAutoFix || issue.original.isEmpty()) return null
        val range = findExactRange(code, issue) ?: return null
        return code.replaceRange(range.start, range.end, issue.replacement)
    }

    fun applyAll(code: String, issues: List<CodeIssue>): Pair<String, Int> {
        var updated = code
        var applied = 0

        issues.filter { it.canAutoFix }.forEach { issue ->
            applyIssue(updated, issue)?.let { next ->
                updated = next
                applied += 1
            }
        }

        return updated to applied
    }

    private fun findExactRange(code: String, issue: CodeIssue): TextRange? {
        if (issue.original.isEmpty()) return null

        val lineHint = offsetForLine(code, issue.startLine.coerceAtLeast(1))
        val lineEnd = offsetForLine(code, issue.endLine.coerceAtLeast(issue.startLine) + 1)
            .coerceAtMost(code.length)

        val nearHint = code.indexOf(issue.original, startIndex = lineHint.coerceAtMost(code.length))
        if (nearHint >= 0 && nearHint <= lineEnd) {
            return TextRange(nearHint, nearHint + issue.original.length)
        }

        val anywhere = code.indexOf(issue.original)
        return anywhere.takeIf { it >= 0 }?.let {
            TextRange(it, it + issue.original.length)
        }
    }

    private fun offsetForLine(code: String, lineNumber: Int): Int {
        if (lineNumber <= 1) return 0

        var line = 1
        code.forEachIndexed { index, char ->
            if (char == '\n') {
                line += 1
                if (line == lineNumber) return index + 1
            }
        }
        return code.length
    }
}
