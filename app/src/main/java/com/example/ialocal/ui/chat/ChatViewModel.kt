package com.example.ialocal.ui.chat

import android.net.Uri
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.ConversationEntity
import com.example.ialocal.data.ConversationListItem
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.files.AttachmentContentProcessor
import com.example.ialocal.files.AttachmentImporter
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.models.BuiltInProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.yield

data class QueuedChatMessage(
    val content: String,
    val attachments: List<PendingAttachment> = emptyList(),
)

class ChatViewModel(
    private val conversationId: String,
    private val initialModelId: String?,
    private val repository: ChatRepository,
    private val aiGateway: AiGateway,
    private val attachmentImporter: AttachmentImporter,
    private val attachmentProcessor: AttachmentContentProcessor,
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
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
    private val _isGenerating = MutableStateFlow(false); val isGenerating = _isGenerating.asStateFlow()
    private val _isProcessingAttachments = MutableStateFlow(false); val isProcessingAttachments = _isProcessingAttachments.asStateFlow()
    private val _queuedMessages = MutableStateFlow<List<QueuedChatMessage>>(emptyList()); val queuedMessages: StateFlow<List<QueuedChatMessage>> = _queuedMessages.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()
    private val _selectedModelId = MutableStateFlow(initialModelId)
    private var generationJob: Job? = null

    init {
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
        val current = _queuedMessages.value
        if (index !in current.indices) return
        val removed = current[index]
        _queuedMessages.value = current.filterIndexed { itemIndex, _ -> itemIndex != index }
        removed.attachments.forEach { attachment ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { File(attachment.localPath).delete() } }
        }
    }

    fun stopGeneration() { generationJob?.cancel() }

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

        if (_isGenerating.value) {
            _queuedMessages.value = _queuedMessages.value + outgoing
        } else {
            startGeneration(outgoing)
        }
    }

    fun rerunAssistant(messageId: String) {
        if (_isGenerating.value || _isProcessingAttachments.value) return
        val snapshot = messages.value
        val assistantIndex = snapshot.indexOfFirst {
            it.message.id == messageId && it.message.role == MessageRole.ASSISTANT.name
        }
        if (assistantIndex < 0) return
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull {
            snapshot[it].message.role == MessageRole.USER.name
        } ?: return

        val history = snapshot.take(userIndex + 1).map(::toAiMessageWithPersistedAttachments)
        startAssistantOnlyGeneration(history)
    }

    private fun startGeneration(outgoing: QueuedChatMessage) {
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            try {
                // Give Room's flows a chance to publish the previous completed response before
                // building history for the next queued turn.
                yield()
                val historyBeforeSend = messages.value.map(::toAiMessageWithPersistedAttachments)
                repository.addMessage(conversationId, MessageRole.USER, outgoing.content, outgoing.attachments)
                val history = historyBeforeSend + AiChatMessage("user", outgoing.content)
                generateAssistant(history, outgoing.attachments)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _error.value = t.message ?: "Falha ao gerar a resposta."
            } finally {
                _isGenerating.value = false
                generationJob = null
                startNextQueuedMessage()
            }
        }
    }

    private fun startAssistantOnlyGeneration(history: List<AiChatMessage>) {
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            try {
                generateAssistant(history, emptyList())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _error.value = t.message ?: "Falha ao refazer a resposta."
            } finally {
                _isGenerating.value = false
                generationJob = null
                startNextQueuedMessage()
            }
        }
    }

    private suspend fun generateAssistant(
        history: List<AiChatMessage>,
        attachments: List<PendingAttachment>,
    ) {
        val preferredAgentId = _selectedAgentId.value ?: conversation.value?.agentId
        var preferredAgent = if (preferredAgentId != null) {
            modelRepository.getAgent(preferredAgentId)
        } else {
            null
        }
        if (preferredAgent?.name != selectedProfile.value.displayName) {
            val modelId = _selectedModelId.value ?: preferredAgent?.modelId
                ?: modelRepository.getModels().firstOrNull()?.id
            preferredAgent = modelId?.let { id ->
                modelRepository.ensureStarterProfiles(id)
                modelRepository.getAgents().firstOrNull {
                    it.modelId == id && it.name == selectedProfile.value.displayName
                }
            }
        }
        if (preferredAgent != null) {
            val selectedModel = requireNotNull(modelRepository.getModel(preferredAgent.modelId)) {
                "O modelo selecionado não está mais disponível."
            }
            if (selectedModel.verificationStatus != ModelVerificationStatus.VERIFIED.name) {
                modelManager.retryVerification(selectedModel.id)
            }
        }

        val agent = modelRepository.resolveAgentForUse(preferredAgent?.id)
            ?: throw IllegalStateException("Nenhum perfil com modelo local disponível pôde ser preparado para uso.")
        val agentId = agent.id
        _selectedAgentId.value = agentId
        if (conversation.value?.agentId != agentId) {
            repository.setConversationAgent(conversationId, agentId)
        }

        val replyId = repository.addMessage(
            conversationId,
            MessageRole.ASSISTANT,
            "",
            status = MessageStatus.SENDING,
        )
        val startedAt = System.currentTimeMillis()
        var reasoningEndedAt: Long? = null
        var accumulated = ""
        try {
            aiGateway.streamChat(
                AiChatRequest(
                    conversationId = conversationId,
                    messages = history,
                    attachments = attachments,
                    agentId = agentId,
                )
            ).collect { chunk ->
                accumulated += chunk
                if (reasoningEndedAt == null && containsReasoningClose(accumulated)) {
                    reasoningEndedAt = System.currentTimeMillis()
                }
                repository.updateMessageContent(replyId, accumulated)
            }
            val elapsed = (reasoningEndedAt ?: System.currentTimeMillis()) - startedAt
            repository.updateMessageContent(replyId, appendReasoningMetadata(accumulated, elapsed))
            repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
        } catch (cancel: CancellationException) {
            val elapsed = (reasoningEndedAt ?: System.currentTimeMillis()) - startedAt
            repository.updateMessageContent(replyId, appendReasoningMetadata(accumulated, elapsed))
            repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
            throw cancel
        } catch (t: Throwable) {
            repository.updateMessageStatus(replyId, MessageStatus.ERROR)
            throw t
        }
    }

    private fun startNextQueuedMessage() {
        val next = _queuedMessages.value.firstOrNull() ?: return
        _queuedMessages.value = _queuedMessages.value.drop(1)
        startGeneration(next)
    }

    private fun toAiMessageWithPersistedAttachments(item: MessageWithAttachments): AiChatMessage {
        var remaining = MAX_HISTORY_ATTACHMENT_CHARS
        val contexts = buildList {
            item.attachments.forEach { attachment ->
                if (remaining <= 0) return@forEach
                val raw = attachment.extractedText?.takeIf { it.isNotBlank() } ?: return@forEach
                val excerpt = raw.take(minOf(MAX_HISTORY_ATTACHMENT_PER_FILE, remaining))
                add("${attachment.fileName}:\n$excerpt")
                remaining -= excerpt.length
            }
        }
        val baseContent = if (item.message.role == MessageRole.ASSISTANT.name) {
            assistantAnswerForHistory(item.message.content)
        } else {
            item.message.content
        }
        val content = if (contexts.isEmpty()) baseContent else
            baseContent + "\n\n[Contexto persistido dos anexos]\n" + contexts.joinToString("\n\n---\n\n")
        return AiChatMessage(item.message.role.lowercase(), content)
    }

    private fun assistantAnswerForHistory(raw: String): String {
        val withoutMetadata = REASONING_METADATA.replace(raw, "").trim()
        val tagged = REASONING_BLOCK.find(withoutMetadata)
        if (tagged != null) {
            return withoutMetadata.removeRange(tagged.range).trim()
        }
        return withoutMetadata
    }

    private fun appendReasoningMetadata(content: String, elapsedMs: Long): String {
        val clean = REASONING_METADATA.replace(content, "").trimEnd()
        return "$clean\n\n<!--nexus_reasoning_ms:${elapsedMs.coerceAtLeast(0L)}-->"
    }

    private fun containsReasoningClose(content: String): Boolean =
        REASONING_CLOSE.containsMatchIn(content)

    companion object {
        private const val MAX_HISTORY_ATTACHMENT_PER_FILE = 4_000
        private const val MAX_HISTORY_ATTACHMENT_CHARS = 6_000
        private val REASONING_CLOSE = Regex("(?is)</think(?:ing)?>")
        private val REASONING_BLOCK = Regex("(?is)<think(?:ing)?>(.*?)</think(?:ing)?>")
        private val REASONING_METADATA = Regex("(?is)\\s*<!--nexus_reasoning_ms:(\\d+)-->\\s*$")
    }

    class Factory(
        private val conversationId: String,
        private val initialModelId: String?,
        private val repository: ChatRepository,
        private val aiGateway: AiGateway,
        private val attachmentImporter: AttachmentImporter,
        private val attachmentProcessor: AttachmentContentProcessor,
        private val modelRepository: ModelRepository,
        private val modelManager: ModelManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            conversationId = conversationId,
            initialModelId = initialModelId,
            repository = repository,
            aiGateway = aiGateway,
            attachmentImporter = attachmentImporter,
            attachmentProcessor = attachmentProcessor,
            modelRepository = modelRepository,
            modelManager = modelManager,
        ) as T
    }
}
