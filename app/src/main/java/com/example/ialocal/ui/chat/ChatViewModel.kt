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
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.files.AttachmentContentProcessor
import com.example.ialocal.files.AttachmentImporter
import com.example.ialocal.models.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val conversationId: String,
    private val repository: ChatRepository,
    private val aiGateway: AiGateway,
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
    val agents: StateFlow<List<AgentEntity>> = modelRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models: StateFlow<List<AiModelEntity>> = modelRepository.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val agentUsageCounts: StateFlow<Map<String, Int>> = modelRepository.agentUsageCounts

    private val _draft = MutableStateFlow(""); val draft: StateFlow<String> = _draft.asStateFlow()
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList()); val pendingAttachments = _pendingAttachments.asStateFlow()
    private val _isGenerating = MutableStateFlow(false); val isGenerating = _isGenerating.asStateFlow()
    private val _isProcessingAttachments = MutableStateFlow(false); val isProcessingAttachments = _isProcessingAttachments.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()
    private var generationJob: Job? = null

    fun setDraft(value: String) { _draft.value = value }
    fun clearError() { _error.value = null }

    fun selectAgent(agentId: String?) {
        _selectedAgentId.value = agentId
        agentId?.let(modelRepository::recordAgentUse)
        viewModelScope.launch { repository.setConversationAgent(conversationId, agentId) }
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
                val modelAgents = modelRepository.getAgents().filter { it.modelId == modelId }
                val agent = modelAgents.firstOrNull { it.isDefault }
                    ?: modelAgents.maxByOrNull { modelRepository.agentUsageCounts.value[it.id] ?: 0 }
                    ?: modelAgents.firstOrNull()
                _selectedAgentId.value = agent?.id
                agent?.let { modelRepository.recordAgentUse(it.id) }
                repository.setConversationAgent(conversationId, agent?.id)
            }.onFailure {
                _error.value = it.message ?: "Não foi possível selecionar o modelo."
            }
        }
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
                val deleted = modelRepository.getAgent(agentId) ?: return@runCatching
                val wasSelected = _selectedAgentId.value == agentId || conversation.value?.agentId == agentId
                modelRepository.deleteAgent(agentId)
                if (wasSelected) {
                    val remaining = modelRepository.getAgents().filter { it.modelId == deleted.modelId }
                    val replacement = remaining.firstOrNull { it.isDefault }
                        ?: remaining.maxByOrNull { modelRepository.agentUsageCounts.value[it.id] ?: 0 }
                        ?: remaining.firstOrNull()
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

    fun stopGeneration() { generationJob?.cancel() }

    fun send() {
        if (_isGenerating.value || _isProcessingAttachments.value) return
        val currentDraft = _draft.value.trim()
        val attachments = _pendingAttachments.value
        if (currentDraft.isBlank() && attachments.isEmpty()) return
        val content = when {
            currentDraft.isNotBlank() -> currentDraft
            attachments.any { it.mimeType?.startsWith("audio/") == true } -> "Analise o áudio enviado."
            else -> "Analise o arquivo enviado."
        }
        _draft.value = ""; _pendingAttachments.value = emptyList(); _isGenerating.value = true; _error.value = null

        val historyBeforeSend = messages.value.map(::toAiMessageWithPersistedAttachments)
        generationJob = viewModelScope.launch {
            var assistantId: String? = null
            try {
                repository.addMessage(conversationId, MessageRole.USER, content, attachments)
                val agentId = _selectedAgentId.value
                    ?: conversation.value?.agentId
                    ?: modelRepository.getDefaultAgent()?.id
                if (agentId != null) repository.setConversationAgent(conversationId, agentId)

                val history = historyBeforeSend + AiChatMessage("user", content)
                val replyId = repository.addMessage(conversationId, MessageRole.ASSISTANT, "", status = MessageStatus.SENDING)
                assistantId = replyId
                var accumulated = ""
                aiGateway.streamChat(AiChatRequest(
                    conversationId = conversationId,
                    messages = history,
                    attachments = attachments,
                    agentId = agentId,
                )).collect { chunk ->
                    accumulated += chunk
                    repository.updateMessageContent(replyId, accumulated)
                }
                repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
            } catch (cancel: CancellationException) {
                assistantId?.let { repository.updateMessageStatus(it, MessageStatus.COMPLETE) }
                throw cancel
            } catch (t: Throwable) {
                assistantId?.let { repository.updateMessageStatus(it, MessageStatus.ERROR) }
                _error.value = t.message ?: "Falha ao gerar a resposta."
            } finally {
                _isGenerating.value = false
                generationJob = null
            }
        }
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
        val content = if (contexts.isEmpty()) item.message.content else
            item.message.content + "\n\n[Contexto persistido dos anexos]\n" + contexts.joinToString("\n\n---\n\n")
        return AiChatMessage(item.message.role.lowercase(), content)
    }

    companion object {
        private const val MAX_HISTORY_ATTACHMENT_PER_FILE = 4_000
        private const val MAX_HISTORY_ATTACHMENT_CHARS = 6_000
    }

    class Factory(
        private val conversationId: String,
        private val repository: ChatRepository,
        private val aiGateway: AiGateway,
        private val attachmentImporter: AttachmentImporter,
        private val attachmentProcessor: AttachmentContentProcessor,
        private val modelRepository: ModelRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            conversationId, repository, aiGateway, attachmentImporter, attachmentProcessor, modelRepository,
        ) as T
    }
}
