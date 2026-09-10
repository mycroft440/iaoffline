package com.example.ialocal.ui.modelconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelConfigViewModel(
    private val modelId: String,
    private val models: ModelRepository,
    private val manager: ModelManager,
    private val chats: ChatRepository,
) : ViewModel() {
    val model: StateFlow<AiModelEntity?> = models.models
        .map { list -> list.firstOrNull { it.id == modelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val agent: StateFlow<AgentEntity?> = models.agents
        .map { list -> list.firstOrNull { it.modelId == modelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _systemPrompt = MutableStateFlow(ModelRepository.DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _temperature = MutableStateFlow(DEFAULT_TEMPERATURE)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(MAX_INTELLIGENCE_TOKENS)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var initializedAgentId: String? = null

    init {
        viewModelScope.launch {
            agent.collect { current ->
                if (current != null && initializedAgentId == null) {
                    initializedAgentId = current.id
                    _systemPrompt.value = current.systemPrompt
                    _temperature.value = current.temperature
                    _maxTokens.value = current.maxTokens.coerceIn(MIN_OUTPUT_TOKENS, MAX_INTELLIGENCE_TOKENS)
                }
            }
        }
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value.take(MAX_SYSTEM_PROMPT_CHARS)
    }

    fun setTemperature(value: Float) {
        _temperature.value = value.coerceIn(0f, 2f)
    }

    fun setIntelligence(level: IntelligenceLevel) {
        _maxTokens.value = level.maxTokens
    }

    fun clearMessage() {
        _message.value = null
    }

    fun saveSettings() {
        val current = agent.value ?: return
        viewModelScope.launch {
            runCatching { persist(current) }
                .onSuccess { _message.value = "Configurações salvas." }
                .onFailure { _message.value = it.message ?: "Não foi possível salvar as configurações." }
        }
    }

    fun recalibrateContext() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = "Ajustando o contexto automaticamente para este aparelho…"
            try {
                val calibrated = manager.recalibrateContext(modelId)
                _message.value = "Contexto automático ajustado para ${calibrated.contextLength} tokens."
            } catch (t: Throwable) {
                _message.value = t.message ?: "Não foi possível recalibrar o contexto."
            } finally {
                _busy.value = false
            }
        }
    }

    fun startChat(onStarted: (String) -> Unit) {
        if (_busy.value) return
        val currentAgent = agent.value ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                persist(currentAgent)
                manager.load(modelId)
                models.setDefaultAgent(currentAgent.id)
                val conversationId = chats.createConversation(agentId = currentAgent.id)
                onStarted(conversationId)
            } catch (t: Throwable) {
                _message.value = t.message ?: "Não foi possível iniciar a conversa com este modelo."
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun persist(current: AgentEntity) {
        val prompt = _systemPrompt.value.trim().ifBlank { ModelRepository.DEFAULT_SYSTEM_PROMPT }
        models.updateAgent(
            current.copy(
                systemPrompt = prompt,
                temperature = _temperature.value,
                maxTokens = _maxTokens.value,
            )
        )
    }

    class Factory(
        private val modelId: String,
        private val models: ModelRepository,
        private val manager: ModelManager,
        private val chats: ChatRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModelConfigViewModel(modelId, models, manager, chats) as T
    }

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.3f
        private const val MIN_OUTPUT_TOKENS = 256
        private const val MAX_INTELLIGENCE_TOKENS = 4096
        private const val MAX_SYSTEM_PROMPT_CHARS = 12_000
    }
}

enum class IntelligenceLevel(val label: String, val maxTokens: Int) {
    ECONOMY("Econômico", 1024),
    BALANCED("Equilibrado", 2048),
    MAX("Máx", 4096),
    ;

    companion object {
        fun fromMaxTokens(value: Int): IntelligenceLevel = when {
            value >= MAX.maxTokens -> MAX
            value >= BALANCED.maxTokens -> BALANCED
            else -> ECONOMY
        }
    }
}
