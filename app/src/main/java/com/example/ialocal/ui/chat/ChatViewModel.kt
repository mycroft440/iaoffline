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
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.ConversationEntity
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
    modelRepository: ModelRepository,
) : ViewModel() {
    val conversation: StateFlow<ConversationEntity?> = repository.observeConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val messages: StateFlow<List<MessageWithAttachments>> = repository.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val agents: StateFlow<List<AgentEntity>> = modelRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow(""); val draft: StateFlow<String> = _draft.asStateFlow()
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList()); val pendingAttachments = _pendingAttachments.asStateFlow()
    private val _isGenerating = MutableStateFlow(false); val isGenerating = _isGenerating.asStateFlow()
    private val _isProcessingAttachments = MutableStateFlow(false); val isProcessingAttachments = _isProcessingAttachments.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    private var generationJob: Job? = null

    fun setDraft(value: String) { _draft.value = value }
    fun clearError() { _error.value = null }
    fun selectAgent(agentId: String?) { _selectedAgentId.value = agentId; viewModelScope.launch { repository.setConversationAgent(conversationId, agentId) } }

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
                val history = historyBeforeSend + AiChatMessage("user", content)
                val replyId = repository.addMessage(conversationId, MessageRole.ASSISTANT, "", status = MessageStatus.SENDING)
                assistantId = replyId
                var accumulated = ""
                aiGateway.streamChat(AiChatRequest(
                    conversationId = conversationId,
                    messages = history,
                    attachments = attachments,
                    agentId = _selectedAgentId.value ?: conversation.value?.agentId,
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
