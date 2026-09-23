package com.example.ialocal.chat

import android.content.Context
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueuedChatMessage(
    val content: String,
    val attachments: List<PendingAttachment> = emptyList(),
)

/** The model/profile the user had selected when a message was sent. */
data class GenerationTarget(
    val preferredAgentId: String?,
    val selectedModelId: String?,
)

/**
 * Owns chat generation outside any screen lifecycle, so leaving the chat or backgrounding the app
 * never interrupts an answer. While anything is generating, [ChatGenerationService] keeps the
 * process in the foreground and the CPU awake.
 */
class ChatGenerationManager(
    context: Context,
    private val repository: ChatRepository,
    private val aiGateway: AiGateway,
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = ConcurrentHashMap<String, Session>()

    private val _activeGenerations = MutableStateFlow(0)
    val activeGenerations: StateFlow<Int> = _activeGenerations.asStateFlow()

    class Session internal constructor() {
        internal val _isGenerating = MutableStateFlow(false)
        val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
        internal val _queued = MutableStateFlow<List<QueuedChatMessage>>(emptyList())
        val queued: StateFlow<List<QueuedChatMessage>> = _queued.asStateFlow()
        internal val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()
        internal val _resolvedAgentId = MutableStateFlow<String?>(null)
        /** The agent actually used by the latest generation in this conversation. */
        val resolvedAgentId: StateFlow<String?> = _resolvedAgentId.asStateFlow()
        internal var job: Job? = null
        internal var queuedTarget: GenerationTarget? = null
    }

    fun session(conversationId: String): Session = sessions.getOrPut(conversationId) { Session() }

    fun send(conversationId: String, outgoing: QueuedChatMessage, target: GenerationTarget) {
        val session = session(conversationId)
        session._error.value = null
        startOrQueue(conversationId, session, outgoing, target)
    }

    private fun startOrQueue(
        conversationId: String,
        session: Session,
        outgoing: QueuedChatMessage,
        target: GenerationTarget,
    ) {
        if (session._isGenerating.value) {
            session._queued.value = session._queued.value + outgoing
            session.queuedTarget = target
        } else {
            launchGeneration(conversationId, session, target, "Falha ao gerar a resposta.") {
                val historyBeforeSend = repository.getMessages(conversationId).map(::toAiMessage)
                repository.addMessage(conversationId, MessageRole.USER, outgoing.content, outgoing.attachments)
                generateAssistant(
                    conversationId,
                    session,
                    target,
                    historyBeforeSend + AiChatMessage("user", outgoing.content),
                    outgoing.attachments,
                )
            }
        }
    }

    fun rerun(conversationId: String, messageId: String, target: GenerationTarget) {
        val session = session(conversationId)
        if (session._isGenerating.value) return
        launchGeneration(conversationId, session, target, "Falha ao refazer a resposta.") {
            val snapshot = repository.getMessages(conversationId)
            val assistantIndex = snapshot.indexOfFirst {
                it.message.id == messageId && it.message.role == MessageRole.ASSISTANT.name
            }
            if (assistantIndex < 0) return@launchGeneration
            val userIndex = (assistantIndex - 1 downTo 0).firstOrNull {
                snapshot[it].message.role == MessageRole.USER.name
            } ?: return@launchGeneration
            val history = snapshot.take(userIndex + 1).map(::toAiMessage)
            generateAssistant(conversationId, session, target, history, emptyList())
        }
    }

    fun stop(conversationId: String) {
        sessions[conversationId]?.job?.cancel()
    }

    fun stopAll() {
        sessions.values.forEach { session ->
            session._queued.value = emptyList()
            session.job?.cancel()
        }
    }

    fun removeQueued(conversationId: String, index: Int): QueuedChatMessage? {
        val session = sessions[conversationId] ?: return null
        val current = session._queued.value
        val removed = current.getOrNull(index) ?: return null
        session._queued.value = current.filterIndexed { itemIndex, _ -> itemIndex != index }
        return removed
    }

    fun clearError(conversationId: String) {
        sessions[conversationId]?._error?.value = null
    }

    private fun launchGeneration(
        conversationId: String,
        session: Session,
        target: GenerationTarget,
        failureMessage: String,
        block: suspend () -> Unit,
    ) {
        session._isGenerating.value = true
        // Only the first concurrent generation starts the service; later ones (including queued turns
        // started while the app is in the background) run under the service that is already active.
        if (_activeGenerations.value == 0) ChatGenerationService.start(appContext)
        _activeGenerations.value += 1
        session.job = scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                session._error.value = t.message ?: failureMessage
            } finally {
                session.job = null
                session._isGenerating.value = false
                // Start the next queued turn before releasing this one so the count never drops to
                // zero in between and the foreground service is not stopped mid-queue.
                startNextQueued(conversationId, session, target)
                _activeGenerations.value -= 1
            }
        }
    }

    private fun startNextQueued(conversationId: String, session: Session, previousTarget: GenerationTarget) {
        val next = session._queued.value.firstOrNull() ?: return
        session._queued.value = session._queued.value.drop(1)
        startOrQueue(conversationId, session, next, session.queuedTarget ?: previousTarget)
    }

    private suspend fun generateAssistant(
        conversationId: String,
        session: Session,
        target: GenerationTarget,
        history: List<AiChatMessage>,
        attachments: List<PendingAttachment>,
    ) {
        val profileName = modelRepository.selectedProfile.value.displayName
        val conversation = repository.getConversation(conversationId)
        val preferredAgentId = target.preferredAgentId ?: conversation?.agentId
        var preferredAgent = preferredAgentId?.let { modelRepository.getAgent(it) }
        if (preferredAgent?.name != profileName) {
            val modelId = target.selectedModelId ?: preferredAgent?.modelId
                ?: modelRepository.getModels().firstOrNull()?.id
            preferredAgent = modelId?.let { id ->
                modelRepository.ensureStarterProfiles(id)
                modelRepository.getAgents().firstOrNull { it.modelId == id && it.name == profileName }
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
        session._resolvedAgentId.value = agent.id
        if (conversation?.agentId != agent.id) {
            repository.setConversationAgent(conversationId, agent.id)
        }

        val replyId = repository.addMessage(conversationId, MessageRole.ASSISTANT, "", status = MessageStatus.SENDING)
        val startedAt = System.currentTimeMillis()
        var reasoningStartedAt: Long? = null
        var reasoningEndedAt: Long? = null
        val accumulated = StringBuilder()
        fun finalContent(): String {
            val now = System.currentTimeMillis()
            val reasoningMs = reasoningStartedAt?.let { start -> (reasoningEndedAt ?: now) - start }
            return AssistantContent.withTimings(accumulated.toString(), now - startedAt, reasoningMs)
        }
        try {
            aiGateway.streamChat(
                AiChatRequest(
                    conversationId = conversationId,
                    messages = history,
                    attachments = attachments,
                    agentId = agent.id,
                )
            ).collect { chunk ->
                accumulated.append(chunk)
                val text = accumulated.toString()
                if (reasoningStartedAt == null && AssistantContent.hasReasoningStarted(text)) {
                    reasoningStartedAt = System.currentTimeMillis()
                }
                if (reasoningStartedAt != null && reasoningEndedAt == null && AssistantContent.hasReasoningEnded(text)) {
                    reasoningEndedAt = System.currentTimeMillis()
                }
                repository.updateMessageContent(replyId, text)
            }
            repository.updateMessageContent(replyId, finalContent())
            repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                repository.updateMessageContent(replyId, finalContent())
                repository.updateMessageStatus(replyId, MessageStatus.COMPLETE)
            }
            throw cancel
        } catch (t: Throwable) {
            repository.updateMessageStatus(replyId, MessageStatus.ERROR)
            throw t
        }
    }

    private fun toAiMessage(item: MessageWithAttachments): AiChatMessage {
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
            AssistantContent.answerForHistory(item.message.content)
        } else {
            item.message.content
        }
        val content = if (contexts.isEmpty()) baseContent else
            baseContent + "\n\n[Contexto persistido dos anexos]\n" + contexts.joinToString("\n\n---\n\n")
        return AiChatMessage(item.message.role.lowercase(), content)
    }

    companion object {
        private const val MAX_HISTORY_ATTACHMENT_PER_FILE = 4_000
        private const val MAX_HISTORY_ATTACHMENT_CHARS = 6_000
    }
}
