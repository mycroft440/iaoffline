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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PendingCodeLineEdit(
    val range: CodeLineRange,
    val original: String,
    val replacement: String,
    val summary: String,
)

data class CodeEditorUiState(
    val code: String = DEFAULT_SAMPLE,
    val language: String = "Kotlin",
    val instruction: String = "",
    val editing: Boolean = false,
    val pendingEdit: PendingCodeLineEdit? = null,
    val languagePackInstalled: Boolean = false,
    val lastAppliedSummary: String? = null,
    val error: String? = null,
) {
    companion object {
        private const val DEFAULT_SAMPLE = """fun greeting(name: String): String {
    return "Olá, " + name
}
"""
    }
}

class CodeEditorViewModel(
    private val aiGateway: AiGateway,
    private val languagePacks: CodeLanguagePackRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CodeEditorUiState(
            languagePackInstalled = languagePacks.isInstalled("Kotlin"),
        ),
    )
    val state: StateFlow<CodeEditorUiState> = _state.asStateFlow()

    private var pendingSourceSnapshot: String? = null

    init {
        viewModelScope.launch {
            languagePacks.installedIds.collect {
                _state.update { state ->
                    state.copy(
                        languagePackInstalled = languagePacks.isInstalled(state.language),
                    )
                }
            }
        }
    }

    fun updateCode(code: String) {
        if (_state.value.code == code) return
        pendingSourceSnapshot = null
        _state.update {
            it.copy(
                code = code,
                pendingEdit = null,
                lastAppliedSummary = null,
                error = null,
            )
        }
    }

    fun updateLanguage(language: String) {
        pendingSourceSnapshot = null
        _state.update {
            it.copy(
                language = language,
                pendingEdit = null,
                languagePackInstalled = languagePacks.isInstalled(language),
                lastAppliedSummary = null,
                error = null,
            )
        }
    }

    fun updateInstruction(instruction: String) {
        _state.update { it.copy(instruction = instruction, error = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun discardPendingEdit() {
        pendingSourceSnapshot = null
        _state.update { it.copy(pendingEdit = null, error = null) }
    }

    fun requestEdit(range: CodeLineRange) {
        val snapshot = _state.value
        if (snapshot.editing) return
        val instruction = snapshot.instruction.trim()
        if (instruction.isBlank()) {
            _state.update { it.copy(error = "Descreva o que deve ser alterado na linha selecionada.") }
            return
        }

        val original = CodeLineEditEngine.extractLines(snapshot.code, range)
        if (original == null) {
            _state.update { it.copy(error = "A linha selecionada não existe mais.") }
            return
        }

        _state.update {
            it.copy(
                editing = true,
                pendingEdit = null,
                lastAppliedSummary = null,
                error = null,
            )
        }

        val sourceSnapshot = snapshot.code
        viewModelScope.launch {
            val profile = CodeLanguageRegistry.find(snapshot.language)
            val installedPack = languagePacks.guidanceFor(snapshot.language)
            val prompt = buildEditPrompt(
                profile = profile,
                installedPack = installedPack,
                code = sourceSnapshot,
                range = range,
                original = original,
                instruction = instruction,
            )

            runCatching {
                aiGateway.chat(
                    AiChatRequest(
                        conversationId = "code-canvas-" + UUID.randomUUID(),
                        messages = listOf(
                            AiChatMessage("system", SYSTEM_PROMPT),
                            AiChatMessage("user", prompt),
                        ),
                    ),
                )
            }.onSuccess { response ->
                runCatching {
                    parseEditResponse(response, range, original)
                }.onSuccess { edit ->
                    pendingSourceSnapshot = sourceSnapshot
                    _state.update {
                        it.copy(
                            editing = false,
                            pendingEdit = edit,
                            languagePackInstalled = installedPack != null,
                        )
                    }
                }.onFailure { error ->
                    pendingSourceSnapshot = null
                    _state.update {
                        it.copy(
                            editing = false,
                            error = "A IA respondeu, mas a edição foi rejeitada por segurança: " +
                                (error.message ?: "formato inválido"),
                        )
                    }
                }
            }.onFailure { error ->
                pendingSourceSnapshot = null
                _state.update {
                    it.copy(
                        editing = false,
                        error = "Não foi possível preparar a edição: " +
                            (error.message ?: "erro desconhecido"),
                    )
                }
            }
        }
    }

    fun applyPendingEdit() {
        val snapshot = _state.value
        val pending = snapshot.pendingEdit ?: return
        val source = pendingSourceSnapshot
        if (source == null || snapshot.code != source) {
            pendingSourceSnapshot = null
            _state.update {
                it.copy(
                    pendingEdit = null,
                    error = "O código mudou depois do pedido. Selecione a linha e peça a edição novamente.",
                )
            }
            return
        }

        val currentOriginal = CodeLineEditEngine.extractLines(snapshot.code, pending.range)
        if (currentOriginal != pending.original) {
            pendingSourceSnapshot = null
            _state.update {
                it.copy(
                    pendingEdit = null,
                    error = "A linha alvo mudou. A edição não foi aplicada.",
                )
            }
            return
        }

        val updated = CodeLineEditEngine.applyExactLines(
            code = snapshot.code,
            range = pending.range,
            replacement = pending.replacement,
        )
        if (updated == null) {
            _state.update {
                it.copy(error = "A edição tentou alterar uma quantidade diferente de linhas e foi bloqueada.")
            }
            return
        }

        pendingSourceSnapshot = null
        _state.update {
            it.copy(
                code = updated,
                instruction = "",
                pendingEdit = null,
                lastAppliedSummary = pending.summary,
                error = null,
            )
        }
    }

    private fun buildEditPrompt(
        profile: CodeLanguageProfile,
        installedPack: InstalledCodeLanguagePack?,
        code: String,
        range: CodeLineRange,
        original: String,
        instruction: String,
    ): String = buildString {
        appendLine("Linguagem declarada: " + profile.displayName)
        appendLine("Faixa permitida: " + range.startLine + "-" + range.endLine)
        appendLine("Quantidade obrigatória de linhas na resposta: " + range.lineCount)
        appendLine("Pedido do usuário: " + instruction)
        appendLine()
        appendLine("Trecho exato que pode ser substituído:")
        appendLine("<<<TARGET")
        appendLine(original)
        appendLine("TARGET")
        appendLine()
        appendLine("Contexto especializado:")
        appendLine(installedPack?.prompt ?: profile.analysisHint)
        appendLine()
        appendLine("Contexto do arquivo com números de linha:")
        append(CodeLineEditEngine.numberedContext(code, range))
    }

    private fun parseEditResponse(
        raw: String,
        requestedRange: CodeLineRange,
        original: String,
    ): PendingCodeLineEdit {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end >= start) { "JSON não encontrado" }

        val json = JSONObject(raw.substring(start, end + 1))
        val responseRange = CodeLineRange(
            startLine = json.optInt("startLine", -1),
            endLine = json.optInt("endLine", -1),
        )
        require(responseRange == requestedRange) {
            "a IA tentou editar fora da linha selecionada"
        }

        val replacement = json.getString("replacement")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        require(CodeLineEditEngine.replacementLineCount(replacement) == requestedRange.lineCount) {
            "a substituição mudaria a quantidade de linhas"
        }
        require(replacement != original) {
            "nenhuma alteração foi proposta"
        }

        return PendingCodeLineEdit(
            range = requestedRange,
            original = original,
            replacement = replacement,
            summary = json.optString("summary").trim().ifBlank { "Edição preparada." },
        )
    }

    class Factory(
        private val aiGateway: AiGateway,
        private val languagePacks: CodeLanguagePackRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CodeEditorViewModel(aiGateway, languagePacks) as T
    }

    private companion object {
        val SYSTEM_PROMPT = """
            Você é um editor de código cirúrgico. Você NÃO é um compilador, validador, linter ou revisor.
            Sua única tarefa é substituir exatamente a linha ou intervalo de linhas escolhido pelo usuário.

            Regras obrigatórias:
            1. Nunca altere startLine ou endLine informados pelo usuário.
            2. replacement deve conter exatamente a mesma quantidade de linhas da faixa escolhida.
            3. Não inclua linhas vizinhas em replacement.
            4. Não faça correções extras, melhorias de estilo ou refatorações não pedidas.
            5. Use o restante do arquivo apenas como contexto para entender o pedido.
            6. Preserve indentação e a linguagem do arquivo.
            7. Se o pedido não puder ser atendido sem alterar outras linhas, faça a melhor alteração possível somente na faixa escolhida e explique isso brevemente em summary.
            8. Retorne SOMENTE um objeto JSON, sem markdown nem texto externo.

            Formato obrigatório:
            {
              "startLine": 1,
              "endLine": 1,
              "replacement": "conteúdo substituto",
              "summary": "descrição curta da mudança"
            }
        """.trimIndent()
    }
}
