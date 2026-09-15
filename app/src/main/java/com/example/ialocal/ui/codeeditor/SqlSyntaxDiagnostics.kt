package com.example.ialocal.ui.codeeditor

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil

/**
 * Deterministic SQL syntax validation.
 *
 * This deliberately validates syntax only. It does not need a live database/schema, so names such
 * as tables and columns are not reported as invalid merely because the editor cannot resolve them.
 */
object SqlSyntaxDiagnostics {
    private val locationRegex = Regex("(?i)\\bline\\s+(\\d+)\\s*,\\s*column\\s+(\\d+)")

    fun analyze(code: String): List<CodeIssue> {
        if (code.isBlank()) return emptyList()

        return try {
            // In JSqlParser 5.3, enabling complex parsing is important here: the simple
            // parseStatements overload can otherwise swallow the first parse failure and return
            // null. With complex parsing enabled, an invalid statement is retried and the final
            // parser error is propagated deterministically.
            CCJSqlParserUtil.parseStatements(code) { parser ->
                parser.withAllowComplexParsing(true)
            }
            emptyList()
        } catch (error: JSQLParserException) {
            listOf(toIssue(code, error))
        } catch (error: RuntimeException) {
            // Parser implementations may wrap lexical/parser failures in a runtime exception.
            listOf(toIssue(code, error))
        }
    }

    private fun toIssue(code: String, error: Throwable): CodeIssue {
        val messages = causeMessages(error)
        val location = messages
            .asSequence()
            .mapNotNull { message -> locationRegex.find(message) }
            .firstOrNull()

        val line = location?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val column = location?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1)
        val sourceLine = code.lineSequence().drop(line - 1).firstOrNull().orEmpty()

        val parserMessage = messages
            .firstOrNull { it.contains("Encountered", ignoreCase = true) || it.contains("unexpected", ignoreCase = true) }
            ?: messages.firstOrNull()
            ?: "O parser SQL rejeitou a instrução."

        return CodeIssue(
            id = "sql-syntax-$line-${column ?: 0}",
            title = "Erro de sintaxe SQL",
            explanation = normalizeMessage(parserMessage),
            startLine = line,
            endLine = line,
            original = sourceLine,
            replacement = sourceLine,
            severity = CodeIssueSeverity.ERROR,
            category = CodeIssueCategory.SYNTAX,
            column = column,
            canAutoFix = false,
            source = CodeIssueSource.LOCAL,
        )
    }

    private fun causeMessages(error: Throwable): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            current.message?.takeIf { it.isNotBlank() }?.let(result::add)
            current = current.cause
        }
        return result.reversed()
    }

    private fun normalizeMessage(message: String): String {
        val compact = message
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

        return compact.take(700)
    }
}
