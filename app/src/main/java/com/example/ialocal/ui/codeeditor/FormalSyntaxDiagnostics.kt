package com.example.ialocal.ui.codeeditor

import io.xberg.tslp.android.DiagnosticSeverity
import io.xberg.tslp.android.LanguageRegistry
import io.xberg.tslp.android.ProcessConfig
import io.xberg.tslp.android.TreeSitterLanguagePack

enum class SyntaxValidationState {
    IDLE,
    CHECKING,
    VALID,
    INVALID,
    PARSER_UNAVAILABLE,
}

data class FormalSyntaxResult(
    val state: SyntaxValidationState,
    val parserName: String? = null,
    val message: String? = null,
    val issues: List<CodeIssue> = emptyList(),
)

object FormalSyntaxDiagnostics {
    suspend fun analyze(code: String, language: CodeLanguageProfile): FormalSyntaxResult =
        when (language.syntaxBackend) {
            SyntaxBackend.SQL_JSQLPARSER -> analyzeSql(code)
            SyntaxBackend.TREE_SITTER -> analyzeTreeSitter(code, language)
            SyntaxBackend.NONE -> FormalSyntaxResult(
                state = SyntaxValidationState.PARSER_UNAVAILABLE,
                message = "Não há parser formal registrado para ${language.displayName}.",
            )
        }

    private fun analyzeSql(code: String): FormalSyntaxResult {
        val issues = SqlSyntaxDiagnostics.analyze(code)
        return FormalSyntaxResult(
            state = if (issues.any { it.severity == CodeIssueSeverity.ERROR }) {
                SyntaxValidationState.INVALID
            } else {
                SyntaxValidationState.VALID
            },
            parserName = "JSqlParser 5.3",
            message = if (issues.isEmpty()) {
                "Sintaxe SQL aceita pelo parser formal."
            } else {
                "O parser SQL encontrou ${issues.size} erro(s) de sintaxe."
            },
            issues = issues,
        )
    }

    private suspend fun analyzeTreeSitter(
        code: String,
        language: CodeLanguageProfile,
    ): FormalSyntaxResult {
        val resolved = resolveLocalTreeSitterLanguage(language)
        if (resolved == null) {
            val knownGrammar = resolveKnownTreeSitterLanguage(language)
            return FormalSyntaxResult(
                state = SyntaxValidationState.PARSER_UNAVAILABLE,
                parserName = knownGrammar?.let { "Tree-sitter ($it)" } ?: "Tree-sitter",
                message = if (knownGrammar != null) {
                    "A gramática Tree-sitter de ${language.displayName} é reconhecida como $knownGrammar, " +
                        "mas o parser não está disponível localmente neste dispositivo. " +
                        "A validação sintática offline não será simulada nem substituída pela IA."
                } else {
                    "Não há gramática Tree-sitter reconhecida para ${language.displayName} neste build."
                },
            )
        }

        return runCatching {
            TreeSitterLanguagePack.processAsync(
                source = code,
                config = ProcessConfig(
                    language = resolved,
                    structure = false,
                    imports = false,
                    exports = false,
                    comments = false,
                    docstrings = false,
                    symbols = false,
                    diagnostics = true,
                    dataExtraction = false,
                    maxSourceBytes = MAX_SOURCE_BYTES,
                    parseTimeoutMs = PARSE_TIMEOUT_MS,
                ),
            )
        }.fold(
            onSuccess = { result ->
                val issues = result.diagnostics.mapIndexed { index, diagnostic ->
                    diagnostic.toCodeIssue(code, index, resolved)
                }
                val hasSyntaxError = issues.any { it.severity == CodeIssueSeverity.ERROR }
                FormalSyntaxResult(
                    state = if (hasSyntaxError) SyntaxValidationState.INVALID else SyntaxValidationState.VALID,
                    parserName = "Tree-sitter ($resolved)",
                    message = if (hasSyntaxError) {
                        "O parser formal encontrou ${issues.count { it.severity == CodeIssueSeverity.ERROR }} erro(s) de sintaxe."
                    } else {
                        "Sintaxe aceita pela gramática formal Tree-sitter de $resolved."
                    },
                    issues = issues,
                )
            },
            onFailure = { error ->
                FormalSyntaxResult(
                    state = SyntaxValidationState.PARSER_UNAVAILABLE,
                    parserName = "Tree-sitter ($resolved)",
                    message = "O parser formal não pôde validar ${language.displayName}: ${error.message ?: error::class.java.simpleName}",
                )
            },
        )
    }

    private fun resolveLocalTreeSitterLanguage(language: CodeLanguageProfile): String? =
        treeSitterCandidates(language).firstOrNull { candidate ->
            runCatching { treeSitterRegistry.hasParser(candidate) }.getOrDefault(false)
        }

    private fun resolveKnownTreeSitterLanguage(language: CodeLanguageProfile): String? =
        treeSitterCandidates(language).firstOrNull { candidate ->
            runCatching { TreeSitterLanguagePack.hasLanguage(candidate) }.getOrDefault(false)
        }

    private fun treeSitterCandidates(language: CodeLanguageProfile): List<String> = buildList {
        addAll(language.treeSitterCandidates)
        language.extensions.forEach { extension ->
            runCatching { TreeSitterLanguagePack.detectLanguageFromExtension(extension) }
                .getOrNull()
                ?.let(::add)
        }
    }.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

    private fun io.xberg.tslp.android.Diagnostic.toCodeIssue(
        code: String,
        index: Int,
        parserLanguage: String,
    ): CodeIssue {
        val startLine = span.startLine.toInt().coerceAtLeast(0) + 1
        val endLine = span.endLine.toInt().coerceAtLeast(span.startLine.toInt()) + 1
        val startColumn = span.startColumn.toInt().coerceAtLeast(0) + 1
        val original = sourceFragment(code, span.startByte, span.endByte)
            .ifBlank { code.lineSequence().drop(startLine - 1).firstOrNull().orEmpty() }

        return CodeIssue(
            id = "tree-sitter-$parserLanguage-$startLine-$startColumn-$index",
            title = "Erro de sintaxe",
            explanation = message.ifBlank { "A gramática formal encontrou uma construção sintaticamente inválida." },
            startLine = startLine,
            endLine = endLine,
            original = original,
            replacement = original,
            severity = when (severity) {
                DiagnosticSeverity.ERROR -> CodeIssueSeverity.ERROR
                DiagnosticSeverity.WARNING -> CodeIssueSeverity.WARNING
                DiagnosticSeverity.INFO -> CodeIssueSeverity.INFO
            },
            category = CodeIssueCategory.SYNTAX,
            column = startColumn,
            canAutoFix = false,
            source = CodeIssueSource.LOCAL,
        )
    }

    private fun sourceFragment(code: String, startByte: Long, endByte: Long): String {
        val bytes = code.toByteArray(Charsets.UTF_8)
        val start = startByte.coerceIn(0L, bytes.size.toLong()).toInt()
        val end = endByte.coerceIn(start.toLong(), bytes.size.toLong()).toInt()
        if (end <= start) return ""
        return bytes.copyOfRange(start, end).toString(Charsets.UTF_8)
    }

    private val treeSitterRegistry by lazy { LanguageRegistry.new() }

    private const val MAX_SOURCE_BYTES = 2_000_000L
    private const val PARSE_TIMEOUT_MS = 2_000L
}
