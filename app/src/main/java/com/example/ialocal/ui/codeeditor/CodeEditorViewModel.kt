package com.example.ialocal.ui.codeeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.models.ModelRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CodeEditorUiState(
    val code: String = DEFAULT_SAMPLE,
    val language: String = "Kotlin",
    val instruction: String = "",
    val editing: Boolean = false,
    val languagePackInstalled: Boolean = false,
    val agentName: String? = null,
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
    private val modelRepository: ModelRepository,
    private val codeEditSessions: CodeEditSessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CodeEditorUiState(
            languagePackInstalled = languagePacks.isInstalled("Kotlin"),
        ),
    )
    val state: StateFlow<CodeEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            languagePacks.installedIds.collect {
                _state.update { state ->
                    state.copy(languagePackInstalled = languagePacks.isInstalled(state.language))
                }
            }
        }
    }

    fun updateCode(code: String) {
        if (_state.value.code == code) return
        _state.update { it.copy(code = code, lastAppliedSummary = null, error = null) }
    }

    fun updateLanguage(language: String) {
        _state.update {
            it.copy(
                language = language,
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

    fun requestEdit(target: CodeEditTarget) {
        val snapshot = _state.value
        if (snapshot.editing) return

        val instruction = snapshot.instruction.trim()
        if (instruction.isBlank()) {
            _state.update { it.copy(error = "Descreva o que o agente deve alterar no trecho selecionado.") }
            return
        }
        if (target.endOffset > snapshot.code.length ||
            snapshot.code.substring(target.startOffset, target.endOffset) != target.original
        ) {
            _state.update { it.copy(error = "A seleção mudou. Selecione novamente o trecho antes de editar.") }
            return
        }

        _state.update { it.copy(editing = true, lastAppliedSummary = null, error = null) }

        viewModelScope.launch {
            var sessionId: String? = null
            try {
                val agent = modelRepository.getDefaultAgent()
                    ?: modelRepository.getAgents().firstOrNull()
                    ?: error("Nenhum agente está configurado. Importe e verifique uma IA primeiro.")
                val profile = CodeLanguageRegistry.find(snapshot.language)
                val installedPack = languagePacks.guidanceFor(snapshot.language)
                val session = codeEditSessions.create(snapshot.code, target)
                sessionId = session.id

                val finalAgentResponse = aiGateway.chat(
                    AiChatRequest(
                        conversationId = "code-canvas-" + UUID.randomUUID(),
                        agentId = agent.id,
                        messages = listOf(
                            AiChatMessage(
                                "user",
                                buildAgentEditPrompt(
                                    session.id,
                                    profile,
                                    installedPack,
                                    snapshot.code,
                                    target,
                                    instruction,
                                ),
                            ),
                        ),
                    ),
                )

                val result = codeEditSessions.get(session.id)
                    ?: error("A sessão de edição expirou antes da resposta do agente.")
                val updatedCode = result.resultCode
                    ?: error("O agente não executou a ferramenta replace_code_range.")

                _state.update {
                    it.copy(
                        code = updatedCode,
                        instruction = "",
                        editing = false,
                        languagePackInstalled = installedPack != null,
                        agentName = agent.name,
                        lastAppliedSummary = result.summary
                            ?: finalAgentResponse.trim().take(240).ifBlank {
                                "Trecho editado diretamente pelo agente."
                            },
                        error = null,
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        editing = false,
                        error = "O agente não conseguiu editar o trecho: " +
                            (error.message ?: "erro desconhecido"),
                    )
                }
            } finally {
                sessionId?.let(codeEditSessions::remove)
            }
        }
    }

    private fun buildAgentEditPrompt(
        sessionId: String,
        profile: CodeLanguageProfile,
        installedPack: InstalledCodeLanguagePack?,
        code: String,
        target: CodeEditTarget,
        instruction: String,
    ): String = buildString {
        appendLine("Você está operando o Canvas de código com autorização de escrita estritamente limitada.")
        appendLine("Você DEVE executar a ferramenta replace_code_range exatamente uma vez para realizar a alteração.")
        appendLine("Não devolva um patch em texto e não reescreva o arquivo inteiro.")
        appendLine("A ferramenta já está travada no trecho autorizado; você só escolhe o replacement.")
        appendLine()
        appendLine("session_id: $sessionId")
        appendLine("Linguagem: ${profile.displayName}")
        appendLine("Alvo autorizado: ${target.label()}")
        appendLine("Pedido do usuário: $instruction")
        appendLine()
        appendLine("Trecho exato atualmente autorizado:")
        appendLine("<<<TARGET")
        appendLine(target.original)
        appendLine("TARGET")
        appendLine()
        appendLine("Contexto especializado:")
        appendLine(installedPack?.prompt ?: profile.analysisHint)
        appendLine()
        appendLine("Contexto somente para leitura, com números de linha:")
        append(CodeLineEditEngine.numberedContext(code, target))
        appendLine()
        appendLine("Chame replace_code_range com este session_id, o replacement exato e um summary curto.")
        appendLine("Você pode inserir ou remover linhas DENTRO do alvo; tudo fora dele deve permanecer intocado.")
    }

    class Factory(
        private val aiGateway: AiGateway,
        private val languagePacks: CodeLanguagePackRepository,
        private val modelRepository: ModelRepository,
        private val codeEditSessions: CodeEditSessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CodeEditorViewModel(aiGateway, languagePacks, modelRepository, codeEditSessions) as T
    }
}
