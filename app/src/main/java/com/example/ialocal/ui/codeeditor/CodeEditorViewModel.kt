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
        _state.update { it.copy(language = language) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.code.isBlank() || snapshot.analyzing) return

        _state.update { it.copy(analyzing = true, error = null, lastAppliedCount = 0) }
        viewModelScope.launch {
            runCatching {
                aiGateway.chat(
                    AiChatRequest(
                        conversationId = "code-editor-${UUID.randomUUID()}",
                        messages = listOf(
                            AiChatMessage("system", SYSTEM_PROMPT),
                            AiChatMessage(
                                "user",
                                "Linguagem declarada: ${snapshot.language.ifBlank { "desconhecida" }}\n\n" +
                                    "Analise o código abaixo e retorne somente o JSON solicitado.\n\n" +
                                    snapshot.code,
                            ),
                        ),
                    ),
                )
            }.onSuccess { response ->
                runCatching { parseIssues(response) }
                    .onSuccess { issues ->
                        _state.update { it.copy(issues = issues, analyzing = false) }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                analyzing = false,
                                error = "A IA respondeu, mas o formato das correções não pôde ser lido: ${error.message}",
                            )
                        }
                    }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        analyzing = false,
                        error = error.message ?: "Falha ao analisar o código com a IA local.",
                    )
                }
            }
        }
    }

    fun applyIssue(issue: CodeIssue) {
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
        _state.update {
            it.copy(
                code = updated,
                issues = if (applied > 0) emptyList() else it.issues,
                error = if (applied == 0 && it.issues.isNotEmpty()) {
                    "Nenhuma correção pôde ser aplicada porque os trechos já mudaram. Analise novamente."
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
                if (original.isEmpty()) continue

                val severity = runCatching {
                    CodeIssueSeverity.valueOf(item.optString("severity", "WARNING").uppercase())
                }.getOrDefault(CodeIssueSeverity.WARNING)

                add(
                    CodeIssue(
                        id = item.optString("id").ifBlank { "issue-$index" },
                        title = item.optString("title").ifBlank { "Problema encontrado" },
                        explanation = item.optString("explanation"),
                        startLine = item.optInt("startLine", 1).coerceAtLeast(1),
                        endLine = item.optInt("endLine", item.optInt("startLine", 1)).coerceAtLeast(1),
                        original = original,
                        replacement = replacement,
                        severity = severity,
                    ),
                )
            }
        }
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
            Você é um analisador cirúrgico de código. Sua tarefa é detectar somente problemas concretos e sugerir alterações mínimas.

            Regras obrigatórias:
            1. Não reescreva o arquivo inteiro.
            2. Cada correção deve substituir apenas o menor trecho necessário.
            3. O campo original deve ser uma cópia EXATA e literal do trecho existente no código do usuário.
            4. startLine e endLine usam numeração iniciando em 1.
            5. severity deve ser ERROR, WARNING ou INFO.
            6. Se não houver problema concreto, retorne [].
            7. Retorne SOMENTE um array JSON válido, sem markdown, sem comentários e sem texto antes/depois.

            Formato obrigatório:
            [
              {
                "id": "issue-1",
                "title": "Título curto",
                "severity": "ERROR",
                "explanation": "Explique objetivamente o erro e o efeito.",
                "startLine": 1,
                "endLine": 1,
                "original": "trecho exato existente",
                "replacement": "trecho corrigido"
              }
            ]
        """.trimIndent()
    }
}
