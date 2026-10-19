package com.example.ialocal.chat

import android.content.Context
import com.example.ialocal.agent.AiOrchestrator
import com.example.ialocal.ai.AiChatMessage
import com.example.ialocal.ai.AiChatRequest
import com.example.ialocal.ai.AiGateway
import com.example.ialocal.data.AgentEntity
import com.example.ialocal.data.ChatRepository
import com.example.ialocal.data.MessageRole
import com.example.ialocal.data.MessageStatus
import com.example.ialocal.data.MessageWithAttachments
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.data.PendingAttachment
import com.example.ialocal.models.ModelManager
import com.example.ialocal.models.ModelRepository
import com.example.ialocal.models.provider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val orchestrator: AiOrchestrator,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = ConcurrentHashMap<String, Session>()
    /** The latest [prepare]; each one waits for the previous, so they never overlap. */
    private var warmUp: Job? = null

    private val _activeGenerations = MutableStateFlow(0)
    val activeGenerations: StateFlow<Int> = _activeGenerations.asStateFlow()

    /**
     * At most [MAX_CONCURRENT_ANSWERS] conversations are answered at once. The semaphore is fair, so
     * a message sent from a third conversation waits and is answered in the order it arrived.
     */
    private val answerSlots = Semaphore(MAX_CONCURRENT_ANSWERS)

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
        internal val _waitingForSlot = MutableStateFlow(false)
        /** True while this conversation's message waits for other conversations to be answered. */
        val waitingForSlot: StateFlow<Boolean> = _waitingForSlot.asStateFlow()
        internal var job: Job? = null
        internal var queuedTarget: GenerationTarget? = null
    }

    fun session(conversationId: String): Session = sessions.getOrPut(conversationId) { Session() }

    /**
     * Loads the AI a message sent now would use and processes its instructions and this
     * conversation in the background, while the user writes, so the answer starts sooner. Skipped
     * while answers are running: they need the model, and their conversations stay in its context.
     */
    fun prepare(conversationId: String, target: GenerationTarget) {
        if (_activeGenerations.value > 0) return
        val previous = warmUp
        warmUp = scope.launch {
            previous?.join()
            if (_activeGenerations.value > 0) return@launch
            try {
                val agent = resolveAgent(conversationId, target, verify = false) ?: return@launch
                val history = repository.getMessages(conversationId).map(::toAiMessage)
                // A message sent meanwhile already loads the model and reads the conversation.
                if (_activeGenerations.value > 0) return@launch
                orchestrator.warmUpAgent(agent.id, history)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Only time is lost; sending the message loads the model again and reports the error.
            }
        }
    }

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
                // The message shows up in the chat right away, even if it has to wait for a slot.
                val historyBeforeSend = repository.getMessages(conversationId).map(::toAiMessage)
                repository.addMessage(conversationId, MessageRole.USER, outgoing.content, outgoing.attachments)
                awaitAnswerSlot(session) {
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
            awaitAnswerSlot(session) { generateAssistant(conversationId, session, target, history, emptyList()) }
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

    private suspend fun awaitAnswerSlot(session: Session, block: suspend () -> Unit) {
        session._waitingForSlot.value = true
        try {
            answerSlots.withPermit {
                session._waitingForSlot.value = false
                block()
            }
        } finally {
            session._waitingForSlot.value = false
        }
    }

    private fun startNextQueued(conversationId: String, session: Session, previousTarget: GenerationTarget) {
        val next = session._queued.value.firstOrNull() ?: return
        session._queued.value = session._queued.value.drop(1)
        startOrQueue(conversationId, session, next, session.queuedTarget ?: previousTarget)
    }

    /**
     * The profile, and so the model, that answers a message sent now. With [verify], a model not
     * yet tested is tested first and stays loaded for the answer; without it, such a model gives null.
     */
    private suspend fun resolveAgent(conversationId: String, target: GenerationTarget, verify: Boolean): AgentEntity? {
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
                if (!verify) return null
                modelManager.retryVerification(selectedModel.id, keepLoaded = true)
            }
        }
        return modelRepository.resolveAgentForUse(preferredAgent?.id)
    }

    private suspend fun generateAssistant(
        conversationId: String,
        session: Session,
        target: GenerationTarget,
        history: List<AiChatMessage>,
        attachments: List<PendingAttachment>,
    ) {
        val conversation = repository.getConversation(conversationId)
        val agent = resolveAgent(conversationId, target, verify = true)
            ?: throw IllegalStateException("Nenhum perfil com modelo local disponível pôde ser preparado para uso.")
        session._resolvedAgentId.value = agent.id
        if (conversation?.agentId != agent.id) {
            repository.setConversationAgent(conversationId, agent.id)
        }

        // Shown next to the reply: the company of the model answering it.
        val provider = modelRepository.getModel(agent.modelId)?.provider()
        val replyId = repository.addMessage(conversationId, MessageRole.ASSISTANT, "", status = MessageStatus.SENDING)
        val startedAt = System.currentTimeMillis()
        var reasoningStartedAt: Long? = null
        var reasoningEndedAt: Long? = null
        val accumulated = StringBuilder()
        fun finalContent(): String {
            val now = System.currentTimeMillis()
            val reasoningMs = reasoningStartedAt?.let { start -> (reasoningEndedAt ?: now) - start }
            return AssistantContent.withTimings(accumulated.toString(), now - startedAt, reasoningMs, provider)
        }
        try {
            // Each saved update re-reads the conversation and redraws the chat, so the text is
            // saved a few times per second instead of on every token: on the main thread, a long
            // answer saved per token kept the screen from responding.
            coroutineScope {
                var unsaved = false
                val saver = launch {
                    while (true) {
                        delay(UI_UPDATE_INTERVAL_MS)
                        if (unsaved) {
                            unsaved = false
                            repository.updateMessageContent(replyId, accumulated.toString())
                        }
                    }
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
                        // Only the end of the text can hold a tag that just arrived.
                        val tail = accumulated.substring(maxOf(0, accumulated.length - chunk.length - TAG_WINDOW))
                        if (reasoningStartedAt == null && AssistantContent.hasReasoningStarted(tail)) {
                            reasoningStartedAt = System.currentTimeMillis()
                        }
                        if (reasoningStartedAt != null && reasoningEndedAt == null && AssistantContent.hasReasoningEnded(tail)) {
                            reasoningEndedAt = System.currentTimeMillis()
                        }
                        unsaved = true
                    }
                } finally {
                    saver.cancel()
                }
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
        const val MAX_CONCURRENT_ANSWERS = 2
        private const val UI_UPDATE_INTERVAL_MS = 200L
        /** Longer than any reasoning tag ("<|channel>thought", "</reasoning>"). */
        private const val TAG_WINDOW = 32
        private const val MAX_HISTORY_ATTACHMENT_PER_FILE = 4_000
        private const val MAX_HISTORY_ATTACHMENT_CHARS = 6_000
    }
}
