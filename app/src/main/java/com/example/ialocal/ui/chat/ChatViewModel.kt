package com.example.ialocal.ui.chat

import android.net.Uri
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.chat.ChatGenerationManager
import com.example.ialocal.chat.GenerationTarget
import com.example.ialocal.chat.QueuedChatMessage
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.ConversationEntity
import com.example.ialocal.data.ConversationListItem
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.files.AttachmentContentProcessor
import com.example.ialocal.files.AttachmentImporter
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.models.BuiltInProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val conversationId: String,
    private val initialModelId: String?,
    private val repository: ChatRepository,
    private val generationManager: ChatGenerationManager,
    private val attachmentImporter: AttachmentImporter,
    private val attachmentProcessor: AttachmentContentProcessor,
    private val modelRepository: ModelRepository,
) : ViewModel() {
    val conversation: StateFlow<ConversationEntity?> = repository.observeConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val messages: StateFlow<List<MessageWithAttachments>> = repository.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversations: StateFlow<List<ConversationListItem>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Profiles and installed models are selectable immediately. Runtime verification is still
    // enforced against the real repository entity immediately before the first inference.
    val agents: StateFlow<List<AgentEntity>> = modelRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models: StateFlow<List<AiModelEntity>> = modelRepository.models
        .map { installed ->
            installed.map { model ->
                if (model.verificationStatus == ModelVerificationStatus.VERIFIED.name) model
                else model.copy(verificationStatus = ModelVerificationStatus.VERIFIED.name)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val agentUsageCounts: StateFlow<Map<String, Int>> = modelRepository.agentUsageCounts
    val selectedProfile: StateFlow<BuiltInProfile> = modelRepository.selectedProfile

    private val _draft = MutableStateFlow(""); val draft: StateFlow<String> = _draft.asStateFlow()
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList()); val pendingAttachments = _pendingAttachments.asStateFlow()
    // Generation lives in the app-scoped manager so leaving this screen never cancels an answer.
    private val generation = generationManager.session(conversationId)
    val isGenerating: StateFlow<Boolean> = generation.isGenerating
    /** The message is waiting while other conversations are being answered. */
    val waitingForSlot: StateFlow<Boolean> = generation.waitingForSlot
    private val _isProcessingAttachments = MutableStateFlow(false); val isProcessingAttachments = _isProcessingAttachments.asStateFlow()
    val queuedMessages: StateFlow<List<QueuedChatMessage>> = generation.queued
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()
    private val _selectedModelId = MutableStateFlow(initialModelId)

    init {
        viewModelScope.launch {
            generation.error.collect { message ->
                if (message != null) {
                    _error.value = message
                    generationManager.clearError(conversationId)
                }
            }
        }
        viewModelScope.launch {
            generation.resolvedAgentId.collect { agentId -> if (agentId != null) _selectedAgentId.value = agentId }
        }
        viewModelScope.launch {
            combine(modelRepository.models, selectedProfile, _selectedModelId) { installed, _, requestedId ->
                requestedId?.takeIf { id -> installed.any { it.id == id } }
                    ?: installed.firstOrNull { it.isActive }?.id
                    ?: installed.firstOrNull()?.id
            }.collect { modelId ->
                runCatching {
                    if (modelId == null) {
                        _selectedAgentId.value = null
                        repository.setConversationAgent(conversationId, null)
                    } else {
                        selectModelInternal(modelId, recordUsage = false)
                    }
                }.onFailure {
                    _error.value = it.message ?: "Não foi possível preparar a IA selecionada."
                }
            }
        }
    }

    fun setDraft(value: String) { _draft.value = value }
    fun clearError() { _error.value = null }

    /** Works without an installed model; the matching agent is picked once a model exists. */
    fun selectProfile(profile: BuiltInProfile) {
        modelRepository.setSelectedProfile(profile)
    }

    fun selectAgent(agentId: String?) {
        viewModelScope.launch {
            if (agentId == null) {
                _selectedAgentId.value = null
                repository.setConversationAgent(conversationId, null)
                return@launch
            }
            runCatching {
                val agent = requireNotNull(modelRepository.getAgent(agentId)) { "Perfil não encontrado." }
                val profile = requireNotNull(BuiltInProfile.entries.firstOrNull { it.displayName == agent.name }) {
                    "Somente Programador e Sem censura estão disponíveis."
                }
                modelRepository.setSelectedProfile(profile)
                _selectedModelId.value = agent.modelId
                _selectedAgentId.value = agent.id
                modelRepository.recordAgentUse(agent.id)
                repository.setConversationAgent(conversationId, agent.id)
            }.onFailure {
                _error.value = it.message ?: "Não foi possível selecionar este perfil."
            }
        }
    }

    fun setDefaultAgent(agentId: String) {
        viewModelScope.launch {
            runCatching { modelRepository.setDefaultAgent(agentId) }
                .onFailure { _error.value = it.message ?: "Não foi possível definir o perfil padrão." }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            runCatching {
                selectModelInternal(modelId, recordUsage = true)
                _selectedModelId.value = modelId
            }
                .onFailure {
                    _error.value = it.message ?: "Não foi possível selecionar o modelo."
                }
        }
    }

    private suspend fun selectModelInternal(modelId: String, recordUsage: Boolean) {
        requireNotNull(modelRepository.getModel(modelId)) { "Modelo não encontrado." }
        modelRepository.ensureStarterProfiles(modelId)
        val modelAgents = modelRepository.getAgents().filter { it.modelId == modelId }
        val agent = modelAgents.firstOrNull { it.name == selectedProfile.value.displayName }
            ?: throw IllegalStateException("Nenhum perfil foi encontrado para este modelo.")
        _selectedAgentId.value = agent.id
        if (recordUsage) modelRepository.recordAgentUse(agent.id)
        repository.setConversationAgent(conversationId, agent.id)
    }

    fun createAgentProfile(
        modelId: String,
        name: String,
        systemPrompt: String,
        temperature: Float = 0.3f,
        onCreated: (AgentEntity) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                modelRepository.createAgentProfile(
                    modelId = modelId,
                    name = name,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                )
            }.onSuccess { agent ->
                _selectedAgentId.value = agent.id
                modelRepository.recordAgentUse(agent.id)
                repository.setConversationAgent(conversationId, agent.id)
                onCreated(agent)
            }.onFailure {
                _error.value = it.message ?: "Não foi possível criar o perfil de I.A."
            }
        }
    }

    fun updateAgent(agent: AgentEntity) {
        viewModelScope.launch {
            runCatching { modelRepository.updateAgent(agent) }
                .onFailure { _error.value = it.message ?: "Não foi possível salvar as configurações do agente." }
        }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            runCatching {
                modelRepository.getAgent(agentId) ?: return@runCatching
                val wasSelected = _selectedAgentId.value == agentId || conversation.value?.agentId == agentId
                modelRepository.deleteAgent(agentId)
                if (wasSelected) {
                    val replacement = modelRepository.resolveAgentForUse(null)
                    _selectedAgentId.value = replacement?.id
                    repository.setConversationAgent(conversationId, replacement?.id)
                }
            }.onFailure {
                _error.value = it.message ?: "Não foi possível excluir o perfil de I.A."
            }
        }
    }

    fun createConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.createConversation() }
                .onSuccess(onCreated)
                .onFailure { _error.value = it.message ?: "Não foi possível criar uma nova conversa." }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _isProcessingAttachments.value = true
            var raw: PendingAttachment? = null
            try {
                raw = withContext(Dispatchers.IO) { attachmentImporter.import(uri) }
                _pendingAttachments.value += attachmentProcessor.enrich(raw)
            } catch (t: Throwable) {
                raw?.let { withContext(Dispatchers.IO) { runCatching { File(it.localPath).delete() } } }
                _error.value = t.message ?: "Não foi possível processar o arquivo."
            } finally { _isProcessingAttachments.value = false }
        }
    }

    fun addPendingAttachment(attachment: PendingAttachment) {
        viewModelScope.launch {
            _isProcessingAttachments.value = true
            try {
                _pendingAttachments.value += attachmentProcessor.enrich(attachment)
            } catch (t: Throwable) {
                withContext(Dispatchers.IO) { runCatching { File(attachment.localPath).delete() } }
                _error.value = t.message ?: "Não foi possível processar o áudio."
            } finally {
                _isProcessingAttachments.value = false
            }
        }
    }

    fun removePendingAttachment(id: String) {
        val removed = _pendingAttachments.value.firstOrNull { it.id == id }
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
        removed?.let { viewModelScope.launch(Dispatchers.IO) { runCatching { File(it.localPath).delete() } } }
    }

    fun removeQueuedMessage(index: Int) {
        val removed = generationManager.removeQueued(conversationId, index) ?: return
        removed.attachments.forEach { attachment ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { File(attachment.localPath).delete() } }
        }
    }

    fun stopGeneration() = generationManager.stop(conversationId)

    fun send() {
        if (_isProcessingAttachments.value) return
        val currentDraft = _draft.value.trim()
        val attachments = _pendingAttachments.value
        if (currentDraft.isBlank() && attachments.isEmpty()) return
        val content = when {
            currentDraft.isNotBlank() -> currentDraft
            attachments.any { it.mimeType?.startsWith("audio/") == true } -> "Analise o áudio enviado."
            else -> "Analise o arquivo enviado."
        }
        val outgoing = QueuedChatMessage(content, attachments)
        _draft.value = ""
        _pendingAttachments.value = emptyList()
        _error.value = null
        generationManager.send(conversationId, outgoing, currentTarget())
    }

    fun rerunAssistant(messageId: String) {
        if (isGenerating.value || _isProcessingAttachments.value) return
        generationManager.rerun(conversationId, messageId, currentTarget())
    }

    private fun currentTarget() = GenerationTarget(
        preferredAgentId = _selectedAgentId.value,
        selectedModelId = _selectedModelId.value,
    )

    class Factory(
        private val conversationId: String,
        private val initialModelId: String?,
        private val repository: ChatRepository,
        private val generationManager: ChatGenerationManager,
        private val attachmentImporter: AttachmentImporter,
        private val attachmentProcessor: AttachmentContentProcessor,
        private val modelRepository: ModelRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            conversationId = conversationId,
            initialModelId = initialModelId,
            repository = repository,
            generationManager = generationManager,
            attachmentImporter = attachmentImporter,
            attachmentProcessor = attachmentProcessor,
            modelRepository = modelRepository,
        ) as T
    }
}
