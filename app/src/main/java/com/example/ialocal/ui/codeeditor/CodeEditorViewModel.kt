package com.example.ialocal.ui.codeeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

data class CodeEditorUiState(
    val code: String = DEFAULT_SAMPLE,
    val language: String = "Kotlin",
    val issues: List<CodeIssue> = emptyList(),
    val analyzing: Boolean = false,
    val syntaxState: SyntaxValidationState = SyntaxValidationState.IDLE,
    val syntaxParserName: String? = null,
    val syntaxMessage: String? = null,
    val error: String? = null,
    val lastAppliedCount: Int = 0,
) {
    companion object {
        private const val DEFAULT_SAMPLE = """fun main() {
    val numbers = listOf(1, 2, 3)
    println(numbers[3])
}
"""
    }
}

class CodeEditorViewModel(
    private val aiGateway: AiGateway,
) : ViewModel() {
    private val _state = MutableStateFlow(CodeEditorUiState())
    val state: StateFlow<CodeEditorUiState> = _state.asStateFlow()

    fun updateCode(code: String) {
        _state.update {
            it.copy(
                code = code,
                issues = emptyList(),
                syntaxState = SyntaxValidationState.IDLE,
                syntaxParserName = null,
                syntaxMessage = null,
                lastAppliedCount = 0,
            )
        }
    }

    fun updateLanguage(language: String) {
        _state.update {
            it.copy(
                language = language,
                issues = emptyList(),
                syntaxState = SyntaxValidationState.IDLE,
                syntaxParserName = null,
                syntaxMessage = null,
                lastAppliedCount = 0,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.analyzing) return

        val profile = CodeLanguageRegistry.find(snapshot.language)
        _state.update {
            it.copy(
                analyzing = true,
                issues = emptyList(),
                syntaxState = SyntaxValidationState.CHECKING,
                syntaxParserName = null,
                syntaxMessage = "Validando a sintaxe com parser formal…",
                error = null,
                lastAppliedCount = 0,
            )
        }

        viewModelScope.launch {
            val syntax = FormalSyntaxDiagnostics.analyze(snapshot.code, profile)
            _state.update {
                it.copy(
                    issues = syntax.issues,
                    syntaxState = syntax.state,
                    syntaxParserName = syntax.parserName,
                    syntaxMessage = syntax.message,
                )
            }

            // Syntax is authoritative only when a formal parser completed successfully.
            // Do not ask the model to guess around invalid or unavailable grammar results.
            if (syntax.state != SyntaxValidationState.VALID) {
                _state.update { it.copy(analyzing = false) }
                return@launch
            }

            runCatching {
                aiGateway.chat(
                    AiChatRequest(
                        conversationId = "code-editor-${UUID.randomUUID()}",
                        messages = listOf(
                            AiChatMessage("system", SYSTEM_PROMPT),
                            AiChatMessage(
                                "user",
                                buildString {
                                    appendLine("Linguagem: ${profile.displayName}")
                                    appendLine("Perfil de análise: ${profile.analysisHint}")
                                    appendLine("A sintaxe já foi aceita pelo parser formal: ${syntax.parserName ?: "parser local"}.")
                                    appendLine()
                                    appendLine("Não faça diagnóstico de sintaxe. Retorne apenas erros concretos de tipo, referência, lógica, segurança ou compatibilidade.")
                                    appendLine("Não reporte preferências de estilo, formatação ou refatorações opcionais.")
                                    appendLine()
                                    append(snapshot.code)
                                },
                            ),
                        ),
                    ),
                )
            }.onSuccess { response ->
                runCatching { parseIssues(response) }
                    .onSuccess { aiIssues ->
                        _state.update {
                            it.copy(
                                issues = mergeDiagnostics(syntax.issues, aiIssues),
                                analyzing = false,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                issues = syntax.issues,
                                analyzing = false,
                                error = "A sintaxe foi validada, mas a análise semântica complementar da IA não pôde ser lida: ${error.message}",
                            )
                        }
                    }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        issues = syntax.issues,
                        analyzing = false,
                        error = "A sintaxe foi validada pelo parser formal. A análise semântica complementar da IA falhou: ${error.message ?: "erro desconhecido"}",
                    )
                }
            }
        }
    }

    fun applyIssue(issue: CodeIssue) {
        if (!issue.canAutoFix) {
            _state.update { it.copy(error = "Este diagnóstico exige revisão manual; não há patch automático seguro.") }
            return
        }

        val snapshot = _state.value
        val updated = CodePatchEngine.applyIssue(snapshot.code, issue)
        if (updated == null) {
            _state.update {
                it.copy(error = "O trecho mudou desde a análise. Analise novamente antes de aplicar esta correção.")
            }
            return
        }

        _state.update {
            it.copy(
                code = updated,
                issues = emptyList(),
                syntaxState = SyntaxValidationState.IDLE,
                syntaxParserName = null,
                syntaxMessage = "Código alterado; valide a sintaxe novamente.",
                error = null,
                lastAppliedCount = 1,
            )
        }
    }

    fun applyAll() {
        val snapshot = _state.value
        val (updated, applied) = CodePatchEngine.applyAll(snapshot.code, snapshot.issues)
        _state.update {
            it.copy(
                code = updated,
                issues = if (applied > 0) emptyList() else it.issues,
                syntaxState = if (applied > 0) SyntaxValidationState.IDLE else it.syntaxState,
                syntaxParserName = if (applied > 0) null else it.syntaxParserName,
                syntaxMessage = if (applied > 0) "Código alterado; valide a sintaxe novamente." else it.syntaxMessage,
                error = if (applied == 0 && it.issues.any { issue -> issue.canAutoFix }) {
                    "Nenhuma correção automática pôde ser aplicada porque os trechos já mudaram. Analise novamente."
                } else {
                    null
                },
                lastAppliedCount = applied,
            )
        }
    }

    private fun parseIssues(raw: String): List<CodeIssue> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        require(start >= 0 && end >= start) { "JSON não encontrado" }

        val array = JSONArray(raw.substring(start, end + 1))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val original = item.optString("original")
                val replacement = item.optString("replacement")
                val startLine = item.optInt("startLine", 1).coerceAtLeast(1)
                val severity = runCatching {
                    CodeIssueSeverity.valueOf(item.optString("severity", "ERROR").uppercase())
                }.getOrDefault(CodeIssueSeverity.ERROR)
                val category = runCatching {
                    CodeIssueCategory.valueOf(item.optString("category", "LOGIC").uppercase())
                }.getOrDefault(CodeIssueCategory.LOGIC)

                // The model is never authoritative for syntax. Ignore any syntax diagnosis it emits.
                if (category == CodeIssueCategory.SYNTAX) continue

                add(
                    CodeIssue(
                        id = item.optString("id").ifBlank { "ai-issue-$index" },
                        title = item.optString("title").ifBlank { "Erro encontrado" },
                        explanation = item.optString("explanation"),
                        startLine = startLine,
                        endLine = item.optInt("endLine", startLine).coerceAtLeast(startLine),
                        original = original,
                        replacement = replacement,
                        severity = severity,
                        category = category,
                        column = item.optInt("column", 0).takeIf { it > 0 },
                        canAutoFix = item.optBoolean("canAutoFix", original.isNotEmpty()),
                        source = CodeIssueSource.AI,
                    ),
                )
            }
        }
    }

    private fun mergeDiagnostics(local: List<CodeIssue>, ai: List<CodeIssue>): List<CodeIssue> {
        val result = local.toMutableList()
        ai.forEach { candidate ->
            val duplicate = result.any { existing ->
                existing.startLine == candidate.startLine &&
                    existing.category == candidate.category &&
                    (existing.original == candidate.original || existing.title.equals(candidate.title, ignoreCase = true))
            }
            if (!duplicate) result += candidate
        }
        return result.sortedWith(
            compareBy<CodeIssue> { it.startLine }
                .thenBy { it.column ?: Int.MAX_VALUE }
                .thenBy { it.severity.ordinal },
        )
    }

    class Factory(
        private val aiGateway: AiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CodeEditorViewModel(aiGateway) as T
        }
    }

    private companion object {
        val SYSTEM_PROMPT = """
            Você é um analisador semântico de código que complementa um parser formal local.
            A SINTAXE JÁ FOI VALIDADA FORA DA IA. Você não tem autoridade para diagnosticar sintaxe.

            Regras obrigatórias:
            1. Nunca retorne category=SYNTAX.
            2. Não reporte preferências de estilo, formatação ou refatorações opcionais.
            3. Detecte somente erros concretos de TYPE, REFERENCE, LOGIC, SECURITY ou COMPATIBILITY.
            4. Não invente bibliotecas, APIs ou erros. Se não tiver evidência suficiente, omita o diagnóstico.
            5. Cada patch deve alterar somente o menor trecho necessário.
            6. Se houver patch seguro, original deve copiar EXATAMENTE o trecho existente e canAutoFix deve ser true.
            7. Se o erro for real mas não houver patch local seguro, canAutoFix=false; original/replacement podem ser vazios.
            8. startLine/endLine começam em 1; column também começa em 1 quando conhecida.
            9. severity deve ser ERROR, WARNING ou INFO.
            10. category deve ser TYPE, REFERENCE, LOGIC, SECURITY ou COMPATIBILITY.
            11. Se não houver erro concreto, retorne [].
            12. Retorne SOMENTE o array JSON, sem markdown e sem texto adicional.

            Formato:
            [
              {
                "id": "issue-1",
                "title": "Descrição curta do erro",
                "severity": "ERROR",
                "category": "TYPE",
                "explanation": "Causa e efeito do erro.",
                "startLine": 1,
                "endLine": 1,
                "column": 1,
                "original": "trecho exato",
                "replacement": "correção mínima",
                "canAutoFix": true
              }
            ]
        """.trimIndent()
    }
}
