package com.example.ialocal.ui.codeeditor

object LocalCodeDiagnostics {
    private data class OpenDelimiter(val char: Char, val offset: Int)

    fun analyze(code: String, language: CodeLanguageProfile): List<CodeIssue> {
        if (code.isBlank()) return emptyList()
        val issues = mutableListOf<CodeIssue>()
        issues += delimiterDiagnostics(code, language)
        if (language.id == "html") issues += htmlDiagnostics(code)
        if (language.id == "sql") issues += SqlSyntaxDiagnostics.analyze(code)
        return issues.distinctBy { Triple(it.startLine, it.original, it.title) }
    }

    private fun delimiterDiagnostics(code: String, language: CodeLanguageProfile): List<CodeIssue> {
        val stack = ArrayDeque<OpenDelimiter>()
        val issues = mutableListOf<CodeIssue>()
        var i = 0
        var quote: Char? = null
        var tripleQuote: String? = null
        var escaped = false
        var blockComment = false

        while (i < code.length) {
            if (blockComment) {
                if (code.startsWith("*/", i)) {
                    blockComment = false
                    i += 2
                } else {
                    i += 1
                }
                continue
            }

            tripleQuote?.let { marker ->
                if (code.startsWith(marker, i)) {
                    tripleQuote = null
                    i += marker.length
                } else {
                    i += 1
                }
                return@let
            }
            if (tripleQuote != null) continue

            val current = code[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if (current == quote) {
                    quote = null
                }
                i += 1
                continue
            }

            if (language.supportsBlockComments && code.startsWith("/*", i)) {
                blockComment = true
                i += 2
                continue
            }

            val commentToken = language.lineCommentTokens.firstOrNull { token -> code.startsWith(token, i) }
            if (commentToken != null) {
                i = code.indexOf('\n', i).let { if (it < 0) code.length else it + 1 }
                continue
            }

            if ((current == '"' || current == '\'') && i + 2 < code.length) {
                val marker = "$current$current$current"
                if (code.startsWith(marker, i)) {
                    tripleQuote = marker
                    i += 3
                    continue
                }
            }

            if (current == '"' || current == '\'' || current == '`') {
                quote = current
                i += 1
                continue
            }

            when (current) {
                '(', '[', '{' -> stack.addLast(OpenDelimiter(current, i))
                ')', ']', '}' -> {
                    val expectedOpen = when (current) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    val open = stack.lastOrNull()
                    if (open == null || open.char != expectedOpen) {
                        val (line, column) = lineColumn(code, i)
                        issues += CodeIssue(
                            id = "local-unexpected-$i",
                            title = "Delimitador inesperado '$current'",
                            explanation = "Há um '$current' sem o delimitador de abertura correspondente.",
                            startLine = line,
                            endLine = line,
                            original = current.toString(),
                            replacement = "",
                            severity = CodeIssueSeverity.ERROR,
                            category = CodeIssueCategory.SYNTAX,
                            column = column,
                            canAutoFix = true,
                            source = CodeIssueSource.LOCAL,
                        )
                    } else {
                        stack.removeLast()
                    }
                }
            }
            i += 1
        }

        if (quote != null || tripleQuote != null) {
            val offset = code.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val (line, column) = lineColumn(code, offset)
            issues += CodeIssue(
                id = "local-string-unclosed",
                title = "String não finalizada",
                explanation = "Foi encontrada uma string sem fechamento antes do fim do código.",
                startLine = line,
                endLine = line,
                original = "",
                replacement = "",
                severity = CodeIssueSeverity.ERROR,
                category = CodeIssueCategory.SYNTAX,
                column = column,
                canAutoFix = false,
                source = CodeIssueSource.LOCAL,
            )
        }

        stack.forEach { open ->
            val (line, column) = lineColumn(code, open.offset)
            val expected = when (open.char) {
                '(' -> ')'
                '[' -> ']'
                else -> '}'
            }
            issues += CodeIssue(
                id = "local-unclosed-${open.offset}",
                title = "Delimitador '${open.char}' não fechado",
                explanation = "O bloco aberto aqui precisa ser fechado com '$expected'.",
                startLine = line,
                endLine = line,
                original = open.char.toString(),
                replacement = open.char.toString(),
                severity = CodeIssueSeverity.ERROR,
                category = CodeIssueCategory.SYNTAX,
                column = column,
                canAutoFix = false,
                source = CodeIssueSource.LOCAL,
            )
        }

        return issues
    }

    private fun htmlDiagnostics(code: String): List<CodeIssue> {
        val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        val tagRegex = Regex("<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9:-]*)([^>]*)>")
        val stack = ArrayDeque<Pair<String, Int>>()
        val issues = mutableListOf<CodeIssue>()

        tagRegex.findAll(code).forEach { match ->
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val tail = match.groupValues[3]
            val selfClosing = tail.trimEnd().endsWith("/") || name in voidTags
            if (selfClosing) return@forEach

            if (!closing) {
                stack.addLast(name to match.range.first)
            } else {
                val open = stack.lastOrNull()
                if (open == null || open.first != name) {
                    val (line, column) = lineColumn(code, match.range.first)
                    issues += CodeIssue(
                        id = "html-close-${match.range.first}",
                        title = "Tag de fechamento inesperada </$name>",
                        explanation = "A tag </$name> não corresponde à última tag HTML aberta.",
                        startLine = line,
                        endLine = line,
                        original = match.value,
                        replacement = "",
                        severity = CodeIssueSeverity.ERROR,
                        category = CodeIssueCategory.SYNTAX,
                        column = column,
                        canAutoFix = false,
                        source = CodeIssueSource.LOCAL,
                    )
                } else {
                    stack.removeLast()
                }
            }
        }

        stack.forEach { (name, offset) ->
            val (line, column) = lineColumn(code, offset)
            issues += CodeIssue(
                id = "html-open-$offset",
                title = "Tag <$name> não fechada",
                explanation = "A estrutura HTML abriu <$name> e não encontrou </$name>.",
                startLine = line,
                endLine = line,
                original = "<$name",
                replacement = "<$name",
                severity = CodeIssueSeverity.ERROR,
                category = CodeIssueCategory.SYNTAX,
                column = column,
                canAutoFix = false,
                source = CodeIssueSource.LOCAL,
            )
        }
        return issues
    }

    private fun lineColumn(code: String, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, code.length)
        var line = 1
        var column = 1
        for (index in 0 until safeOffset) {
            if (code[index] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return line to column
    }
}
