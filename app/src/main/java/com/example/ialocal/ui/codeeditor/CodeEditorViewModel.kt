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
        _state.update { it.copy(code = code, lastAppliedCount = 0) }
    }

    fun updateLanguage(language: String) {
        _state.update { it.copy(language = language, issues = emptyList(), lastAppliedCount = 0) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.code.isBlank() || snapshot.analyzing) return

        val profile = CodeLanguageRegistry.find(snapshot.language)
        val localIssues = LocalCodeDiagnostics.analyze(snapshot.code, profile)
        _state.update {
            it.copy(
                analyzing = true,
                issues = localIssues,
                error = null,
                lastAppliedCount = 0,
            )
        }

        viewModelScope.launch {
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
                                    appendLine()
                                    appendLine("Retorne apenas erros concretos do código. Não reporte preferências de estilo, formatação ou refatorações opcionais.")
                                    appendLine("Priorize erros de sintaxe, tipos, símbolos/referências, lógica demonstrável e compatibilidade.")
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
                                issues = mergeDiagnostics(localIssues, aiIssues),
                                analyzing = false,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                issues = localIssues,
                                analyzing = false,
                                error = "A análise local foi concluída, mas a resposta complementar da IA não pôde ser lida: ${error.message}",
                            )
                        }
                    }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        issues = localIssues,
                        analyzing = false,
                        error = if (localIssues.isNotEmpty()) {
                            "Diagnósticos locais exibidos. A análise complementar da IA falhou: ${error.message ?: "erro desconhecido"}"
                        } else {
                            error.message ?: "Falha ao analisar o código com a IA local."
                        },
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
                issues = it.issues.filterNot { candidate -> candidate.id == issue.id },
                error = null,
                lastAppliedCount = 1,
            )
        }
    }

    fun applyAll() {
        val snapshot = _state.value
        val (updated, applied) = CodePatchEngine.applyAll(snapshot.code, snapshot.issues)
        val manualIssues = snapshot.issues.filterNot { it.canAutoFix }
        _state.update {
            it.copy(
                code = updated,
                issues = if (applied > 0) manualIssues else it.issues,
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
                    CodeIssueCategory.valueOf(item.optString("category", "SYNTAX").uppercase())
                }.getOrDefault(CodeIssueCategory.SYNTAX)

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
            Você é um analisador de código com comportamento de compilador/linter profissional.
            Sua função é retornar ERROS CONCRETOS. Não faça revisão estética.

            Regras obrigatórias:
            1. Não reporte preferências de estilo, formatação ou refatorações opcionais.
            2. Detecte erros de sintaxe, tipos, referências/símbolos, lógica demonstrável, segurança concreta e compatibilidade.
            3. Não invente bibliotecas, APIs ou erros. Se não tiver evidência suficiente, omita o diagnóstico.
            4. Cada patch deve alterar somente o menor trecho necessário.
            5. Se houver patch seguro, original deve copiar EXATAMENTE o trecho existente e canAutoFix deve ser true.
            6. Se o erro for real mas não houver patch local seguro, canAutoFix=false; original/replacement podem ser vazios.
            7. startLine/endLine começam em 1; column também começa em 1 quando conhecida.
            8. severity deve ser ERROR, WARNING ou INFO.
            9. category deve ser SYNTAX, TYPE, REFERENCE, LOGIC, SECURITY ou COMPATIBILITY.
            10. Se não houver erro concreto, retorne [].
            11. Retorne SOMENTE o array JSON, sem markdown e sem texto adicional.

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
